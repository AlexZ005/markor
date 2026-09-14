/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;

/**
 * Which of the Git tab's three screens is shown. Split out of the fragment so the rule that picks
 * one is a plain-Java function with unit tests instead of a chain of {@code setVisibility} calls.
 *
 * @see GitRepoRegistry#getActive()
 */
public enum GitTabState {

    /** Nothing configured yet: explain the tab, offer "Select working folder" and "Clone repository". */
    NO_REPOSITORY,

    /** A folder was picked that is not a repository: offer "Initialize repository here" / "Clone into this folder". */
    FOLDER_NOT_A_REPOSITORY,

    /** A repository is active: header, actions, Changes and History. */
    REPOSITORY_OPEN;

    /**
     * Picks the state.
     * <p>
     * A folder the user just picked wins over an open repository: "Add folder…" has to reach the
     * initialize/clone screen even while another repository is active. The caller drops the pending
     * folder as soon as a repository is activated (or cloned, or initialized), which is what brings
     * the repository screen back.
     *
     * @param activeRepoRoot working folder of the active repository, or {@code null} when none is active
     * @param pendingFolder  folder the user selected that turned out not to be a repository, or {@code null}
     * @return the state to render
     */
    public static GitTabState select(final File activeRepoRoot, final File pendingFolder) {
        if (pendingFolder != null) {
            return FOLDER_NOT_A_REPOSITORY;
        }
        return activeRepoRoot != null ? REPOSITORY_OPEN : NO_REPOSITORY;
    }

    /** @return {@code true} for the two states that show the setup screen rather than a repository */
    public boolean isSetup() {
        return this != REPOSITORY_OPEN;
    }
}
