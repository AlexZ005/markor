/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.GitRepoConfig;
import net.gsantner.markor.git.GitRepoRegistryCodec;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The contract task 8.1c depends on: the public-key spellings, the index format,
 * {@link GitSshKeySelection} and {@link GitRepoConfig#getSshKeyId()} round tripping.
 * <p>
 * The vector is a real {@code ssh-keygen -t ed25519} public key; the expected fingerprint is what
 * {@code ssh-keygen -lf} prints for it.
 */
public class GitSshKeyContractTest {

    private static final String ED25519_LINE =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEL6D2PT5RVv6kvvRNVo8pTRtHNIrhmqusOyeurwE9wk vector@markor";
    private static final String ED25519_FINGERPRINT = "SHA256:HThMk1DKYEdhpWPmI/O7G4lUscNxehHTbxoGT73231M";

    @Test
    public void fingerprintMatchesWhatSshKeygenPrints() {
        final byte[] blob = GitSshPublicKeys.blobOf(ED25519_LINE);
        assertThat(blob).isNotNull();
        assertThat(GitSshPublicKeys.algorithmOf(blob)).isEqualTo("ssh-ed25519");
        assertThat(GitSshPublicKeys.fingerprintSha256(blob)).isEqualTo(ED25519_FINGERPRINT);
        assertThat(GitSshPublicKeys.lineFor(blob, "vector@markor")).isEqualTo(ED25519_LINE);
    }

    @Test
    public void indexRoundTripsKeysAndTheDefault() {
        final GitSshKey rsa = new GitSshKey("kaaa", "Phone", GitSshKey.Type.RSA, 4096,
                ED25519_LINE, ED25519_FINGERPRINT, 1700000000000L, false);
        final GitSshKey ed = new GitSshKey("kbbb", "Imported", GitSshKey.Type.ED25519, 256,
                ED25519_LINE, ED25519_FINGERPRINT, 1700000001000L, true);

        final GitSshKeyIndexCodec.Index read =
                GitSshKeyIndexCodec.fromJson(GitSshKeyIndexCodec.toJson(Arrays.asList(rsa, ed), "kbbb"));

        assertThat(read.getKeys()).hasSize(2);
        assertThat(read.getDefaultKeyId()).isEqualTo("kbbb");
        final GitSshKey first = read.getKeys().get(0);
        assertThat(first.getId()).isEqualTo("kaaa");
        assertThat(first.getName()).isEqualTo("Phone");
        assertThat(first.getType()).isEqualTo(GitSshKey.Type.RSA);
        assertThat(first.getBits()).isEqualTo(4096);
        assertThat(first.getFingerprintSha256()).isEqualTo(ED25519_FINGERPRINT);
        assertThat(first.hasPassphrase()).isFalse();
        assertThat(first.canAuthenticate()).isTrue();
        final GitSshKey second = read.getKeys().get(1);
        assertThat(second.hasPassphrase()).isTrue();
        assertThat(second.canAuthenticate()).isFalse();
        assertThat(second.describe()).isEqualTo("ED25519 256 · " + ED25519_FINGERPRINT);
    }

    @Test
    public void aDefaultNamingNoStoredKeyIsDropped() {
        final GitSshKey key = new GitSshKey("kaaa", "Phone", GitSshKey.Type.RSA, 4096,
                ED25519_LINE, ED25519_FINGERPRINT, 1L, false);
        final GitSshKeyIndexCodec.Index read = GitSshKeyIndexCodec.fromJson(
                GitSshKeyIndexCodec.toJson(Collections.singletonList(key), "gone"));
        assertThat(read.getKeys()).hasSize(1);
        assertThat(read.getDefaultKeyId()).isNull();
    }

    @Test
    public void garbageIndexReadsAsEmptyInsteadOfThrowing() {
        for (final String json : new String[]{null, "", "   ", "not json", "[]", "{\"keys\":7}"}) {
            final GitSshKeyIndexCodec.Index read = GitSshKeyIndexCodec.fromJson(json);
            assertThat(read.getKeys()).isEmpty();
            assertThat(read.getDefaultKeyId()).isNull();
        }
    }

    @Test
    public void repoConfigCarriesTheSshKeyIdThroughTheRegistryJson() {
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes").setSshKeyId("kaaa");
        final List<GitRepoConfig> read = GitRepoRegistryCodec.fromJson(
                GitRepoRegistryCodec.toJson(Collections.singletonList(repo)));
        assertThat(read).hasSize(1);
        assertThat(read.get(0).getSshKeyId()).isEqualTo("kaaa");
        assertThat(new GitRepoConfig(read.get(0)).getSshKeyId()).isEqualTo("kaaa");
    }

    @Test
    public void aConfigWrittenBeforeTheFieldExistedHasNoKeyId() {
        final List<GitRepoConfig> read = GitRepoRegistryCodec.fromJson(
                "{\"version\":1,\"repositories\":[{\"path\":\"/storage/emulated/0/notes\"}]}");
        assertThat(read).hasSize(1);
        assertThat(read.get(0).getSshKeyId()).isNull();
    }

    @Test
    public void selectionPrefersTheRepositoryKeyThenTheDefault() {
        final GitSshKeyStore store = GitSshKeyStoreTestFixtures.emptyStore();
        assertThat(GitSshKeySelection.resolve(new GitRepoConfig("/notes"), store)).isNull();
        assertThat(GitSshKeySelection.resolve(null, null)).isNull();
    }
}
