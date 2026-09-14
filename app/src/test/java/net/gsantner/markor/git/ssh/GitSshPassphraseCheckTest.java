/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A passphrase is checked against the key before an operation starts, so the dialog can say "that is
 * not it" instead of the server saying "authentication failed" a round trip later (roadmap task
 * 8.1c). The keys here are generated once and are RSA 2048 rather than 4096, which is all this
 * question needs and keeps the test under a second.
 */
public class GitSshPassphraseCheckTest {

    private static final byte[] PASSPHRASE = "hunter2".getBytes(StandardCharsets.UTF_8);

    private static byte[] _plain;
    private static byte[] _encrypted;

    @BeforeClass
    public static void generate() throws Exception {
        final JSch jsch = new JSch();
        final KeyPair pair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048);
        _plain = write(pair, null);
        _encrypted = write(pair, PASSPHRASE);
        pair.dispose();
    }

    /** OpenSSH v1, the only format the key store writes — see defect 1 in ADR 0002. */
    private static byte[] write(final KeyPair pair, final byte[] passphrase) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        pair.writeOpenSSHv1PrivateKey(out, passphrase == null ? null : passphrase.clone());
        return out.toByteArray();
    }

    @Test
    public void anUnencryptedKeyNeedsNoPassphraseAndAcceptsAny() {
        assertThat(GitSshPassphraseCheck.isEncrypted(_plain)).isFalse();
        assertThat(GitSshPassphraseCheck.canDecrypt(_plain, null)).isTrue();
        assertThat(GitSshPassphraseCheck.canDecrypt(_plain, PASSPHRASE)).isTrue();
    }

    @Test
    public void anEncryptedKeyOpensOnlyWithItsOwnPassphrase() {
        assertThat(GitSshPassphraseCheck.isEncrypted(_encrypted)).isTrue();
        assertThat(GitSshPassphraseCheck.canDecrypt(_encrypted, PASSPHRASE)).isTrue();
        assertThat(GitSshPassphraseCheck.canDecrypt(_encrypted, "wrong".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(GitSshPassphraseCheck.canDecrypt(_encrypted, null)).isFalse();
        assertThat(GitSshPassphraseCheck.canDecrypt(_encrypted, new byte[0])).isFalse();
    }

    /**
     * ADR 0002, defect 2: a failed attempt can leave a {@code KeyPair} unusable, so the bytes are
     * re-loaded every time rather than {@code decrypt} being retried on a cached object. A wrong
     * attempt must therefore not spoil the next, right one.
     */
    @Test
    public void aWrongAttemptDoesNotSpoilTheNextOne() {
        assertThat(GitSshPassphraseCheck.canDecrypt(_encrypted, "nope".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(GitSshPassphraseCheck.canDecrypt(_encrypted, PASSPHRASE)).isTrue();
    }

    @Test
    public void theCallersArraysAreLeftAsTheyWere() {
        final byte[] key = _encrypted.clone();
        final byte[] passphrase = PASSPHRASE.clone();
        assertThat(GitSshPassphraseCheck.canDecrypt(key, passphrase)).isTrue();
        assertThat(key).isEqualTo(_encrypted);
        assertThat(passphrase).isEqualTo(PASSPHRASE);
    }

    @Test
    public void garbageIsNotAPassphraseProblemButIsAnsweredTheSameWay() {
        assertThat(GitSshPassphraseCheck.canDecrypt("not a key at all".getBytes(StandardCharsets.UTF_8), null)).isFalse();
        assertThat(GitSshPassphraseCheck.canDecrypt(new byte[0], null)).isFalse();
        assertThat(GitSshPassphraseCheck.canDecrypt(null, null)).isFalse();
    }
}
