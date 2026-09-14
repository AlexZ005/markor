/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevWalkUtils;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Remote and merge-state operations behind {@link JGitService}: clone, fetch, pull, push,
 * ahead/behind, abort and continue after conflicts, remote URL, ls-remote.
 * Owned by lane <b>git-core-api</b> (roadmap task 2.3). Contract: the {@link GitService} Javadoc.
 * <p>
 * Credentials are wrapped in a fresh {@link JGitCredentials} per call and never stored. Every JGit
 * exception is routed through {@link JGitErrors#map}; messages are sanitized there.
 */
final class JGitRemoteOps {

    /** Socket timeout for remote operations, in seconds (per read, not per operation). */
    static final int TIMEOUT_SECONDS = 30;

    /** Files larger than this are not scanned for conflict markers (they are assumed resolved). */
    private static final long MAX_MARKER_SCAN_BYTES = 8L * 1024 * 1024;

    private static final String NO_REMOTE = "No remote configured; add one first";
    private static final String DETACHED = "Detached HEAD: check out a branch first";

    // ---------------------------------------------------------------- clone

    GitResult<GitRepoInfo> clone(final String url, final File targetDir, final GitCredentialsSource credentials, final GitProgress progress) {
        final GitResult<URIish> parsed = parseUrl(url);
        if (!parsed.isOk()) {
            return parsed.asError();
        }
        if (targetDir == null) {
            return GitResult.failed("No target folder given");
        }
        if (targetDir.exists()) {
            if (!targetDir.isDirectory()) {
                return GitResult.failed("Target is not a folder: " + targetDir.getPath());
            }
            final String[] children = targetDir.list();
            if (children != null && children.length > 0) {
                return GitResult.failed("Folder is not empty: " + targetDir.getPath());
            }
        }
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        final boolean existedBefore = targetDir.exists();
        try (Git git = Git.cloneRepository()
                .setURI(parsed.getValue().toString())
                .setDirectory(targetDir)
                .setCredentialsProvider(JGitCredentials.forSource(credentials))
                .setProgressMonitor(new JGitProgressMonitor(progress))
                .setTimeout(TIMEOUT_SECONDS)
                .call()) {
            JGitRepos.disableAutoGc(git.getRepository());
            return GitResult.ok(JGitRepos.describe(git.getRepository()));
        } catch (Exception e) {
            if (!existedBefore) {
                deleteQuietly(targetDir);
            }
            return JGitErrors.map(e, progress, targetDir);
        }
    }

    // ---------------------------------------------------------------- fetch / ahead-behind

    GitResult<GitAheadBehind> fetch(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            final String remote = JGitRepos.remoteFor(repo);
            if (remote == null) {
                return GitResult.failed(NO_REMOTE);
            }
            doFetch(git, remote, credentials, progress);
            if (isCancelled(progress)) {
                return GitResult.cancelled();
            }
            return GitResult.ok(computeAheadBehind(repo, remote));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    GitResult<GitAheadBehind> aheadBehind(final File repoDir, final GitProgress progress) {
        try (Repository repo = JGitRepos.open(repoDir)) {
            if (isDetached(repo)) {
                return GitResult.failed(DETACHED);
            }
            return GitResult.ok(computeAheadBehind(repo, JGitRepos.remoteFor(repo)));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    private static void doFetch(final Git git, final String remote, final GitCredentialsSource credentials, final GitProgress progress)
            throws GitAPIException, URISyntaxException {
        final RemoteConfig remoteConfig = new RemoteConfig(git.getRepository().getConfig(), remote);
        final List<RefSpec> specs = remoteConfig.getFetchRefSpecs().isEmpty()
                ? Collections.singletonList(new RefSpec("+" + Constants.R_HEADS + "*:" + Constants.R_REMOTES + remote + "/*"))
                : remoteConfig.getFetchRefSpecs();
        git.fetch()
                .setRemote(remote)
                .setRefSpecs(specs)
                .setCredentialsProvider(JGitCredentials.forSource(credentials))
                .setProgressMonitor(new JGitProgressMonitor(progress))
                .setTimeout(TIMEOUT_SECONDS)
                .call();
    }

    /**
     * Ahead/behind of the current branch against its upstream, falling back to {@code <remote>/<branch>}
     * when no upstream is configured. {@link GitAheadBehind#none()} for a detached HEAD or when nothing
     * to compare with exists locally.
     */
    static GitAheadBehind computeAheadBehind(final Repository repo, final String remote) throws IOException {
        final Ref head = repo.exactRef(Constants.HEAD);
        if (head == null || !head.isSymbolic()) {
            return GitAheadBehind.none();
        }
        final String branch = repo.getBranch();
        final BranchTrackingStatus tracked = BranchTrackingStatus.of(repo, branch);
        if (tracked != null) {
            return new GitAheadBehind(Repository.shortenRefName(tracked.getRemoteTrackingBranch()), tracked.getAheadCount(), tracked.getBehindCount());
        }
        if (remote == null) {
            return GitAheadBehind.none();
        }
        final String trackingName = Constants.R_REMOTES + remote + "/" + branch;
        final Ref tracking = repo.exactRef(trackingName);
        if (tracking == null || tracking.getObjectId() == null) {
            return GitAheadBehind.none();
        }
        try (RevWalk walk = new RevWalk(repo)) {
            final RevCommit remoteCommit = walk.parseCommit(tracking.getObjectId());
            final ObjectId localId = repo.resolve(Constants.HEAD);
            final String shortName = Repository.shortenRefName(trackingName);
            if (localId == null) {
                return new GitAheadBehind(shortName, 0, RevWalkUtils.count(walk, remoteCommit, null));
            }
            final RevCommit localCommit = walk.parseCommit(localId);
            final int ahead = RevWalkUtils.count(walk, localCommit, remoteCommit);
            final int behind = RevWalkUtils.count(walk, remoteCommit, localCommit);
            return new GitAheadBehind(shortName, ahead, behind);
        }
    }

    // ---------------------------------------------------------------- pull

    GitResult<GitRepoInfo> pull(final File repoDir, final GitPullStrategy strategy, final GitCredentialsSource credentials,
                                final GitAuthor author, final GitProgress progress) {
        final GitPullStrategy effective = strategy == null ? GitPullStrategy.FF_ONLY : strategy;
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            final RepositoryState state = repo.getRepositoryState();
            if (state != RepositoryState.SAFE) {
                return GitResult.failed(stateBlocks("pull", state));
            }
            if (isDetached(repo)) {
                return GitResult.failed(DETACHED);
            }
            final String branch = repo.getBranch();
            final String remote = JGitRepos.remoteFor(repo);
            if (remote == null) {
                return GitResult.failed(NO_REMOTE);
            }
            applyAuthor(repo, author);
            doFetch(git, remote, credentials, progress);
            if (isCancelled(progress)) {
                return GitResult.cancelled();
            }
            final String trackingName = JGitRepos.trackingRefFor(repo, branch, remote);
            final Ref tracking = repo.exactRef(trackingName);
            if (tracking == null || tracking.getObjectId() == null) {
                return GitResult.failed("Remote branch " + Repository.shortenRefName(trackingName) + " does not exist");
            }
            final ObjectId localId = repo.resolve(Constants.HEAD);
            final GitResult<Void> integrated;
            if (effective == GitPullStrategy.REBASE && localId != null) {
                integrated = rebaseOnto(git, tracking, trackingName, progress);
            } else {
                final MergeCommand.FastForwardMode mode = effective == GitPullStrategy.MERGE && localId != null
                        ? MergeCommand.FastForwardMode.FF
                        : MergeCommand.FastForwardMode.FF_ONLY;
                integrated = mergeFrom(git, tracking, mode, progress);
            }
            if (!integrated.isOk()) {
                return integrated.asError();
            }
            recordUpstreamIfMissing(repo, branch, remote, remoteBranchRefOf(trackingName, remote));
            return GitResult.ok(JGitRepos.describe(repo));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    private static GitResult<Void> mergeFrom(final Git git, final Ref tracking, final MergeCommand.FastForwardMode mode, final GitProgress progress)
            throws GitAPIException {
        final MergeResult result = git.merge()
                .include(tracking)
                .setFastForward(mode)
                .setCommit(true)
                .setProgressMonitor(new JGitProgressMonitor(progress))
                .call();
        final MergeResult.MergeStatus status = result.getMergeStatus();
        switch (status) {
            case ALREADY_UP_TO_DATE:
            case FAST_FORWARD:
            case MERGED:
            case FAST_FORWARD_SQUASHED:
            case MERGED_SQUASHED:
            case MERGED_NOT_COMMITTED:
            case MERGED_SQUASHED_NOT_COMMITTED:
                return GitResult.ok();
            case ABORTED:
                return GitResult.nonFastForward("Local and remote have diverged; rebase or merge to combine them");
            case CONFLICTING:
                return GitResult.conflicts(sorted(result.getConflicts() == null ? null : result.getConflicts().keySet()));
            case CHECKOUT_CONFLICT:
                return GitResult.dirtyWorkTree(sorted(result.getCheckoutConflicts()));
            case FAILED:
                return GitResult.dirtyWorkTree(sorted(result.getFailingPaths() == null ? null : result.getFailingPaths().keySet()));
            case NOT_SUPPORTED:
            default:
                return GitResult.failed("Merge not possible: " + status);
        }
    }

    private static GitResult<Void> rebaseOnto(final Git git, final Ref tracking, final String trackingName, final GitProgress progress)
            throws GitAPIException {
        final RebaseResult result = git.rebase()
                .setUpstream(tracking.getObjectId())
                .setUpstreamName(Repository.shortenRefName(trackingName))
                .setProgressMonitor(new JGitProgressMonitor(progress))
                .call();
        return mapRebase(git, result, progress);
    }

    private static GitResult<Void> mapRebase(final Git git, final RebaseResult result, final GitProgress progress) throws GitAPIException {
        final RebaseResult.Status status = result.getStatus();
        switch (status) {
            case OK:
            case UP_TO_DATE:
            case FAST_FORWARD:
                return GitResult.ok();
            case STOPPED:
                return GitResult.conflicts(conflictingPaths(git));
            case CONFLICTS:
                return GitResult.dirtyWorkTree(sorted(result.getConflicts()));
            case UNCOMMITTED_CHANGES:
                return GitResult.dirtyWorkTree(sorted(result.getUncommittedChanges()));
            case ABORTED:
                return isCancelled(progress) ? GitResult.<Void>cancelled() : GitResult.<Void>failed("Rebase was aborted");
            case FAILED:
                return GitResult.failed("Rebase failed" + (result.getFailingPaths() == null ? "" : ": " + sorted(result.getFailingPaths().keySet())));
            case NOTHING_TO_COMMIT:
                return GitResult.failed("Nothing to commit: the resolved file equals the upstream version");
            default:
                return GitResult.failed("Rebase stopped: " + status);
        }
    }

    // ---------------------------------------------------------------- push

    GitResult<Void> push(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            final RepositoryState state = repo.getRepositoryState();
            if (state != RepositoryState.SAFE) {
                return GitResult.failed(stateBlocks("push", state));
            }
            if (isDetached(repo)) {
                return GitResult.failed(DETACHED);
            }
            final String branch = repo.getBranch();
            final String remote = JGitRepos.remoteFor(repo);
            if (remote == null) {
                return GitResult.failed(NO_REMOTE);
            }
            if (repo.resolve(Constants.HEAD) == null) {
                return GitResult.failed("Nothing to push: the repository has no commits yet");
            }
            final BranchConfig branchConfig = new BranchConfig(repo.getConfig(), branch);
            final String remoteBranchRef = branchConfig.getMerge() != null && remote.equals(branchConfig.getRemote())
                    ? branchConfig.getMerge()
                    : Constants.R_HEADS + branch;
            final Iterable<PushResult> results = git.push()
                    .setRemote(remote)
                    .setRefSpecs(new RefSpec(Constants.R_HEADS + branch + ":" + remoteBranchRef))
                    .setCredentialsProvider(JGitCredentials.forSource(credentials))
                    .setProgressMonitor(new JGitProgressMonitor(progress))
                    .setTimeout(TIMEOUT_SECONDS)
                    .call();
            if (isCancelled(progress)) {
                return GitResult.cancelled();
            }
            for (final PushResult pushResult : results) {
                for (final RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                    switch (update.getStatus()) {
                        case OK:
                        case UP_TO_DATE:
                            break;
                        case REJECTED_NONFASTFORWARD:
                            return GitResult.nonFastForward("The remote has commits you do not have yet: pull first");
                        case REJECTED_REMOTE_CHANGED:
                            return GitResult.nonFastForward("The remote branch changed meanwhile: pull first");
                        default:
                            return GitResult.failed("Push rejected (" + update.getStatus() + ")"
                                    + (update.getMessage() == null ? "" : ": " + JGitErrors.sanitize(update.getMessage())));
                    }
                }
            }
            recordUpstreamIfMissing(repo, branch, remote, remoteBranchRef);
            return GitResult.ok();
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    // ---------------------------------------------------------------- conflict handling

    GitResult<GitRepoInfo> abortMergeOrRebase(final File repoDir, final GitProgress progress) {
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            final RepositoryState state = repo.getRepositoryState();
            switch (JGitRepos.mapState(state)) {
                case MERGING:
                    abortMerge(git, repo);
                    break;
                case REBASING: {
                    final RebaseResult result = git.rebase()
                            .setOperation(RebaseCommand.Operation.ABORT)
                            .setProgressMonitor(new JGitProgressMonitor(progress))
                            .call();
                    if (result.getStatus() != RebaseResult.Status.ABORTED) {
                        return GitResult.failed("Could not abort the rebase: " + result.getStatus());
                    }
                    break;
                }
                case NORMAL:
                    return GitResult.failed("Nothing to abort: no merge or rebase is in progress");
                default:
                    // Cherry-pick, revert, ...: not started by this app; a hard reset to HEAD clears them.
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
                    break;
            }
            if (repo.getRepositoryState() != RepositoryState.SAFE) {
                return GitResult.failed("Repository is still in state " + repo.getRepositoryState() + " after aborting");
            }
            return GitResult.ok(JGitRepos.describe(repo));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    /**
     * {@code git merge --abort} without JGit's (unimplemented) {@code reset --merge}: restore only the paths
     * the merge touched (everything whose index entry differs from HEAD, plus the conflicts), so that
     * unrelated uncommitted edits survive, then drop MERGE_HEAD/MERGE_MSG.
     */
    private static void abortMerge(final Git git, final Repository repo) throws GitAPIException, IOException {
        final Status status = git.status().call();
        final Set<String> touched = new TreeSet<>();
        touched.addAll(status.getAdded());
        touched.addAll(status.getChanged());
        touched.addAll(status.getRemoved());
        touched.addAll(status.getConflicting());
        if (!touched.isEmpty()) {
            final Set<String> inHead = new HashSet<>();
            final ObjectId headId = repo.resolve(Constants.HEAD);
            if (headId != null) {
                try (RevWalk walk = new RevWalk(repo); TreeWalk treeWalk = new TreeWalk(repo)) {
                    treeWalk.addTree(walk.parseCommit(headId).getTree());
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilterGroup.createFromStrings(touched));
                    while (treeWalk.next()) {
                        inHead.add(treeWalk.getPathString());
                    }
                }
            }
            final ResetCommand reset = git.reset();
            for (final String path : touched) {
                reset.addPath(path);
            }
            reset.call();
            final List<String> restore = new ArrayList<>();
            for (final String path : touched) {
                if (inHead.contains(path)) {
                    restore.add(path);
                } else {
                    deleteQuietly(new File(repo.getWorkTree(), path));
                }
            }
            if (!restore.isEmpty()) {
                final CheckoutCommand checkout = git.checkout();
                for (final String path : restore) {
                    checkout.addPath(path);
                }
                checkout.call();
            }
        }
        repo.writeMergeCommitMsg(null);
        repo.writeMergeHeads(null);
    }

    GitResult<GitRepoInfo> continueAfterConflictResolution(final File repoDir, final String message, final GitAuthor author, final GitProgress progress) {
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            final GitRepoState state = JGitRepos.mapState(repo.getRepositoryState());
            if (state != GitRepoState.MERGING && state != GitRepoState.REBASING) {
                return GitResult.failed("Nothing to continue: no merge or rebase is in progress");
            }
            applyAuthor(repo, author);
            final Set<String> conflicting = conflictingPaths(git);
            final List<String> stillMarked = filesWithConflictMarkers(repo.getWorkTree(), conflicting);
            if (!stillMarked.isEmpty()) {
                return GitResult.conflicts(stillMarked);
            }
            stage(git, repo.getWorkTree(), conflicting);
            if (state == GitRepoState.MERGING) {
                String text = message == null || message.trim().isEmpty() ? repo.readMergeCommitMsg() : message;
                if (text == null || text.trim().isEmpty()) {
                    text = "Merge";
                }
                final CommitCommand commit = git.commit().setMessage(text);
                if (author != null) {
                    commit.setAuthor(author.getName(), author.getEmail()).setCommitter(author.getName(), author.getEmail());
                }
                commit.call();
            } else {
                RebaseResult result = git.rebase()
                        .setOperation(RebaseCommand.Operation.CONTINUE)
                        .setProgressMonitor(new JGitProgressMonitor(progress))
                        .call();
                if (result.getStatus() == RebaseResult.Status.NOTHING_TO_COMMIT) {
                    // Resolution made the commit empty (took the upstream version): skip it, like cgit suggests.
                    result = git.rebase()
                            .setOperation(RebaseCommand.Operation.SKIP)
                            .setProgressMonitor(new JGitProgressMonitor(progress))
                            .call();
                }
                final GitResult<Void> mapped = mapRebase(git, result, progress);
                if (!mapped.isOk()) {
                    return mapped.asError();
                }
            }
            return GitResult.ok(JGitRepos.describe(repo));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    /** Stages the formerly conflicted paths: existing files are added, missing ones removed. */
    private static void stage(final Git git, final File workTree, final Collection<String> paths) throws GitAPIException {
        if (paths.isEmpty()) {
            return;
        }
        AddCommand add = null;
        RmCommand rm = null;
        for (final String path : paths) {
            if (new File(workTree, path).exists()) {
                add = (add == null ? git.add() : add).addFilepattern(path);
            } else {
                rm = (rm == null ? git.rm() : rm).addFilepattern(path);
            }
        }
        if (add != null) {
            add.call();
        }
        if (rm != null) {
            rm.call();
        }
    }

    /** @return those of {@code paths} whose file still has a line starting with {@code <<<<<<<} or {@code >>>>>>>} */
    static List<String> filesWithConflictMarkers(final File workTree, final Collection<String> paths) throws IOException {
        final List<String> marked = new ArrayList<>();
        for (final String path : paths) {
            final File file = new File(workTree, path);
            if (!file.isFile() || file.length() > MAX_MARKER_SCAN_BYTES) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isConflictMarker(line)) {
                        marked.add(path);
                        break;
                    }
                }
            }
        }
        return marked;
    }

    static boolean isConflictMarker(final String line) {
        if (line.length() < 7) {
            return false;
        }
        final String head = line.substring(0, 7);
        if (!head.equals("<<<<<<<") && !head.equals(">>>>>>>")) {
            return false;
        }
        return line.length() == 7 || line.charAt(7) == ' ' || line.charAt(7) == '\t';
    }

    // ---------------------------------------------------------------- remote configuration / ls-remote

    GitResult<Void> setRemoteUrl(final File repoDir, final String url, final GitProgress progress) {
        final GitResult<URIish> parsed = parseUrl(url);
        if (!parsed.isOk()) {
            return parsed.asError();
        }
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            final String remote = JGitRepos.DEFAULT_REMOTE;
            if (repo.getConfig().getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION).contains(remote)) {
                git.remoteSetUrl().setRemoteName(remote).setRemoteUri(parsed.getValue()).call();
            } else {
                git.remoteAdd().setName(remote).setUri(parsed.getValue()).call();
            }
            return GitResult.ok();
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        }
    }

    GitResult<List<String>> lsRemote(final String url, final GitCredentialsSource credentials, final GitProgress progress) {
        final GitResult<URIish> parsed = parseUrl(url);
        if (!parsed.isOk()) {
            return parsed.asError();
        }
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        try {
            final Collection<Ref> refs = Git.lsRemoteRepository()
                    .setRemote(parsed.getValue().toString())
                    .setHeads(true)
                    .setCredentialsProvider(JGitCredentials.forSource(credentials))
                    .setTimeout(TIMEOUT_SECONDS)
                    .call();
            final TreeSet<String> names = new TreeSet<>();
            for (final Ref ref : refs) {
                if (ref.getName().startsWith(Constants.R_HEADS)) {
                    names.add(Repository.shortenRefName(ref.getName()));
                }
            }
            return GitResult.ok(new ArrayList<>(names));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, null);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static GitResult<URIish> parseUrl(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return GitResult.failed("The URL is empty");
        }
        if (JGitRepos.hasPassword(url)) {
            return GitResult.failed("Remove the password from the URL and enter the token in the credentials field instead");
        }
        try {
            return GitResult.ok(new URIish(url.trim()));
        } catch (URISyntaxException e) {
            return GitResult.failed("Invalid URL: " + JGitRepos.sanitizeUrl(url.trim()));
        }
    }

    private static boolean isCancelled(final GitProgress progress) {
        return progress != null && progress.isCancelled();
    }

    private static boolean isDetached(final Repository repo) throws IOException {
        final Ref head = repo.exactRef(Constants.HEAD);
        return head != null && !head.isSymbolic();
    }

    private static String stateBlocks(final String operation, final RepositoryState state) {
        return "Cannot " + operation + " while the repository is " + JGitRepos.mapState(state).name().toLowerCase()
                + "; finish or abort that first";
    }

    /** Writes user.name/user.email into the repository configuration when they differ from {@code author}. */
    private static void applyAuthor(final Repository repo, final GitAuthor author) throws IOException {
        if (author == null) {
            return;
        }
        final StoredConfig config = repo.getConfig();
        final String name = config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME);
        final String email = config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL);
        if (author.getName().equals(name) && author.getEmail().equals(email)) {
            return;
        }
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, author.getName());
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, author.getEmail());
        config.save();
    }

    /** Records {@code branch.<branch>.remote/merge} when neither is set yet. */
    private static void recordUpstreamIfMissing(final Repository repo, final String branch, final String remote, final String remoteBranchRef) throws IOException {
        final StoredConfig config = repo.getConfig();
        final String haveRemote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE);
        final String haveMerge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE);
        if (haveRemote != null && haveMerge != null) {
            return;
        }
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE, remote);
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE, remoteBranchRef);
        config.save();
    }

    /** {@code refs/remotes/<remote>/<x>} to the remote's own {@code refs/heads/<x>}. */
    private static String remoteBranchRefOf(final String trackingName, final String remote) {
        final String prefix = Constants.R_REMOTES + remote + "/";
        return trackingName.startsWith(prefix) ? Constants.R_HEADS + trackingName.substring(prefix.length()) : trackingName;
    }

    private static Set<String> conflictingPaths(final Git git) throws GitAPIException {
        return new TreeSet<>(git.status().call().getConflicting());
    }

    private static List<String> sorted(final Collection<String> paths) {
        return paths == null ? Collections.<String>emptyList() : new ArrayList<>(new TreeSet<>(paths));
    }

    private static void deleteQuietly(final File file) {
        try {
            FileUtils.delete(file, FileUtils.RECURSIVE | FileUtils.SKIP_MISSING | FileUtils.IGNORE_ERRORS);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
