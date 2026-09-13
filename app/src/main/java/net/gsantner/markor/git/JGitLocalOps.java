/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Local repository operations behind {@link JGitService}: init, status, log, diff, commit.
 * Owned by lane <b>git-core-local</b> (roadmap task 2.2). The contract for each method is the
 * Javadoc of the corresponding {@link GitService} method; keep the signatures, fill in the bodies.
 * <p>
 * Helpers available from this package: {@link JGitRepos#open(File)} opens the repository from any
 * path inside it (throws {@code RepositoryNotFoundException} otherwise), {@link JGitRepos#describe}
 * builds a {@link GitRepoInfo}, {@link JGitErrors#map} turns a caught exception into the right
 * {@link GitResult}, and {@link JGitProgressMonitor} adapts a {@link GitProgress} for JGit commands.
 * <p>
 * Every method here is a stub returning {@code FAILED("not implemented")} until task 2.2 lands.
 */
final class JGitLocalOps {

    private static final String NOT_IMPLEMENTED = "not implemented (task 2.2)";

    GitResult<GitRepoInfo> init(final File dir, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<List<GitStatusEntry>> status(final File repoDir, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<List<GitCommitInfo>> log(final File repoDir, final int limit, final int skip, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitDiff> diffWorkingTree(final File repoDir, final String path, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitDiff> diffForCommit(final File repoDir, final String sha, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }

    GitResult<GitCommitInfo> commit(final File repoDir, final String message, final Collection<String> paths,
                                    final GitAuthor author, final GitProgress progress) {
        return GitResult.failed(NOT_IMPLEMENTED);
    }
}
