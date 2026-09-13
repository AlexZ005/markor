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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GitCommitSelectionTest {

    private static GitStatusEntry entry(final String path, final GitStatusEntry.Kind kind) {
        return new GitStatusEntry(path, kind);
    }

    private static List<GitStatusEntry> sample() {
        return Arrays.asList(
                entry("a.md", GitStatusEntry.Kind.MODIFIED),
                entry("b.md", GitStatusEntry.Kind.ADDED),
                entry("c.md", GitStatusEntry.Kind.DELETED),
                entry("d.png", GitStatusEntry.Kind.UNTRACKED));
    }

    @Test
    public void everythingIsCheckedByDefault() {
        final GitCommitSelection selection = new GitCommitSelection(sample());

        assertThat(selection.size()).isEqualTo(4);
        assertThat(selection.getSelectedCount()).isEqualTo(4);
        assertThat(selection.isAllSelected()).isTrue();
        assertThat(selection.hasConflicts()).isFalse();
        assertThat(selection.getSelectedPaths()).containsExactly("a.md", "b.md", "c.md", "d.png");
    }

    @Test
    public void conflictsAreShownButCanNeverBeChecked() {
        final GitCommitSelection selection = new GitCommitSelection(Arrays.asList(
                entry("a.md", GitStatusEntry.Kind.MODIFIED),
                entry("conflict.md", GitStatusEntry.Kind.CONFLICT)));

        assertThat(selection.size()).isEqualTo(2);
        assertThat(selection.isSelectable(1)).isFalse();
        assertThat(selection.isChecked(1)).isFalse();
        assertThat(selection.getConflictCount()).isEqualTo(1);
        assertThat(selection.hasConflicts()).isTrue();

        assertThat(selection.setChecked(1, true)).isFalse();
        assertThat(selection.toggle(1)).isFalse();
        assertThat(selection.isChecked(1)).isFalse();
        assertThat(selection.getSelectedPaths()).containsExactly("a.md");

        // "all selected" looks only at the rows that can be selected
        assertThat(selection.isAllSelected()).isTrue();
    }

    @Test
    public void togglingAndSelectAllChangeTheSelectedPaths() {
        final GitCommitSelection selection = new GitCommitSelection(sample());

        assertThat(selection.toggle(0)).isFalse();
        assertThat(selection.getSelectedCount()).isEqualTo(3);
        assertThat(selection.getSelectedPaths()).containsExactly("b.md", "c.md", "d.png");
        assertThat(selection.isAllSelected()).isFalse();

        selection.setAllChecked(false);
        assertThat(selection.getSelectedCount()).isZero();
        assertThat(selection.getSelectedPaths()).isEmpty();

        selection.setAllChecked(true);
        assertThat(selection.getSelectedCount()).isEqualTo(4);
    }

    @Test
    public void setCheckedReportsWhetherItChangedAnything() {
        final GitCommitSelection selection = new GitCommitSelection(sample());

        assertThat(selection.setChecked(0, true)).isFalse();
        assertThat(selection.setChecked(0, false)).isTrue();
        assertThat(selection.setChecked(0, false)).isFalse();
    }

    @Test
    public void restoreSelectionChecksExactlyTheGivenPathsAndIgnoresUnknownOnes() {
        final GitCommitSelection selection = new GitCommitSelection(Arrays.asList(
                entry("a.md", GitStatusEntry.Kind.MODIFIED),
                entry("b.md", GitStatusEntry.Kind.ADDED),
                entry("conflict.md", GitStatusEntry.Kind.CONFLICT)));

        selection.restoreSelection(Arrays.asList("b.md", "conflict.md", "gone.md"));

        assertThat(selection.getSelectedPaths()).containsExactly("b.md");
        assertThat(selection.isChecked(2)).isFalse();

        selection.restoreSelection(null);
        assertThat(selection.getSelectedPaths()).isEmpty();
    }

    @Test
    public void anEmptyOrNullStatusIsAnEmptySelection() {
        for (final GitCommitSelection selection : Arrays.asList(
                new GitCommitSelection(null), new GitCommitSelection(Collections.<GitStatusEntry>emptyList()))) {
            assertThat(selection.isEmpty()).isTrue();
            assertThat(selection.getSelectedCount()).isZero();
            assertThat(selection.isAllSelected()).isFalse();
            assertThat(selection.getSelectedPaths()).isEmpty();
            assertThat(GitCommitValidator.validate("Message", selection.getSelectedCount()).isValid()).isFalse();
        }
    }

    @Test
    public void entriesKeepTheOrderStatusProduced() {
        final GitCommitSelection selection = new GitCommitSelection(sample());

        assertThat(selection.getEntries()).extracting(GitStatusEntry::getPath)
                .containsExactly("a.md", "b.md", "c.md", "d.png");
        assertThat(selection.getEntry(2).getKind()).isEqualTo(GitStatusEntry.Kind.DELETED);
    }
}
