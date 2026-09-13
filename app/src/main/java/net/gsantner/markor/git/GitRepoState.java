/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * The operation a repository is in the middle of. Drives the "Resolving conflicts" banner:
 * anything other than {@link #NORMAL} means {@link GitService#continueAfterConflictResolution}
 * or {@link GitService#abortMergeOrRebase} should be offered.
 */
public enum GitRepoState {
    /** Nothing in progress. Commit, pull and push are allowed. */
    NORMAL,
    /** A merge stopped on conflicts, or all conflicts are resolved but the merge commit is not yet made. */
    MERGING,
    /** A rebase stopped on conflicts (or is otherwise unfinished). */
    REBASING,
    /** Cherry-pick, revert, bisect or another state this app does not drive. Only abort is offered. */
    OTHER
}
