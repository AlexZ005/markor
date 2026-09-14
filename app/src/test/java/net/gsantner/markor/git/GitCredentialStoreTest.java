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

/** The in-memory fallback and the key derivation; the Keystore path is verified on a device. */
public class GitCredentialStoreTest {

    @Test
    public void hostKeyIsLowerCaseHostOnly() {
        assertThat(GitCredentialStore.hostKey("https://GitHub.com/alex/notes.git")).isEqualTo("github.com");
        assertThat(GitCredentialStore.hostKey("https://alex@Gitlab.Example.org:8443/x/y.git")).isEqualTo("gitlab.example.org");
        assertThat(GitCredentialStore.hostKey("http://codeberg.org/x")).isEqualTo("codeberg.org");
        assertThat(GitCredentialStore.hostKey("git@github.com:alex/notes.git")).isEqualTo("github.com");
        assertThat(GitCredentialStore.hostKey("ssh://git@forgejo.local/x.git")).isEqualTo("forgejo.local");
        assertThat(GitCredentialStore.hostKey("file:///tmp/x")).isNull();
        assertThat(GitCredentialStore.hostKey("/tmp/x")).isNull();
        assertThat(GitCredentialStore.hostKey("")).isNull();
        assertThat(GitCredentialStore.hostKey(null)).isNull();
    }

    @Test
    public void inMemoryStoreRoundTripsCopiesAndForgets() {
        final GitCredentialStore store = new GitCredentialStore(new GitCredentialStore.InMemoryBackend());
        assertThat(store.isPersistent()).isFalse();
        assertThat(store.has("https://github.com/a/b.git")).isFalse();
        assertThat(store.getUsername("https://github.com/a/b.git")).isNull();

        final char[] token = "ghp_secret".toCharArray();
        assertThat(store.save("https://GitHub.com/a/b.git", " alice ", token)).isTrue();
        java.util.Arrays.fill(token, 'x'); // caller wipes its copy; the store keeps its own
        assertThat(store.has("https://github.com/other/repo.git")).isTrue();
        assertThat(store.getUsername("https://github.com/other/repo.git")).isEqualTo("alice");

        final GitCredentialsSource source = store.asSource();
        assertThat(source.getUsername("GITHUB.COM")).isEqualTo("alice");
        final char[] first = source.getSecret("github.com");
        assertThat(new String(first)).isEqualTo("ghp_secret");
        java.util.Arrays.fill(first, '\0');
        assertThat(new String(source.getSecret("github.com"))).isEqualTo("ghp_secret");
        assertThat(source.getSecret("gitlab.com")).isNull();
        assertThat(source.getUsername("gitlab.com")).isNull();
        assertThat(source.getSecret(null)).isNull();

        // replacing
        assertThat(store.save("https://github.com/x", "bob", "new".toCharArray())).isTrue();
        assertThat(store.getUsername("https://github.com/x")).isEqualTo("bob");
        assertThat(new String(source.getSecret("github.com"))).isEqualTo("new");

        store.forget("https://github.com/whatever");
        assertThat(store.has("https://github.com/a/b.git")).isFalse();
        assertThat(store.getUsername("https://github.com/a/b.git")).isNull();
        assertThat(source.getSecret("github.com")).isNull();
        store.forget("file:///no/host"); // no-op
    }

    @Test
    public void saveRejectsBlankInputAndHostlessUrls() {
        final GitCredentialStore store = new GitCredentialStore(new GitCredentialStore.InMemoryBackend());
        assertThat(store.save("file:///tmp/x", "alice", "t".toCharArray())).isFalse();
        assertThat(store.save("https://github.com/x", "", "t".toCharArray())).isFalse();
        assertThat(store.save("https://github.com/x", null, "t".toCharArray())).isFalse();
        assertThat(store.save("https://github.com/x", "alice", new char[0])).isFalse();
        assertThat(store.save("https://github.com/x", "alice", null)).isFalse();
        assertThat(store.has("https://github.com/x")).isFalse();
    }

    @Test
    public void withoutAContextItFallsBackToMemoryWithoutTouchingAndroid() {
        // No context means no Keystore, which is also what an odd ROM that refuses it ends up with.
        // (Before this fork raised minSdk to 26 the same branch covered devices below API 23.)
        final GitCredentialStore store = GitCredentialStore.get(null);
        assertThat(store.isPersistent()).isFalse();
        assertThat(GitCredentialStore.get(null)).isSameAs(store);
        assertThat(store.save("https://example.com/r.git", "u", "p".toCharArray())).isTrue();
        assertThat(GitCredentialStore.get(null).getUsername("https://example.com/other.git")).isEqualTo("u");
        store.forget("https://example.com/r.git");
    }
}
