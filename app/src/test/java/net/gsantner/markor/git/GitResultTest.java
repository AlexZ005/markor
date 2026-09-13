/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;

public class GitResultTest {

    @Test
    public void okCarriesValue() {
        final GitResult<String> r = GitResult.ok("x");
        assertThat(r.isOk()).isTrue();
        assertThat(r.getKind()).isEqualTo(GitResult.Kind.OK);
        assertThat(r.getValue()).isEqualTo("x");
        assertThat(r.getMessage()).isNull();
        assertThat(r.getFiles()).isEmpty();
        assertThatThrownBy(r::asError).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void errorsCarryMessageAndFiles() {
        final GitResult<Void> c = GitResult.conflicts(Arrays.asList("a.md", "b/c.txt"));
        assertThat(c.getKind()).isEqualTo(GitResult.Kind.CONFLICTS);
        assertThat(c.getFiles()).containsExactly("a.md", "b/c.txt");
        assertThat(c.getMessage()).contains("2 files");
        assertThat(c.getValueOrNull()).isNull();
        assertThatThrownBy(c::getValue).isInstanceOf(IllegalStateException.class);

        final GitResult<String> retyped = c.asError();
        assertThat(retyped.getKind()).isEqualTo(GitResult.Kind.CONFLICTS);
        assertThat(retyped.getFiles()).containsExactly("a.md", "b/c.txt");

        assertThat(GitResult.notARepo(new File("/x/y")).getMessage()).contains("/x/y");
        assertThat(GitResult.failed(null).getMessage()).isNotEmpty();
        assertThat(GitResult.cancelled().getKind()).isEqualTo(GitResult.Kind.CANCELLED);
        assertThat(GitResult.dirtyWorkTree(Arrays.asList("todo.txt")).getKind()).isEqualTo(GitResult.Kind.DIRTY_WORK_TREE);
    }

    @Test
    public void filesListIsDefensiveCopy() {
        final java.util.List<String> files = new java.util.ArrayList<>(Arrays.asList("a"));
        final GitResult<Void> c = GitResult.conflicts(files);
        files.add("b");
        assertThat(c.getFiles()).containsExactly("a");
        assertThatThrownBy(() -> c.getFiles().add("z")).isInstanceOf(UnsupportedOperationException.class);
    }
}
