/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;
import java.util.List;

/**
 * Remote and merge-state operations behind {@link JGitService}: clone, fetch, pull, push,
 * ahead/behind, abort and continue after conflicts, remote URL, ls-remote.
 * Owned by lane <b>git-core-api</b> (roadmap task 2.3). Contract: the {@link GitService} Javadoc.
 */
final class JGitRemoteOps {

    private static final String NOT_IMPLEMENTED = "not implemented (task 2.3)";

    GitResult<GitRepoInfo> clone(final String url, final File targetDir, final GitCredentialsSource credentials, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitAheadBehind> fetch(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitRepoInfo> pull(final File repoDir, final GitPullStrategy strategy, final GitCredentialsSource credentials,
                                final GitAuthor author, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<Void> push(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitAheadBehind> aheadBehind(final File repoDir, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitRepoInfo> abortMergeOrRebase(final File repoDir, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitRepoInfo> continueAfterConflictResolution(final File repoDir, final String message, final GitAuthor author, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<Void> setRemoteUrl(final File repoDir, final String url, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<List<String>> lsRemote(final String url, final GitCredentialsSource credentials, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }
}
