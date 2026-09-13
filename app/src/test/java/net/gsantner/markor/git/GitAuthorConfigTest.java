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

public class GitAuthorConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final GitAuthor ALICE = new GitAuthor("Alice", "alice@example.com");
    private static final GitAuthor BOB = new GitAuthor("Bob", "bob@example.com");

    @Test
    public void writesTheIdentityIntoTheRepositoryConfiguration() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("repo"))) {
            assertThat(GitAuthorConfig.read(repo.root())).isNull();

            assertThat(GitAuthorConfig.write(repo.root(), ALICE)).isTrue();

            assertThat(GitAuthorConfig.read(repo.root())).isEqualTo(ALICE);
            assertThat(repo.git().getRepository().getConfig().getString("user", null, "email"))
                    .isEqualTo("alice@example.com");
        }
    }

    @Test
    public void doesNotOverwriteAnIdentityTheRepositoryAlreadyHas() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("repo"))) {
            GitAuthorConfig.write(repo.root(), ALICE);

            assertThat(GitAuthorConfig.write(repo.root(), BOB)).isFalse();
            assertThat(GitAuthorConfig.read(repo.root())).isEqualTo(ALICE);

            assertThat(GitAuthorConfig.write(repo.root(), BOB, true)).isTrue();
            assertThat(GitAuthorConfig.read(repo.root())).isEqualTo(BOB);
        }
    }

    @Test
    public void aHalfConfiguredIdentityCountsAsNoneAndIsCompleted() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("repo"))) {
            repo.git().getRepository().getConfig().setString("user", null, "name", "Only A Name");
            repo.git().getRepository().getConfig().save();

            assertThat(GitAuthorConfig.read(repo.root())).isNull();
            assertThat(GitAuthorConfig.write(repo.root(), ALICE)).isTrue();
            assertThat(GitAuthorConfig.read(repo.root())).isEqualTo(ALICE);
        }
    }

    @Test
    public void worksFromAnyPathInsideTheWorkingTree() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("repo"))) {
            final File nested = new File(repo.root(), "notes/sub");
            assertThat(nested.mkdirs()).isTrue();

            assertThat(GitAuthorConfig.write(nested, ALICE)).isTrue();
            assertThat(GitAuthorConfig.read(nested)).isEqualTo(ALICE);
        }
    }

    @Test
    public void aFolderThatIsNoRepositoryIsReportedInsteadOfThrowing() throws Exception {
        final File plain = tmp.newFolder("plain");

        assertThat(GitAuthorConfig.read(plain)).isNull();
        assertThat(GitAuthorConfig.write(plain, ALICE)).isFalse();
        assertThat(GitAuthorConfig.write(null, ALICE)).isFalse();
        assertThat(GitAuthorConfig.write(plain, null)).isFalse();
    }
}
