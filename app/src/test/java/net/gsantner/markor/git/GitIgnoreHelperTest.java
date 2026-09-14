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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class GitIgnoreHelperTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File _repo;

    @Before
    public void setUp() throws IOException {
        _repo = tmp.newFolder("repo");
    }

    private void writeGitIgnore(final String content) throws IOException {
        try (OutputStream out = new FileOutputStream(GitIgnoreHelper.getGitIgnoreFile(_repo))) {
            out.write(content.getBytes(UTF8));
        }
    }

    private String readGitIgnore() throws IOException {
        final File file = GitIgnoreHelper.getGitIgnoreFile(_repo);
        final byte[] buffer = new byte[(int) file.length()];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < buffer.length) {
                final int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return new String(buffer, UTF8);
    }

    // ---------------------------------------------------------------- entryFor

    @Test
    public void entryForTheNotebookAtTheRepositoryRootIsTheDefaultEntry() {
        assertThat(GitIgnoreHelper.entryFor(_repo, new File(_repo, ".app")))
                .isEqualTo(GitIgnoreHelper.DEFAULT_ENTRY);
    }

    @Test
    public void entryForANotebookInASubfolderIsRepositoryRelative() {
        assertThat(GitIgnoreHelper.entryFor(_repo, new File(_repo, "notes/.app"))).isEqualTo("notes/.app/");
    }

    @Test
    public void entryForAFolderOutsideTheRepositoryIsNull() throws IOException {
        assertThat(GitIgnoreHelper.entryFor(_repo, tmp.newFolder("elsewhere"))).isNull();
        assertThat(GitIgnoreHelper.entryFor(_repo, _repo)).isNull();
        assertThat(GitIgnoreHelper.entryFor(_repo, null)).isNull();
        assertThat(GitIgnoreHelper.entryFor(null, new File(_repo, ".app"))).isNull();
    }

    // ---------------------------------------------------------------- isIgnored

    @Test
    public void noGitIgnoreFileMeansNotIgnored() {
        assertThat(GitIgnoreHelper.getGitIgnoreFile(_repo)).doesNotExist();
        assertThat(GitIgnoreHelper.isIgnored(_repo, ".app/")).isFalse();
        assertThat(GitIgnoreHelper.shouldSuggest(_repo, new File(_repo, ".app"))).isTrue();
    }

    @Test
    public void detectsAnExistingEntryInEverySpellingGitTreatsAlike() throws IOException {
        for (final String spelling : new String[]{".app/", ".app", "/.app/", "/.app", "  .app/  "}) {
            writeGitIgnore("# notes\n*.tmp\n" + spelling + "\n");
            assertThat(GitIgnoreHelper.isIgnored(_repo, ".app/"))
                    .as("spelling <%s>", spelling).isTrue();
            assertThat(GitIgnoreHelper.shouldSuggest(_repo, new File(_repo, ".app"))).isFalse();
        }
    }

    @Test
    public void doesNotMistakeACommentOrAnUnrelatedEntryForTheEntry() throws IOException {
        writeGitIgnore("# .app/\n.apple/\napp/\nnotes/.app/\n");

        assertThat(GitIgnoreHelper.isIgnored(_repo, ".app/")).isFalse();
        assertThat(GitIgnoreHelper.isIgnored(_repo, "notes/.app/")).isTrue();
    }

    // ---------------------------------------------------------------- append

    @Test
    public void appendCreatesTheFileWhenItIsMissing() throws IOException {
        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isTrue();

        assertThat(readGitIgnore()).isEqualTo(GitIgnoreHelper.COMMENT + "\n.app/\n");
        assertThat(GitIgnoreHelper.isIgnored(_repo, ".app/")).isTrue();
    }

    @Test
    public void appendDoesNotDuplicateAnEntryThatIsAlreadyThere() throws IOException {
        writeGitIgnore("*.tmp\n.app\n");

        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isFalse();
        assertThat(readGitIgnore()).isEqualTo("*.tmp\n.app\n");
    }

    @Test
    public void appendTwiceWritesTheEntryOnlyOnce() throws IOException {
        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isTrue();
        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isFalse();

        assertThat(readGitIgnore()).isEqualTo(GitIgnoreHelper.COMMENT + "\n.app/\n");
    }

    @Test
    public void appendRespectsAnExistingTrailingNewline() throws IOException {
        writeGitIgnore("*.tmp\nbuild/\n");

        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isTrue();

        assertThat(readGitIgnore())
                .isEqualTo("*.tmp\nbuild/\n\n" + GitIgnoreHelper.COMMENT + "\n.app/\n");
    }

    @Test
    public void appendAddsTheMissingNewlineSoTheLastLineSurvives() throws IOException {
        writeGitIgnore("*.tmp\nbuild/");

        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isTrue();

        assertThat(readGitIgnore())
                .isEqualTo("*.tmp\nbuild/\n\n" + GitIgnoreHelper.COMMENT + "\n.app/\n");
        assertThat(GitIgnoreHelper.isIgnored(_repo, "build/")).isTrue();
    }

    @Test
    public void appendToAnEmptyFileDoesNotStartWithABlankLine() throws IOException {
        writeGitIgnore("");

        assertThat(GitIgnoreHelper.append(_repo, ".app/")).isTrue();

        assertThat(readGitIgnore()).isEqualTo(GitIgnoreHelper.COMMENT + "\n.app/\n");
    }

    @Test
    public void appendWritesASubfolderEntryVerbatim() throws IOException {
        assertThat(GitIgnoreHelper.append(_repo, "notes/.app/")).isTrue();

        assertThat(readGitIgnore()).isEqualTo(GitIgnoreHelper.COMMENT + "\nnotes/.app/\n");
        assertThat(GitIgnoreHelper.isIgnored(_repo, "notes/.app/")).isTrue();
        assertThat(GitIgnoreHelper.isIgnored(_repo, ".app/")).isFalse();
    }

    @Test
    public void appendWithoutARepositoryOrAnEntryFails() {
        assertThatThrownBy(() -> GitIgnoreHelper.append(null, ".app/")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GitIgnoreHelper.append(_repo, null)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> GitIgnoreHelper.append(_repo, "  /  ")).isInstanceOf(IOException.class);
    }
}
