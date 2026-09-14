/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A {@link GitSshKeyStore} for JVM tests: a temp folder plus {@link InMemoryVault}, the test double
 * that stands in for the Android Keystore. Real AES/GCM, but with a key that lives in this object,
 * so the encrypt-at-rest path is exercised without Android.
 */
final class GitSshKeyStoreTestFixtures {

    private GitSshKeyStoreTestFixtures() {
    }

    static GitSshKeyStore emptyStore() {
        return new GitSshKeyStore(tempDir(), new InMemoryVault());
    }

    static GitSshKeyStore storeIn(final File root, final GitSshKeyStore.Vault vault) {
        return new GitSshKeyStore(root, vault);
    }

    static File tempDir() {
        try {
            final File dir = Files.createTempDirectory("markor-ssh-keys").toFile();
            dir.deleteOnExit();
            return new File(dir, "git/ssh");
        } catch (final IOException e) {
            throw new IllegalStateException("No temp folder for the test", e);
        }
    }

    /** AES/GCM with an in-memory key; the same wrapped format the Android vault writes. */
    static final class InMemoryVault implements GitSshKeyStore.Vault {
        private static final byte FORMAT_VERSION = 1;

        private final SecretKey _key;
        private final SecureRandom _random = new SecureRandom();

        private boolean _available = true;

        InMemoryVault() {
            try {
                final KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                _key = generator.generateKey();
            } catch (final GeneralSecurityException e) {
                throw new IllegalStateException("No AES in this JVM", e);
            }
        }

        /** Simulates a device whose Keystore cannot be used. */
        void setAvailable(final boolean available) {
            _available = available;
        }

        @Override
        public boolean isAvailable() {
            return _available;
        }

        @Override
        public byte[] wrap(final byte[] plain) throws GitSshKeyException {
            try {
                final byte[] iv = new byte[12];
                _random.nextBytes(iv);
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, _key, new GCMParameterSpec(128, iv));
                final byte[] encrypted = cipher.doFinal(plain);
                final byte[] out = new byte[2 + iv.length + encrypted.length];
                out[0] = FORMAT_VERSION;
                out[1] = (byte) iv.length;
                System.arraycopy(iv, 0, out, 2, iv.length);
                System.arraycopy(encrypted, 0, out, 2 + iv.length, encrypted.length);
                Arrays.fill(encrypted, (byte) 0);
                return out;
            } catch (final GeneralSecurityException e) {
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE, "wrap failed", e);
            }
        }

        @Override
        public byte[] unwrap(final byte[] wrapped) throws GitSshKeyException {
            if (wrapped == null || wrapped.length < 3 || wrapped[0] != FORMAT_VERSION) {
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_FAILED, "not our format");
            }
            final int ivLength = wrapped[1] & 0xFF;
            try {
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, _key, new GCMParameterSpec(128, wrapped, 2, ivLength));
                return cipher.doFinal(wrapped, 2 + ivLength, wrapped.length - 2 - ivLength);
            } catch (final GeneralSecurityException e) {
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_FAILED, "unwrap failed", e);
            }
        }
    }
}
