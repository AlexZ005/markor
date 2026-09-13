/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.ADDED;
import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.CONTEXT;
import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.FILE_HEADER;
import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.HUNK_HEADER;
import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.META;
import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.REMOVED;
import static net.gsantner.markor.git.ui.DiffTextFormatter.LineKind.TRUNCATION_FOOTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.gsantner.markor.git.ui.DiffTextFormatter.Formatted;
import net.gsantner.markor.git.ui.DiffTextFormatter.Line;
import net.gsantner.markor.git.ui.DiffTextFormatter.LineKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DiffTextFormatterTest {

    private static final String SAMPLE = ""
            + "diff --git a/notes.md b/notes.md\n"
            + "index 0f1e2d3..4c5b6a7 100644\n"
            + "--- a/notes.md\n"
            + "+++ b/notes.md\n"
            + "@@ -1,5 +1,6 @@\n"
            + " # Notes\n"
            + " \n"
            + "-old line\n"
            + "+new line\n"
            + "+another new line\n"
            + " tail\n";

    private static List<LineKind> kinds(final Formatted f) {
        final List<LineKind> out = new ArrayList<>();
        for (final Line l : f.getLines()) {
            out.add(l.getKind());
        }
        return out;
    }

    private static String textOf(final Formatted f, final int index) {
        final Line l = f.getLines().get(index);
        return f.getText().substring(l.getStart(), l.getEnd());
    }

    @Test
    public void classifiesEveryLineKindOfARealDiff() {
        final Formatted f = DiffTextFormatter.format(SAMPLE);

        assertThat(kinds(f)).containsExactly(
                FILE_HEADER,  // diff --git
                META,         // index
                FILE_HEADER,  // ---
                FILE_HEADER,  // +++
                HUNK_HEADER,  // @@
                CONTEXT,      // " # Notes"
                CONTEXT,      // " "
                REMOVED,
                ADDED,
                ADDED,
                CONTEXT);
        assertThat(f.isTruncated()).isFalse();
        assertThat(f.getTotalLines()).isEqualTo(11);
        assertThat(f.getShownLines()).isEqualTo(11);
    }

    @Test
    public void keepsTheTextByteForByteAndOffsetsAddressIt() {
        final Formatted f = DiffTextFormatter.format(SAMPLE);

        // The sample ends with '\n'; the formatted text drops only that trailing break.
        assertThat(f.getText()).isEqualTo(SAMPLE.substring(0, SAMPLE.length() - 1));
        assertThat(textOf(f, 0)).isEqualTo("diff --git a/notes.md b/notes.md");
        assertThat(textOf(f, 7)).isEqualTo("-old line");
        assertThat(textOf(f, 9)).isEqualTo("+another new line");

        for (final Line l : f.getLines()) {
            assertThat(f.getText().substring(l.getStart(), l.getEnd())).doesNotContain("\n");
        }
    }

    @Test
    public void emptyDiffGivesAnEmptyResult() {
        for (final String input : new String[]{null, ""}) {
            final Formatted f = DiffTextFormatter.format(input);
            assertThat(f.isEmpty()).isTrue();
            assertThat(f.getLines()).isEmpty();
            assertThat(f.getText()).isEmpty();
            assertThat(f.isTruncated()).isFalse();
            assertThat(f.getTotalLines()).isZero();
        }
    }

    @Test
    public void dashDashInsideAHunkIsARemovedLineNotAFileHeader() {
        // Markdown front matter: "---" removed shows up as "----", "--" removed as "---".
        final String diff = ""
                + "diff --git a/f.md b/f.md\n"
                + "--- a/f.md\n"
                + "+++ b/f.md\n"
                + "@@ -1,4 +1,4 @@\n"
                + "----\n"
                + "---\n"
                + "+++\n"
                + "++++\n";

        assertThat(kinds(DiffTextFormatter.format(diff))).containsExactly(
                FILE_HEADER, FILE_HEADER, FILE_HEADER, HUNK_HEADER,
                REMOVED, REMOVED, ADDED, ADDED);
    }

    @Test
    public void theHeaderSectionStartsAgainAtTheNextFile() {
        final String diff = ""
                + "diff --git a/a.md b/a.md\n"
                + "--- a/a.md\n"
                + "+++ b/a.md\n"
                + "@@ -1 +1 @@\n"
                + "---\n"
                + "diff --git a/b.md b/b.md\n"
                + "--- a/b.md\n"
                + "+++ b/b.md\n"
                + "@@ -1 +1 @@\n"
                + "+++\n";

        assertThat(kinds(DiffTextFormatter.format(diff))).containsExactly(
                FILE_HEADER, FILE_HEADER, FILE_HEADER, HUNK_HEADER, REMOVED,
                FILE_HEADER, FILE_HEADER, FILE_HEADER, HUNK_HEADER, ADDED);
    }

    @Test
    public void recognisesTheMetaLinesGitWrites() {
        assertThat(DiffTextFormatter.classify("index 0f1e2d3..4c5b6a7 100644", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("new file mode 100644", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("deleted file mode 100644", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("old mode 100644", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("new mode 100755", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("similarity index 95%", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("rename from old.md", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("rename to new.md", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("copy from a.md", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("copy to b.md", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("Binary files differ", true)).isEqualTo(META);
        assertThat(DiffTextFormatter.classify("\\ No newline at end of file", false)).isEqualTo(META);
    }

    @Test
    public void classifiesHunkHeadersAndContextIndependentOfTheHeaderState() {
        for (final boolean inHeader : new boolean[]{true, false}) {
            assertThat(DiffTextFormatter.classify("@@ -1,5 +1,6 @@ fn()", inHeader)).isEqualTo(HUNK_HEADER);
            assertThat(DiffTextFormatter.classify("diff --git a/x b/x", inHeader)).isEqualTo(FILE_HEADER);
            assertThat(DiffTextFormatter.classify(" unchanged", inHeader)).isEqualTo(CONTEXT);
            assertThat(DiffTextFormatter.classify("", inHeader)).isEqualTo(CONTEXT);
            assertThat(DiffTextFormatter.classify("+added", inHeader)).isEqualTo(ADDED);
            assertThat(DiffTextFormatter.classify("-removed", inHeader)).isEqualTo(REMOVED);
        }
    }

    @Test
    public void truncatesLongDiffsAndAppendsTheFooter() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("+line ").append(i).append('\n');
        }

        final Formatted f = DiffTextFormatter.format(sb.toString(), 10, "cut %1$d of %2$d");

        assertThat(f.isTruncated()).isTrue();
        assertThat(f.getShownLines()).isEqualTo(10);
        assertThat(f.getTotalLines()).isEqualTo(50);
        assertThat(f.getLines()).hasSize(11); // 10 diff lines + footer
        assertThat(f.getLines().get(10).getKind()).isEqualTo(TRUNCATION_FOOTER);
        assertThat(textOf(f, 10)).isEqualTo("cut 10 of 50");
        assertThat(textOf(f, 9)).isEqualTo("+line 9");
        assertThat(f.getText()).endsWith("+line 9\ncut 10 of 50");
        assertThat(f.getText()).doesNotContain("+line 10");
    }

    @Test
    public void aDiffExactlyAtTheLimitIsNotTruncated() {
        final Formatted f = DiffTextFormatter.format("+a\n+b\n+c\n", 3, null);

        assertThat(f.isTruncated()).isFalse();
        assertThat(f.getLines()).hasSize(3);
        assertThat(kinds(f)).containsExactly(ADDED, ADDED, ADDED);
    }

    @Test
    public void theDefaultFooterTemplateIsUsedWhenNoneIsGiven() {
        final Formatted f = DiffTextFormatter.format("+a\n+b\n", 1, null);

        assertThat(f.isTruncated()).isTrue();
        assertThat(textOf(f, 1)).isEqualTo("Diff truncated: showing 1 of 2 lines");
    }

    @Test
    public void countsLinesWithAndWithoutATrailingNewline() {
        assertThat(DiffTextFormatter.format("+a\n+b").getTotalLines()).isEqualTo(2);
        assertThat(DiffTextFormatter.format("+a\n+b\n").getTotalLines()).isEqualTo(2);
        assertThat(DiffTextFormatter.format("+a\n\n+b\n").getTotalLines()).isEqualTo(3);
        assertThat(kinds(DiffTextFormatter.format("+a\n\n+b\n"))).containsExactly(ADDED, CONTEXT, ADDED);
    }

    @Test
    public void maxLinesMustBePositive() {
        assertThatThrownBy(() -> DiffTextFormatter.format("+a\n", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
