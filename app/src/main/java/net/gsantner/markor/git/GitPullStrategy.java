/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/** How {@link GitService#pull} integrates fetched commits into the current branch. */
public enum GitPullStrategy {
    /**
     * Default. Only move the branch forward. When local and remote have diverged the pull returns
     * {@link GitResult.Kind#NON_FAST_FORWARD} and changes nothing; the UI then offers REBASE or MERGE.
     */
    FF_ONLY,
    /** Replay local commits on top of the remote branch ({@code git pull --rebase}). Requires a clean work tree. */
    REBASE,
    /** Create a merge commit when diverged ({@code git pull --no-rebase}); fast-forwards when possible. */
    MERGE
}
