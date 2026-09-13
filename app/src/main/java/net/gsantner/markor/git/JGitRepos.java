/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Shared, package-private JGit plumbing used by the facade and both ops classes: locate and open
 * a repository from any path inside it, describe it as a {@link GitRepoInfo}, and strip userinfo
 * from URLs before they are shown or logged. Owned by lane git-core-api; other lanes call it, they
 * do not need to change it.
 */
final class JGitRepos {

    /** Default remote name used when a branch has no remote configured. */
    static final String DEFAULT_REMOTE = Constants.DEFAULT_REMOTE_NAME;

    private JGitRepos() {
    }

    /**
     * Walks up from {@code anyPath} (a file or folder) until a folder containing {@code .git} is found.
     *
     * @return the working-tree root, or {@code null} when none is found or the repository is bare
     */
    static File findWorkTree(final File anyPath) {
        if (anyPath == null) {
            return null;
        }
        try (Repository repo = open(anyPath)) {
            return repo.getWorkTree();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Opens the non-bare repository that contains {@code anyPath}. The caller closes it.
     *
     * @throws RepositoryNotFoundException when there is none, or it is bare
     * @throws IOException                 when the repository cannot be read
     */
    static Repository open(final File anyPath) throws IOException {
        if (anyPath == null) {
            throw new RepositoryNotFoundException("(null)");
        }
        final File start = anyPath.isDirectory() ? anyPath : anyPath.getAbsoluteFile().getParentFile();
        if (start == null) {
            throw new RepositoryNotFoundException(anyPath);
        }
        final FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .setMustExist(true)
                .findGitDir(start.getAbsoluteFile());
        if (builder.getGitDir() == null) {
            throw new RepositoryNotFoundException(anyPath);
        }
        final Repository repo = builder.build();
        if (repo.isBare() || repo.getWorkTree() == null) {
            repo.close();
            throw new RepositoryNotFoundException(anyPath);
        }
        return repo;
    }

    /** Builds the header snapshot. Does not walk the working tree. */
    static GitRepoInfo describe(final Repository repo) throws IOException {
        final Ref head = repo.exactRef(Constants.HEAD);
        final ObjectId headId = head == null ? null : head.getObjectId();
        final boolean detached = head != null && !head.isSymbolic();
        final String branch = repo.getBranch();
        final String shortBranch = detached
                ? (branch != null && branch.length() > GitCommitInfo.SHORT_SHA_LENGTH ? branch.substring(0, GitCommitInfo.SHORT_SHA_LENGTH) : branch)
                : branch;

        final StoredConfig config = repo.getConfig();
        final String remoteName = remoteFor(repo);
        String upstream = null;
        if (!detached && branch != null) {
            final String tracking = new BranchConfig(config, branch).getTrackingBranch();
            if (tracking != null) {
                upstream = Repository.shortenRefName(tracking);
            }
        }
        final String rawUrl = remoteName == null ? null : config.getString(ConfigConstants.CONFIG_REMOTE_SECTION, remoteName, ConfigConstants.CONFIG_KEY_URL);
        final String remoteUrl = rawUrl == null ? null : sanitizeUrl(rawUrl);

        final File fetchHead = new File(repo.getDirectory(), Constants.FETCH_HEAD);
        final long lastFetch = fetchHead.isFile() ? fetchHead.lastModified() : 0L;

        return new GitRepoInfo(repo.getWorkTree(), shortBranch, headId == null ? null : headId.name(), detached,
                mapState(repo.getRepositoryState()), remoteName, remoteUrl, upstream, lastFetch);
    }

    /**
     * The remote that pull/push use: the current branch's configured remote when it exists, else
     * {@code origin}, else the first configured remote.
     *
     * @return remote name, or {@code null} when the repository has no remotes
     */
    static String remoteFor(final Repository repo) throws IOException {
        final StoredConfig config = repo.getConfig();
        final Set<String> remotes = config.getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION);
        if (remotes.isEmpty()) {
            return null;
        }
        final Ref head = repo.exactRef(Constants.HEAD);
        if (head != null && head.isSymbolic()) {
            final String configured = new BranchConfig(config, repo.getBranch()).getRemote();
            if (configured != null && remotes.contains(configured)) {
                return configured;
            }
        }
        if (remotes.contains(DEFAULT_REMOTE)) {
            return DEFAULT_REMOTE;
        }
        return remotes.iterator().next();
    }

    /**
     * Full name of the remote-tracking ref the branch integrates from: its configured upstream when it
     * belongs to {@code remote}, otherwise {@code refs/remotes/<remote>/<branch>}.
     */
    static String trackingRefFor(final Repository repo, final String branch, final String remote) {
        final BranchConfig branchConfig = new BranchConfig(repo.getConfig(), branch);
        final String tracking = branchConfig.getTrackingBranch();
        if (tracking != null && remote.equals(branchConfig.getRemote())) {
            return tracking;
        }
        return Constants.R_REMOTES + remote + "/" + branch;
    }

    static GitRepoState mapState(final RepositoryState state) {
        if (state == null) {
            return GitRepoState.OTHER;
        }
        switch (state) {
            case SAFE:
                return GitRepoState.NORMAL;
            case MERGING:
            case MERGING_RESOLVED:
                return GitRepoState.MERGING;
            case REBASING:
            case REBASING_REBASING:
            case REBASING_MERGE:
            case REBASING_INTERACTIVE:
                return GitRepoState.REBASING;
            default:
                return GitRepoState.OTHER;
        }
    }

    /**
     * Removes username and password from a URL so it can be displayed or logged.
     * Non-URL strings (e.g. scp-like {@code host:path}) are returned unchanged except for a
     * best-effort removal of a {@code user:pass@} part.
     */
    static String sanitizeUrl(final String url) {
        if (url == null) {
            return null;
        }
        try {
            final URIish uri = new URIish(url);
            if (uri.getUser() == null && uri.getPass() == null) {
                return url;
            }
            return uri.setUser(null).setPass(null).toString();
        } catch (URISyntaxException e) {
            return url.replaceFirst("(?i)^([a-z][a-z0-9+.-]*://)[^/@]*@", "$1");
        }
    }

    /**
     * @return {@code true} when the URL carries a password. Such URLs are refused so the secret never
     * lands in {@code .git/config}.
     */
    static boolean hasPassword(final String url) {
        if (url == null) {
            return false;
        }
        try {
            return new URIish(url).getPass() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
