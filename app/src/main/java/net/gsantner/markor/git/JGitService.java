/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * {@link GitService} on JGit. A thin facade: repository discovery lives here, local operations are
 * delegated to {@link JGitLocalOps} (lane git-core-local) and remote/merge-state operations to
 * {@link JGitRemoteOps} (lane git-core-api), so both can be developed without touching this file.
 * <p>
 * Stateless; one instance can serve the whole app. See {@link GitService} for the threading rules.
 */
public final class JGitService implements GitService {
    private final JGitLocalOps _local;
    private final JGitRemoteOps _remote;

    public JGitService() {
        this(new JGitLocalOps(), new JGitRemoteOps());
    }

    JGitService(final JGitLocalOps local, final JGitRemoteOps remote) {
        _local = local;
        _remote = remote;
    }

    // ---------------------------------------------------------------- discovery

    @Override
    public GitResult<GitRepoInfo> open(final File anyPathInsideRepo, final GitProgress progress) {
        try (Repository repo = JGitRepos.open(anyPathInsideRepo)) {
            return GitResult.ok(JGitRepos.describe(repo));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, anyPathInsideRepo);
        }
    }

    @Override
    public boolean isRepository(final File anyPath) {
        return JGitRepos.findWorkTree(anyPath) != null;
    }

    @Override
    public File findRepositoryRoot(final File anyPath) {
        return JGitRepos.findWorkTree(anyPath);
    }

    // ---------------------------------------------------------------- local

    @Override
    public GitResult<GitRepoInfo> init(final File dir, final GitProgress progress) {
        return _local.init(dir, progress);
    }

    @Override
    public GitResult<List<GitStatusEntry>> status(final File repoDir, final GitProgress progress) {
        return _local.status(repoDir, progress);
    }

    @Override
    public GitResult<List<GitCommitInfo>> log(final File repoDir, final int limit, final int skip, final GitProgress progress) {
        return _local.log(repoDir, limit, skip, progress);
    }

    @Override
    public GitResult<GitDiff> diffWorkingTree(final File repoDir, final String path, final GitProgress progress) {
        return _local.diffWorkingTree(repoDir, path, progress);
    }

    @Override
    public GitResult<GitDiff> diffForCommit(final File repoDir, final String sha, final GitProgress progress) {
        return _local.diffForCommit(repoDir, sha, progress);
    }

    @Override
    public GitResult<GitCommitInfo> commit(final File repoDir, final String message, final Collection<String> paths,
                                           final GitAuthor author, final GitProgress progress) {
        return _local.commit(repoDir, message, paths, author, progress);
    }

    // ---------------------------------------------------------------- remote

    @Override
    public GitResult<GitRepoInfo> clone(final String url, final File targetDir, final GitCredentialsSource credentials, final GitProgress progress) {
        return _remote.clone(url, targetDir, credentials, progress);
    }

    @Override
    public GitResult<GitAheadBehind> fetch(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
        return _remote.fetch(repoDir, credentials, progress);
    }

    @Override
    public GitResult<GitRepoInfo> pull(final File repoDir, final GitPullStrategy strategy, final GitCredentialsSource credentials,
                                       final GitAuthor author, final GitProgress progress) {
        return _remote.pull(repoDir, strategy, credentials, author, progress);
    }

    @Override
    public GitResult<Void> push(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
        return _remote.push(repoDir, credentials, progress);
    }

    @Override
    public GitResult<GitAheadBehind> aheadBehind(final File repoDir, final GitProgress progress) {
        return _remote.aheadBehind(repoDir, progress);
    }

    @Override
    public GitResult<GitRepoInfo> abortMergeOrRebase(final File repoDir, final GitProgress progress) {
        return _remote.abortMergeOrRebase(repoDir, progress);
    }

    @Override
    public GitResult<GitRepoInfo> continueAfterConflictResolution(final File repoDir, final String message, final GitAuthor author, final GitProgress progress) {
        return _remote.continueAfterConflictResolution(repoDir, message, author, progress);
    }

    @Override
    public GitResult<Void> setRemoteUrl(final File repoDir, final String url, final GitProgress progress) {
        return _remote.setRemoteUrl(repoDir, url, progress);
    }

    @Override
    public GitResult<List<String>> lsRemote(final String url, final GitCredentialsSource credentials, final GitProgress progress) {
        return _remote.lsRemote(url, credentials, progress);
    }
}
