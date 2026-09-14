/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * {@link GitSshKeyStore} on the JVM: a temp folder and the in-memory {@link GitSshKeyStoreTestFixtures.InMemoryVault}
 * in place of the Android Keystore (roadmap task 8.1b). Everything except the Keystore itself is
 * covered here - key generation, the OpenSSH public-key encoding, the fingerprint, the import
 * formats ADR 0002 lists, the index surviving a restart, and what the store refuses.
 */
public class GitSshKeyStoreTest {

    private File _root;
    private GitSshKeyStoreTestFixtures.InMemoryVault _vault;
    private GitSshKeyStore _store;

    @Before
    public void setUp() {
        _root = GitSshKeyStoreTestFixtures.tempDir();
        _vault = new GitSshKeyStoreTestFixtures.InMemoryVault();
        _store = GitSshKeyStoreTestFixtures.storeIn(_root, _vault);
    }

    // ---------------------------------------------------------------- generate

    @Test
    public void generatesAnRsaKeyStoredEncryptedAndMakesItTheDefault() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.RSA, "Phone", GitSshTestKeys.TEST_RSA_BITS);

        assertThat(key.getId()).matches("k[0-9a-f]{16}");
        assertThat(key.getName()).isEqualTo("Phone");
        assertThat(key.getType()).isEqualTo(GitSshKey.Type.RSA);
        assertThat(key.getBits()).isEqualTo(GitSshTestKeys.TEST_RSA_BITS);
        assertThat(key.getPublicKeyLine()).startsWith("ssh-rsa AAAAB3NzaC1yc2E").endsWith(" Phone");
        assertThat(key.getFingerprintSha256()).startsWith("SHA256:").hasSize(50);
        assertThat(key.hasPassphrase()).isFalse();
        assertThat(key.canAuthenticate()).isTrue();

        assertThat(_store.list()).containsExactly(key);
        assertThat(_store.getDefault()).isEqualTo(key);
        assertThat(_store.exportPublicKey(key.getId())).isEqualTo(key.getPublicKeyLine());

        // The file on disk is ciphertext: no PEM header, and nothing of the public key in it either.
        final File onDisk = new File(new File(_root, key.getId()), "key.enc");
        assertThat(onDisk).isFile();
        final String raw = GitSshTestKeys.asString(readAll(onDisk));
        assertThat(raw).doesNotContain("PRIVATE KEY").doesNotContain("ssh-rsa");
    }

    @Test
    public void theStoredKeyIsAnOpenSshV1FileJschCanReadBack() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.RSA, "Phone", GitSshTestKeys.TEST_RSA_BITS);

        final byte[] priv = _store.loadPrivateKey(key.getId());
        try {
            assertThat(GitSshTestKeys.asString(priv)).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----");
            final KeyPair reloaded = KeyPair.load(new JSch(), priv.clone(), null);
            try {
                assertThat(reloaded.isEncrypted()).isFalse();
                assertThat(GitSshPublicKeys.fingerprintSha256(reloaded.getPublicKeyBlob()))
                        .isEqualTo(key.getFingerprintSha256());
            } finally {
                reloaded.dispose();
            }
        } finally {
            Arrays.fill(priv, (byte) 0);
        }
    }

    @Test
    public void generatesAnEcdsaKeyOnNistp256() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Small");

        assertThat(key.getType()).isEqualTo(GitSshKey.Type.ECDSA);
        assertThat(key.getBits()).isEqualTo(GitSshKeyStore.ECDSA_BITS);
        assertThat(key.getPublicKeyLine()).startsWith("ecdsa-sha2-nistp256 ");
        assertThat(key.canAuthenticate()).isTrue();
    }

    @Test
    public void refusesToGenerateEd25519BecauseThisBuildCannotSignWithIt() {
        assertThatThrownBy(() -> _store.generate(GitSshKey.Type.ED25519, "Modern"))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.UNSUPPORTED_TYPE);
        assertThat(_store.list()).isEmpty();
    }

    @Test
    public void refusesToGenerateWithoutAName() {
        assertThatThrownBy(() -> _store.generate(GitSshKey.Type.RSA, "  "))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.INVALID_REQUEST);
    }

    @Test
    public void aNameWithLineBreaksCannotBreakThePublicKeyLine() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Phone\nssh-rsa AAAA evil");

        // The comment is the rest of the line, so extra words in it are harmless; a line break
        // would not be, because it would turn one entry into two.
        assertThat(key.getPublicKeyLine()).doesNotContain("\n").doesNotContain("\r");
        assertThat(key.getPublicKeyLine()).startsWith("ecdsa-sha2-nistp256 ").endsWith(" Phone ssh-rsa AAAA evil");
        assertThat(GitSshPublicKeys.fingerprintSha256(GitSshPublicKeys.blobOf(key.getPublicKeyLine())))
                .isEqualTo(key.getFingerprintSha256());
    }

    @Test
    public void anEdDsaKeySizeIsReportedInBitsNotBytes() {
        // JSch's KeyPairEd25519.getKeySize() returns 32, i.e. bytes; ssh-keygen says 256.
        assertThat(GitSshKeyStore.bitsOf(GitSshKey.Type.ED25519, 32)).isEqualTo(256);
        assertThat(GitSshKeyStore.bitsOf(GitSshKey.Type.UNKNOWN, 57)).isEqualTo(456);
        assertThat(GitSshKeyStore.bitsOf(GitSshKey.Type.RSA, 4096)).isEqualTo(4096);
        assertThat(GitSshKeyStore.bitsOf(GitSshKey.Type.ECDSA, 521)).isEqualTo(521);
        assertThat(GitSshKeyStore.bitsOf(GitSshKey.Type.RSA, 0)).isZero();
        assertThat(GitSshKeyStore.bitsOf(GitSshKey.Type.ED25519, -1)).isZero();
    }

    // ---------------------------------------------------------------- import

    @Test
    public void importsAnOpenSshKeyWithoutAPassphrase() throws Exception {
        final byte[] file = GitSshTestKeys.openSshV1(null);
        final GitSshKey key = _store.importKey("From desktop", file, null);

        assertThat(key.getType()).isEqualTo(GitSshKey.Type.RSA);
        assertThat(key.getBits()).isEqualTo(GitSshTestKeys.TEST_RSA_BITS);
        assertThat(key.hasPassphrase()).isFalse();
        assertThat(key.getFingerprintSha256()).isEqualTo(GitSshTestKeys.fingerprintOf(file, null));
        // The comment the file carried wins over the name, as ssh-keygen's own output does.
        assertThat(key.getPublicKeyLine()).endsWith(" fixture@markor");
    }

    @Test
    public void importsAnEncryptedKeyAndKeepsItEncrypted() throws Exception {
        final byte[] file = GitSshTestKeys.openSshV1("hunter2");

        assertThatThrownBy(() -> _store.importKey("Protected", file, null))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.PASSPHRASE_REQUIRED);
        assertThatThrownBy(() -> _store.importKey("Protected", file, GitSshTestKeys.utf8("wrong")))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.BAD_PASSPHRASE);
        assertThat(_store.list()).isEmpty();

        final GitSshKey key = _store.importKey("Protected", file, GitSshTestKeys.utf8("hunter2"));
        assertThat(key.hasPassphrase()).isTrue();
        assertThat(key.getFingerprintSha256()).isEqualTo(GitSshTestKeys.fingerprintOf(file, "hunter2"));

        // The passphrase protection is still on the stored bytes: the store never keeps the
        // passphrase, so the code that authenticates has to ask for it again.
        final byte[] stored = _store.loadPrivateKey(key.getId());
        try {
            assertThat(stored).isEqualTo(file);
            assertThat(GitSshTestKeys.isEncrypted(stored)).isTrue();
        } finally {
            Arrays.fill(stored, (byte) 0);
        }
    }

    @Test
    public void aFailedPassphraseAttemptDoesNotSpoilTheNextOne() throws Exception {
        // ADR 0002 defect 2: every attempt must re-load the bytes instead of retrying a cached
        // KeyPair. The store does that internally, so two attempts in a row must behave.
        final byte[] file = GitSshTestKeys.openSshV1("hunter2");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> _store.importKey("Protected", file, GitSshTestKeys.utf8("wrong")))
                    .isInstanceOf(GitSshKeyException.class);
        }
        assertThat(_store.importKey("Protected", file, GitSshTestKeys.utf8("hunter2")).hasPassphrase()).isTrue();
    }

    @Test
    public void importsALegacyPkcs1PemKey() throws Exception {
        final byte[] file = GitSshTestKeys.pkcs1Pem();
        assertThat(GitSshTestKeys.asString(file)).startsWith("-----BEGIN RSA PRIVATE KEY-----");

        final GitSshKey key = _store.importKey("PEM", file, null);
        assertThat(key.getType()).isEqualTo(GitSshKey.Type.RSA);
        assertThat(key.getFingerprintSha256()).isEqualTo(GitSshTestKeys.fingerprintOf(file, null));
    }

    @Test
    public void importsAPkcs8Key() throws Exception {
        final byte[] file = GitSshTestKeys.pkcs8Pem("RSA", GitSshTestKeys.TEST_RSA_BITS);
        assertThat(GitSshTestKeys.asString(file)).startsWith("-----BEGIN PRIVATE KEY-----");

        final GitSshKey key = _store.importKey("PKCS8", file, null);
        assertThat(key.getType()).isEqualTo(GitSshKey.Type.RSA);
        assertThat(key.getPublicKeyLine()).startsWith("ssh-rsa ").endsWith(" PKCS8");
    }

    @Test
    public void aKeyOfATypeThisBuildCannotSignWithIsListedButNeverBecomesTheDefault() throws Exception {
        // ADR 0002 section 4: an ed25519 file imports, is named and is fingerprinted, but cannot
        // sign without Bouncy Castle. Writing a real ed25519 private key needs ssh-keygen or Bouncy
        // Castle, so the on-device matrix owns the parsing; what is checked here is the consequence,
        // on an entry of that type in the index.
        final GitSshKey usable = _store.generate(GitSshKey.Type.ECDSA, "Modern");
        rewriteIndex(GitSshKey.Type.ECDSA.name(), GitSshKey.Type.ED25519.name());

        final GitSshKeyStore reopened = GitSshKeyStoreTestFixtures.storeIn(_root, _vault);
        final GitSshKey ed = reopened.get(usable.getId());
        assertThat(ed).isNotNull();
        assertThat(ed.getType()).isEqualTo(GitSshKey.Type.ED25519);
        assertThat(ed.canAuthenticate()).isFalse();
        assertThat(reopened.list()).containsExactly(ed);
        assertThat(reopened.exportPublicKey(ed.getId())).isEqualTo(usable.getPublicKeyLine());
        assertThat(reopened.setDefault(ed.getId())).isFalse();
        // It stays what the index says it is - reporting a different default than what is stored
        // would be a lie - but nothing in the app may authenticate with it; that is what
        // canAuthenticate() above is for.
        assertThat(reopened.getDefault()).isEqualTo(ed);
        assertThat(reopened.getDefault().canAuthenticate()).isFalse();
    }

    @Test
    public void refusesAnEd25519Pkcs8FileThisBuildCannotRead() throws Exception {
        final byte[] file;
        try {
            file = GitSshTestKeys.pkcs8Pem("Ed25519", 256);
        } catch (final GeneralSecurityException noEdDsaInThisJvm) {
            return; // Below JDK 15 there is no Ed25519 provider.
        }
        // JSch reads ed25519 only from the OpenSSH format, not from PKCS#8; the refusal must name
        // the file, not the passphrase.
        assertThatThrownBy(() -> _store.importKey("Modern", file, null))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.UNREADABLE_KEY);
    }

    @Test
    public void refusesBytesThatAreNoPrivateKey() {
        for (final byte[] bytes : new byte[][]{GitSshTestKeys.utf8("hello"), new byte[]{0},
                GitSshTestKeys.utf8("-----BEGIN OPENSSH PRIVATE KEY-----\nnope\n-----END OPENSSH PRIVATE KEY-----\n")}) {
            assertThatThrownBy(() -> _store.importKey("Junk", bytes, null))
                    .isInstanceOf(GitSshKeyException.class)
                    .extracting(e -> ((GitSshKeyException) e).getReason())
                    .isEqualTo(GitSshKeyException.Reason.UNREADABLE_KEY);
        }
        assertThatThrownBy(() -> _store.importKey("Empty", new byte[0], null))
                .isInstanceOf(GitSshKeyException.class);
        assertThatThrownBy(() -> _store.importKey("Huge", new byte[GitSshKeyStore.MAX_PRIVATE_KEY_BYTES + 1], null))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.UNREADABLE_KEY);
        assertThat(_store.list()).isEmpty();
    }

    @Test
    public void theCallersBytesAreNotTouched() throws Exception {
        final byte[] file = GitSshTestKeys.openSshV1(null);
        final byte[] copy = file.clone();
        final byte[] passphrase = GitSshTestKeys.utf8("hunter2");
        final byte[] passphraseCopy = passphrase.clone();

        _store.importKey("Unchanged", file, passphrase);

        assertThat(file).isEqualTo(copy);
        assertThat(passphrase).isEqualTo(passphraseCopy);
    }

    // ---------------------------------------------------------------- default, delete, restart

    @Test
    public void theSecondKeyDoesNotBecomeTheDefaultByItself() throws Exception {
        final GitSshKey first = _store.generate(GitSshKey.Type.ECDSA, "First");
        final GitSshKey second = _store.generate(GitSshKey.Type.ECDSA, "Second");

        assertThat(_store.getDefault()).isEqualTo(first);
        assertThat(_store.setDefault(second.getId())).isTrue();
        assertThat(_store.getDefault()).isEqualTo(second);
        assertThat(_store.setDefault(null)).isTrue();
        assertThat(_store.getDefault()).isNull();
        assertThat(_store.setDefault("kdoesnotexist")).isFalse();
    }

    @Test
    public void keysAndTheDefaultSurviveANewStoreOverTheSameFolder() throws Exception {
        final GitSshKey first = _store.generate(GitSshKey.Type.ECDSA, "First");
        final GitSshKey second = _store.generate(GitSshKey.Type.ECDSA, "Second");
        assertThat(_store.setDefault(second.getId())).isTrue();

        final GitSshKeyStore reopened = GitSshKeyStoreTestFixtures.storeIn(_root, _vault);
        assertThat(reopened.list()).containsExactly(first, second);
        assertThat(reopened.getDefault()).isEqualTo(second);
        assertThat(reopened.get(first.getId()).getName()).isEqualTo("First");
        assertThat(reopened.get(first.getId()).getPublicKeyLine()).isEqualTo(first.getPublicKeyLine());

        final byte[] priv = reopened.loadPrivateKey(second.getId());
        try {
            assertThat(GitSshTestKeys.asString(priv)).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----");
        } finally {
            Arrays.fill(priv, (byte) 0);
        }
    }

    @Test
    public void deleteRemovesTheKeyFileAndLeavesNoDefaultBehind() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Gone");
        final File dir = new File(_root, key.getId());
        assertThat(new File(dir, "key.enc")).isFile();

        assertThat(_store.delete(key.getId())).isTrue();

        assertThat(_store.list()).isEmpty();
        assertThat(_store.get(key.getId())).isNull();
        assertThat(_store.getDefault()).isNull();
        assertThat(new File(dir, "key.enc")).doesNotExist();
        assertThat(dir).doesNotExist();
        assertThat(_store.delete(key.getId())).isFalse();
        assertThatThrownBy(() -> _store.loadPrivateKey(key.getId()))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.NOT_FOUND);
    }

    @Test
    public void deletingTheDefaultDoesNotPromoteAnotherKey() throws Exception {
        final GitSshKey first = _store.generate(GitSshKey.Type.ECDSA, "First");
        _store.generate(GitSshKey.Type.ECDSA, "Second");

        assertThat(_store.delete(first.getId())).isTrue();

        assertThat(_store.list()).hasSize(1);
        assertThat(_store.getDefault()).isNull();
    }

    @Test
    public void anEntryWhoseKeyFileWentMissingIsNotListed() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Half gone");
        assertThat(new File(new File(_root, key.getId()), "key.enc").delete()).isTrue();

        final GitSshKeyStore reopened = GitSshKeyStoreTestFixtures.storeIn(_root, _vault);
        assertThat(reopened.list()).isEmpty();
        assertThat(reopened.get(key.getId())).isNull();
        assertThat(reopened.getDefault()).isNull();
    }

    // ---------------------------------------------------------------- a failing index write

    @Test
    public void aDefaultThatCouldNotBeWrittenIsNotAdoptedInMemory() throws Exception {
        final GitSshKey first = _store.generate(GitSshKey.Type.ECDSA, "First");
        final GitSshKey second = _store.generate(GitSshKey.Type.ECDSA, "Second");
        blockIndexWrites();

        assertThat(_store.setDefault(second.getId())).isFalse();

        // Reporting failure and still using the new key would authenticate with a key the user was
        // just told was refused, and a restart would flip it back.
        assertThat(_store.getDefault()).isEqualTo(first);
        assertThat(GitSshKeyStoreTestFixtures.storeIn(_root, _vault).getDefault()).isEqualTo(first);
    }

    @Test
    public void aKeyWhoseRemovalCouldNotBeWrittenIsKeptWithItsFile() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Stays");
        blockIndexWrites();

        assertThat(_store.delete(key.getId())).isFalse();

        // The index still lists it, so the key file must still be there: the other order would
        // leave an entry the UI can neither show nor delete, with getDefault() pointing at it.
        assertThat(_store.list()).containsExactly(key);
        assertThat(_store.getDefault()).isEqualTo(key);
        assertThat(new File(new File(_root, key.getId()), "key.enc")).isFile();
        final byte[] priv = _store.loadPrivateKey(key.getId());
        try {
            assertThat(GitSshTestKeys.asString(priv)).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----");
        } finally {
            Arrays.fill(priv, (byte) 0);
        }
    }

    @Test
    public void theIndexIsNeverAbsentWhileItIsReplaced() throws Exception {
        _store.generate(GitSshKey.Type.ECDSA, "First");
        final File index = new File(_root, "index.json");
        final long before = index.length();

        _store.generate(GitSshKey.Type.ECDSA, "Second");

        // Written to a temp file and renamed over the index, never unlinked first: a process death
        // in between would otherwise orphan every key file, which alone carry no names or default.
        assertThat(index).isFile();
        assertThat(index.length()).isGreaterThan(before);
        assertThat(new File(_root, "index.json.tmp")).doesNotExist();
    }

    @Test
    public void aDefaultWhoseKeyFileIsGoneDoesNotStopTheNextKeyBecomingDefault() throws Exception {
        final GitSshKey ghost = _store.generate(GitSshKey.Type.ECDSA, "Ghost");
        assertThat(new File(new File(_root, ghost.getId()), "key.enc").delete()).isTrue();

        final GitSshKeyStore reopened = GitSshKeyStoreTestFixtures.storeIn(_root, _vault);
        assertThat(reopened.getDefault()).isNull();
        final GitSshKey fresh = reopened.generate(GitSshKey.Type.ECDSA, "Fresh");

        assertThat(reopened.getDefault()).isEqualTo(fresh);
    }

    // ---------------------------------------------------------------- refusals

    @Test
    public void withoutAUsableKeystoreNothingIsStored() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Before");
        _vault.setAvailable(false);

        assertThat(_store.isUsable()).isFalse();
        assertThatThrownBy(() -> _store.generate(GitSshKey.Type.RSA, "After"))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE);
        assertThatThrownBy(() -> _store.importKey("After", GitSshTestKeys.openSshV1(null), null))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE);
        assertThatThrownBy(() -> _store.loadPrivateKey(key.getId()))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE);

        // Listing and exporting are public information and keep working.
        assertThat(_store.list()).containsExactly(key);
        assertThat(_store.exportPublicKey(key.getId())).isEqualTo(key.getPublicKeyLine());
    }

    @Test
    public void aKeyStoredByAnotherKeystoreKeyCannotBeDecrypted() throws Exception {
        final GitSshKey key = _store.generate(GitSshKey.Type.ECDSA, "Restored backup");

        final GitSshKeyStore withOtherVault = GitSshKeyStoreTestFixtures.storeIn(
                _root, new GitSshKeyStoreTestFixtures.InMemoryVault());
        assertThat(withOtherVault.list()).containsExactly(key);
        assertThatThrownBy(() -> withOtherVault.loadPrivateKey(key.getId()))
                .isInstanceOf(GitSshKeyException.class)
                .extracting(e -> ((GitSshKeyException) e).getReason())
                .isEqualTo(GitSshKeyException.Reason.CRYPTO_FAILED);
    }

    @Test
    public void anIdFromOutsideCannotReachAnotherFolder() throws Exception {
        _store.generate(GitSshKey.Type.ECDSA, "Real");

        for (final String id : new String[]{"../../../etc/passwd", "..", ".", "k1/../k2",
                "index.json", "k1" + (char) 0}) {
            assertThat(_store.get(id)).isNull();
            assertThat(_store.setDefault(id)).isFalse();
            assertThat(_store.delete(id)).isFalse();
            assertThat(_store.exportPublicKey(id)).isNull();
            assertThatThrownBy(() -> _store.loadPrivateKey(id)).isInstanceOf(GitSshKeyException.class);
        }
        assertThat(_store.list()).hasSize(1);

        // A blank id is not an attempt to escape but the documented way to have no default at all.
        for (final String blank : new String[]{null, "", "   "}) {
            assertThat(_store.setDefault(blank)).isTrue();
            assertThat(_store.getDefault()).isNull();
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Makes every later index write fail: the store writes {@code index.json.tmp} first, and a
     * directory of that name cannot be opened as a file.
     */
    private void blockIndexWrites() {
        assertThat(new File(_root, "index.json.tmp").mkdir()).isTrue();
    }

    /** Edits index.json directly, to produce an entry no API of the store would create. */
    private void rewriteIndex(final String from, final String to) throws Exception {
        final File index = new File(_root, "index.json");
        final String json = GitSshTestKeys.asString(readAll(index));
        try (final java.io.OutputStream out = new java.io.FileOutputStream(index)) {
            out.write(GitSshTestKeys.utf8(json.replace(from, to)));
        }
    }

    private static byte[] readAll(final File file) throws Exception {
        final byte[] bytes = new byte[(int) file.length()];
        try (final java.io.InputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            for (int read = in.read(bytes); read > 0; read = in.read(bytes, offset, bytes.length - offset)) {
                offset += read;
                if (offset >= bytes.length) {
                    break;
                }
            }
        }
        return bytes;
    }
}
