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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/**
 * Security review (roadmap task 7.5): paths that come out of a repository must not be able to name a
 * file outside its working folder. Nothing in git's object format forbids a tree entry called
 * {@code ..}, and repositories live in the notebook folder on shared storage, so the path in a
 * status entry or a diff is not trusted input.
 */
public class GitPathsTest {

    @Rule
    public final TemporaryFolder _tmp = new TemporaryFolder();

    @Test
    public void resolvesOrdinaryRepositoryPaths() throws IOException {
        final File root = _tmp.newFolder("repo");
        assertThat(GitPaths.resolveInside(root, "notes.md")).isEqualTo(new File(root, "notes.md"));
        assertThat(GitPaths.resolveInside(root, "journal/2026-09-13.md"))
                .isEqualTo(new File(root, "journal/2026-09-13.md"));
        // A file name may contain dots without being a traversal.
        assertThat(GitPaths.resolveInside(root, "a..b.md")).isEqualTo(new File(root, "a..b.md"));
        assertThat(GitPaths.resolveInside(root, "dir/../notes.md")).isEqualTo(new File(root, "dir/../notes.md"));
    }

    @Test
    public void refusesPathsThatLeaveTheWorkingFolder() throws IOException {
        final File root = _tmp.newFolder("repo");
        assertThat(GitPaths.resolveInside(root, "../outside.md")).isNull();
        assertThat(GitPaths.resolveInside(root, "../../../../data/data/net.gsantner.markor/shared_prefs/x.xml")).isNull();
        assertThat(GitPaths.resolveInside(root, "sub/../../outside.md")).isNull();
        assertThat(GitPaths.resolveInside(root, "..")).isNull();
    }

    @Test
    public void refusesAbsoluteEmptyAndNullPaths() throws IOException {
        final File root = _tmp.newFolder("repo");
        assertThat(GitPaths.resolveInside(root, "/etc/hosts")).isNull();
        assertThat(GitPaths.resolveInside(root, "")).isNull();
        assertThat(GitPaths.resolveInside(root, null)).isNull();
        assertThat(GitPaths.resolveInside(null, "notes.md")).isNull();
        // The working folder itself is never a file the repository names.
        assertThat(GitPaths.resolveInside(root, ".")).isNull();
    }

    /**
     * A leading or trailing space is legal in a git path name. Trimming it would hand the caller a
     * different file than the repository named — and the callers stage, delete and overwrite what
     * they get back, so "draft .md" must not resolve to "draft.md".
     */
    @Test
    public void usesThePathVerbatimRatherThanTrimmingIt() throws IOException {
        final File root = _tmp.newFolder("repo");
        assertThat(GitPaths.resolveInside(root, "draft .md")).isEqualTo(new File(root, "draft .md"));
        assertThat(GitPaths.resolveInside(root, " leading.md")).isEqualTo(new File(root, " leading.md"));
        assertThat(GitPaths.resolveInside(root, "trailing.md ")).isEqualTo(new File(root, "trailing.md "));
        assertThat(GitPaths.resolveInside(root, "draft .md")).isNotEqualTo(new File(root, "draft.md"));
    }

    /**
     * The conflict-marker scan is the one place a repository-supplied path is opened for reading
     * without the UI in between, so it is checked end to end.
     */
    @Test
    public void theConflictScanNeverReadsOutsideTheWorkingFolder() throws IOException {
        final File root = _tmp.newFolder("repo");
        final File outside = _tmp.newFile("outside.md");
        Files.write(outside.toPath(), "<<<<<<< HEAD\nsecret\n".getBytes(StandardCharsets.UTF_8));

        final File inside = new File(root, "conflicted.md");
        Files.write(inside.toPath(), "<<<<<<< HEAD\nmine\n".getBytes(StandardCharsets.UTF_8));

        assertThat(GitConflictMarkers.scan(root, Collections.singletonList("../outside.md"))).isEmpty();
        assertThat(GitConflictMarkers.scan(root, Arrays.asList("../outside.md", "conflicted.md")))
                .containsExactly("conflicted.md");
    }
}
