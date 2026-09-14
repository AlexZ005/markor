/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * JGit's automatic GC must stay off: on Android it reaches java.lang.management and crashes
 * (see doc/adr/0001-jgit-on-android.md). Every path that hands out a repository disables it.
 */
public class JGitReposAutoGcTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void openingARepositoryDisablesAutoGcInItsConfig() throws Exception {
        final File dir = tmp.newFolder("repo");
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            assertThat(git.getRepository().getConfig().getInt("gc", "auto", -1)).isEqualTo(-1);
        }
        try (Repository repo = JGitRepos.open(dir)) {
            assertThat(repo.getConfig().getInt("gc", "auto", -1)).isEqualTo(0);
            assertThat(repo.getConfig().getInt("gc", "autopacklimit", -1)).isEqualTo(0);
        }
        final String config = new String(Files.readAllBytes(new File(dir, ".git/config").toPath()), StandardCharsets.UTF_8);
        assertThat(config).contains("[gc]").contains("auto = 0").contains("autopacklimit = 0");
    }

    @Test
    public void initAndOpenWriteTheConfigOnlyOnce() throws Exception {
        final File dir = tmp.newFolder("repo2");
        final JGitLocalOps ops = new JGitLocalOps();
        assertThat(ops.init(dir, GitProgress.NONE).isOk()).isTrue();
        final File configFile = new File(dir, ".git/config");
        final String first = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        assertThat(first).contains("auto = 0");
        try (Repository repo = JGitRepos.open(dir)) {
            assertThat(repo.getConfig().getInt("gc", "auto", -1)).isEqualTo(0);
        }
        assertThat(new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8)).isEqualTo(first);
    }
}
