/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GitConflictMarkersTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void markerLinesAreExactlySevenAnglesAtLineStart() {
        assertThat(GitConflictMarkers.isMarkerLine("<<<<<<< HEAD")).isTrue();
        assertThat(GitConflictMarkers.isMarkerLine(">>>>>>> refs/remotes/origin/main")).isTrue();
        assertThat(GitConflictMarkers.isMarkerLine("<<<<<<<")).isTrue();
        assertThat(GitConflictMarkers.isMarkerLine(">>>>>>>")).isTrue();
        assertThat(GitConflictMarkers.isMarkerLine("<<<<<<<\tours")).isTrue();

        assertThat(GitConflictMarkers.isMarkerLine("=======")).as("setext underline is not a marker").isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("======= ")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("<<<<<<<< eight")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("<<<<<<")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine(" <<<<<<< indented")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("text <<<<<<< inside")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("<<<<<<<x")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("<<<>>>>")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine("")).isFalse();
        assertThat(GitConflictMarkers.isMarkerLine(null)).isFalse();
    }

    @Test
    public void containsMarkersLooksAtEveryLineIncludingCrLf() {
        assertThat(GitConflictMarkers.containsMarkers("a\n<<<<<<< HEAD\nb\n=======\nc\n>>>>>>> x\n")).isTrue();
        assertThat(GitConflictMarkers.containsMarkers("a\r\n>>>>>>> x\r\n")).isTrue();
        assertThat(GitConflictMarkers.containsMarkers(">>>>>>>")).isTrue();
        assertThat(GitConflictMarkers.containsMarkers("Heading\n=======\n\ntext\n")).isFalse();
        assertThat(GitConflictMarkers.containsMarkers("")).isFalse();
        assertThat(GitConflictMarkers.containsMarkers(null)).isFalse();
    }

    @Test
    public void scanNamesOnlyFilesThatStillHaveMarkersAndSkipsMissingOnes() throws Exception {
        final File root = tmp.newFolder("repo");
        GitTestRepos.write(root, "todo.txt", "- buy\n<<<<<<< HEAD\n- oat\n=======\n- soy\n>>>>>>> origin/main\n");
        GitTestRepos.write(root, "notes.md", "Notes\n=======\n\nresolved text\n");
        GitTestRepos.write(root, "sub/deep.md", "x\n>>>>>>> theirs\n");

        final List<String> marked = GitConflictMarkers.scan(root, Arrays.asList("todo.txt", "notes.md", "sub/deep.md", "gone.md"));
        assertThat(marked).containsExactly("todo.txt", "sub/deep.md");

        assertThat(GitConflictMarkers.scan(root, Collections.<String>emptyList())).isEmpty();
        assertThat(GitConflictMarkers.scan(root, null)).isEmpty();
        assertThat(GitConflictMarkers.scan(null, Collections.singletonList("todo.txt"))).isEmpty();
    }
}
