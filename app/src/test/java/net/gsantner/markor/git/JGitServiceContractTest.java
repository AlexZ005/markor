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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Repository discovery and description: the part of the contract the facade implements itself. */
public class JGitServiceContractTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();

    @Test
    public void openFindsRepositoryFromNestedPathAndDescribesIt() throws Exception {
        final File root = tmp.newFolder("notes");
        try (Git git = Git.init().setDirectory(root).setInitialBranch("main").call()) {
            final File sub = new File(root, "journal");
            assertThat(sub.mkdirs()).isTrue();
            final File file = new File(sub, "2026-09-13.md");
            Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));

            assertThat(_git.isRepository(root)).isTrue();
            assertThat(_git.isRepository(sub)).isTrue();
            assertThat(_git.isRepository(file)).isTrue();
            assertThat(_git.findRepositoryRoot(file).getCanonicalFile()).isEqualTo(root.getCanonicalFile());

            final GitResult<GitRepoInfo> r = _git.open(file, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            final GitRepoInfo info = r.getValue();
            assertThat(info.getWorkTree().getCanonicalFile()).isEqualTo(root.getCanonicalFile());
            assertThat(info.getName()).isEqualTo("notes");
            assertThat(info.getBranch()).isEqualTo("main");
            assertThat(info.isEmpty()).isTrue();
            assertThat(info.getHeadSha()).isNull();
            assertThat(info.isDetached()).isFalse();
            assertThat(info.getState()).isEqualTo(GitRepoState.NORMAL);
            assertThat(info.hasRemote()).isFalse();
            assertThat(info.getUpstream()).isNull();
            assertThat(info.getLastFetchEpochMillis()).isZero();

            git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish("https://alex:secret-token@example.com/notes.git")).call();
            final GitRepoInfo withRemote = _git.open(root, GitProgress.NONE).getValue();
            assertThat(withRemote.getRemoteName()).isEqualTo("origin");
            assertThat(withRemote.getRemoteUrl()).isEqualTo("https://example.com/notes.git");
            assertThat(withRemote.toString()).doesNotContain("secret-token").doesNotContain("alex");
        }
    }

    @Test
    public void openOutsideRepositoryIsNotARepo() throws Exception {
        final File plain = tmp.newFolder("plain");
        assertThat(_git.isRepository(plain)).isFalse();
        assertThat(_git.findRepositoryRoot(plain)).isNull();
        final GitResult<GitRepoInfo> r = _git.open(plain, GitProgress.NONE);
        assertThat(r.getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(r.getMessage()).contains("plain");
        assertThat(_git.isRepository(null)).isFalse();
        assertThat(_git.open(new File(plain, "missing/file.md"), GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    @Test
    public void bareRepositoryIsNotARepo() throws Exception {
        final File bare = tmp.newFolder("bare.git");
        try (Git git = Git.init().setDirectory(bare).setBare(true).call()) {
            assertThat(_git.isRepository(bare)).isFalse();
            assertThat(_git.open(bare, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        }
    }

    @Test
    public void stubsReportNotImplementedInsteadOfThrowing() throws Exception {
        final File root = tmp.newFolder("r");
        try (Git git = Git.init().setDirectory(root).call()) {
            assertThat(_git.status(root, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(_git.push(root, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        }
    }

    @Test
    public void urlSanitizing() {
        assertThat(JGitRepos.sanitizeUrl("https://user:pw@host.tld/a/b.git")).isEqualTo("https://host.tld/a/b.git");
        assertThat(JGitRepos.sanitizeUrl("https://user@host.tld/a/b.git")).isEqualTo("https://host.tld/a/b.git");
        assertThat(JGitRepos.sanitizeUrl("https://host.tld/a/b.git")).isEqualTo("https://host.tld/a/b.git");
        assertThat(JGitRepos.sanitizeUrl("file:///tmp/x")).isEqualTo("file:///tmp/x");
        assertThat(JGitRepos.hasPassword("https://user:pw@host.tld/a.git")).isTrue();
        assertThat(JGitRepos.hasPassword("https://user@host.tld/a.git")).isFalse();
        assertThat(JGitErrors.sanitize("fetch https://u:p@h/x.git: not authorized")).isEqualTo("fetch https://h/x.git: not authorized");
    }
}
