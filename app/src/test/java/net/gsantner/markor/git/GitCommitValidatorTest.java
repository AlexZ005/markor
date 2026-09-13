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

public class GitCommitValidatorTest {

    @Test
    public void aMessageAndASelectionIsValid() {
        final GitCommitValidator.Result result = GitCommitValidator.validate("Update notes", 2);

        assertThat(result.isValid()).isTrue();
        assertThat(result.isEmptyMessage()).isFalse();
        assertThat(result.isNothingSelected()).isFalse();
        assertThat(result.firstProblem()).isEqualTo(GitCommitValidator.Problem.NONE);
    }

    @Test
    public void aBlankMessageIsReportedAsEmpty() {
        assertThat(GitCommitValidator.validate(null, 1).isEmptyMessage()).isTrue();
        assertThat(GitCommitValidator.validate("", 1).isEmptyMessage()).isTrue();
        assertThat(GitCommitValidator.validate("   ", 1).isEmptyMessage()).isTrue();
        assertThat(GitCommitValidator.validate("\n \t \n", 1).isEmptyMessage()).isTrue();
        assertThat(GitCommitValidator.validate("   ", 1).firstProblem())
                .isEqualTo(GitCommitValidator.Problem.EMPTY_MESSAGE);
    }

    @Test
    public void noSelectionIsReportedAndOutranksAMissingMessage() {
        assertThat(GitCommitValidator.validate("Update notes", 0).isNothingSelected()).isTrue();
        assertThat(GitCommitValidator.validate("Update notes", -1).isNothingSelected()).isTrue();

        final GitCommitValidator.Result both = GitCommitValidator.validate("", 0);
        assertThat(both.isValid()).isFalse();
        assertThat(both.isEmptyMessage()).isTrue();
        assertThat(both.isNothingSelected()).isTrue();
        assertThat(both.firstProblem()).isEqualTo(GitCommitValidator.Problem.NOTHING_SELECTED);
    }

    @Test
    public void normalizeStripsSurroundingBlankLinesAndTrailingSpaces() {
        assertThat(GitCommitValidator.normalizeMessage("\n\n  Subject   \n\n  Body  \n\n\n"))
                .isEqualTo("  Subject\n\n  Body");
        assertThat(GitCommitValidator.normalizeMessage("Subject\t \n")).isEqualTo("Subject");
    }

    @Test
    public void normalizeKeepsBlankLinesInsideTheMessage() {
        assertThat(GitCommitValidator.normalizeMessage("Subject\n\nBody line one\nBody line two"))
                .isEqualTo("Subject\n\nBody line one\nBody line two");
    }

    @Test
    public void normalizeConvertsWindowsAndOldMacLineEndings() {
        assertThat(GitCommitValidator.normalizeMessage("Subject\r\n\r\nBody")).isEqualTo("Subject\n\nBody");
        assertThat(GitCommitValidator.normalizeMessage("Subject\r\rBody")).isEqualTo("Subject\n\nBody");
    }

    @Test
    public void normalizeOfABlankMessageIsTheEmptyString() {
        assertThat(GitCommitValidator.normalizeMessage(null)).isEmpty();
        assertThat(GitCommitValidator.normalizeMessage(" \n\t\n ")).isEmpty();
        assertThat(GitCommitValidator.isMessageValid(" \n\t\n ")).isFalse();
        assertThat(GitCommitValidator.isMessageValid("x")).isTrue();
    }
}
