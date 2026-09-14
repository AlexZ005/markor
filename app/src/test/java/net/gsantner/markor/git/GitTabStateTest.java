/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.io.File;

public class GitTabStateTest {

    private static final File REPO = new File("/storage/emulated/0/Documents/notes");
    private static final File FOLDER = new File("/storage/emulated/0/Documents/plain");

    @Test
    public void nothingConfiguredShowsTheIntroduction() {
        assertThat(GitTabState.select(null, null)).isEqualTo(GitTabState.NO_REPOSITORY);
    }

    @Test
    public void aPickedFolderWithoutGitOffersInitAndClone() {
        assertThat(GitTabState.select(null, FOLDER)).isEqualTo(GitTabState.FOLDER_NOT_A_REPOSITORY);
    }

    @Test
    public void anActiveRepositoryIsShown() {
        assertThat(GitTabState.select(REPO, null)).isEqualTo(GitTabState.REPOSITORY_OPEN);
    }

    @Test
    public void anActiveRepositoryWinsOverAForgottenPickedFolder() {
        assertThat(GitTabState.select(REPO, FOLDER)).isEqualTo(GitTabState.REPOSITORY_OPEN);
    }

    @Test
    public void onlyTheOpenRepositoryIsNotASetupScreen() {
        assertThat(GitTabState.NO_REPOSITORY.isSetup()).isTrue();
        assertThat(GitTabState.FOLDER_NOT_A_REPOSITORY.isSetup()).isTrue();
        assertThat(GitTabState.REPOSITORY_OPEN.isSetup()).isFalse();
    }
}
