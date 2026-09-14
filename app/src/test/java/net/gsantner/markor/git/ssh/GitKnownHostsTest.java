/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Reading and forgetting entries in the app's {@code known_hosts} (roadmap task 8.1c). The fixtures
 * are real lines: the GitHub ones are the keys GitHub publishes, so the fingerprints below can be
 * checked against <a href="https://docs.github.com/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints">their
 * published list</a> by eye — which is exactly what the confirmation dialog asks a user to do.
 */
public class GitKnownHostsTest {

    @Rule
    public TemporaryFolder _tmp = new TemporaryFolder();

    private static final String GITHUB_RSA = "github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk= github";
    private static final String GITHUB_RSA_FINGERPRINT = "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s";

    private File write(final String... lines) throws Exception {
        final File file = new File(_tmp.newFolder(), "known_hosts");
        final StringBuilder sb = new StringBuilder();
        for (final String line : lines) {
            sb.append(line).append('\n');
        }
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void readsHostTypeAndFingerprint() throws Exception {
        final List<GitKnownHosts.Entry> entries = GitKnownHosts.list(write(GITHUB_RSA));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getHost()).isEqualTo("github.com");
        assertThat(entries.get(0).getKeyType()).isEqualTo("ssh-rsa");
        assertThat(entries.get(0).getFingerprintSha256()).isEqualTo(GITHUB_RSA_FINGERPRINT);
        assertThat(entries.get(0).describe()).contains("github.com").contains(GITHUB_RSA_FINGERPRINT);
    }

    @Test
    public void skipsWhatItCannotShow() throws Exception {
        final List<GitKnownHosts.Entry> entries = GitKnownHosts.list(write(
                "# a comment",
                "",
                "@cert-authority *.example.org ssh-rsa AAAAB3NzaC1yc2E=",
                "|1|hashed+base64=|alsohashed= ssh-rsa AAAAB3NzaC1yc2E=",
                "broken.example.org ssh-rsa not-base64!!",
                "wrong.example.org ssh-rsa AAAAB3NzaC1kc3M=",
                GITHUB_RSA));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getHost()).isEqualTo("github.com");
    }

    @Test
    public void aMissingFileIsAnEmptyList() {
        assertThat(GitKnownHosts.list(new File(_tmp.getRoot(), "nope"))).isEmpty();
        assertThat(GitKnownHosts.list(null)).isEmpty();
    }

    @Test
    public void forgettingRemovesEveryKeyOfThatHostAndKeepsTheRest() throws Exception {
        final File file = write(GITHUB_RSA, "# kept", GITHUB_RSA.replace("github.com ", "codeberg.org "));

        assertThat(GitKnownHosts.forget(file, "github.com")).isTrue();

        final List<GitKnownHosts.Entry> left = GitKnownHosts.list(file);
        assertThat(left).hasSize(1);
        assertThat(left.get(0).getHost()).isEqualTo("codeberg.org");
        assertThat(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).contains("# kept");
    }

    @Test
    public void forgettingSomethingThatIsNotThereChangesNothing() throws Exception {
        final File file = write(GITHUB_RSA);
        assertThat(GitKnownHosts.forget(file, "elsewhere.example")).isFalse();
        assertThat(GitKnownHosts.forget(file, null)).isFalse();
        assertThat(GitKnownHosts.list(file)).hasSize(1);
    }

    @Test
    public void ensureCreatesTheFileAndItsFolder() {
        final File file = GitKnownHosts.fileIn(_tmp.getRoot());
        assertThat(file.getPath()).endsWith("git/known_hosts");
        assertThat(GitKnownHosts.ensure(file)).isNotNull();
        assertThat(file).exists();
        // Idempotent: a second call on an existing file must not truncate it.
        assertThat(GitKnownHosts.ensure(file)).isNotNull();
    }
}
