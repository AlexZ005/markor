/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The app's one {@link GitSshKeyStore}: keys under {@code filesDir/git/ssh}, private halves
 * encrypted with an AES key that lives in the Android Keystore (roadmap task 8.1b).
 * <p>
 * This is the only class of the {@code git.ssh} package that imports Android types, which is what
 * lets the store itself be unit tested on the JVM. It is the counterpart of
 * {@link net.gsantner.markor.git.GitCredentialStore} for the HTTPS token, with one difference: the
 * token goes through {@code PasswordStore}, which keeps its ciphertext in {@code SharedPreferences}.
 * A private key must not live there - it is a file, and only its encryption key is in the Keystore.
 */
public final class GitSshKeyStores {

    private static final String TAG = "GitSshKeyStore";

    /** Where the keys live, relative to {@code Context.getFilesDir()}. */
    public static final String KEYS_DIR = "git/ssh";

    private static GitSshKeyStore sInstance;

    private GitSshKeyStores() {
    }

    /**
     * The process-wide store. A singleton so that the key manager screen, the settings summary and a
     * running git operation all see the same index.
     *
     * @param context any context; only the application context is retained
     * @return the store, also when the Keystore is unusable - {@link GitSshKeyStore#isUsable()} then
     * reports {@code false} and creating keys is refused instead of storing them unencrypted
     */
    public static synchronized GitSshKeyStore get(final Context context) {
        if (sInstance == null) {
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            sInstance = new GitSshKeyStore(new File(app.getFilesDir(), KEYS_DIR), new KeystoreVault());
        }
        return sInstance;
    }

    /**
     * AES/GCM with a 256 bit key in the Android Keystore, alias {@code markor.git.ssh.vault}. The
     * key is created on first use, is not exportable and needs no user authentication (a git sync
     * has to work while the screen is off).
     * <p>
     * Wrapped form: one version byte, one IV length byte, the IV, then the GCM ciphertext with its
     * tag. The version byte is what allows the format to change later without guessing.
     */
    static final class KeystoreVault implements GitSshKeyStore.Vault {
        private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
        private static final String KEY_ALIAS = "markor.git.ssh.vault";
        private static final String TRANSFORMATION = "AES/GCM/NoPadding";
        private static final int KEY_BITS = 256;
        private static final int TAG_BITS = 128;
        private static final byte FORMAT_VERSION = 1;

        @Override
        public boolean isAvailable() {
            try {
                return secretKey() != null;
            } catch (final GeneralSecurityException | RuntimeException e) {
                Log.w(TAG, "The Android Keystore is unusable, SSH keys cannot be stored");
                return false;
            }
        }

        @Override
        public byte[] wrap(final byte[] plain) throws GitSshKeyException {
            if (plain == null) {
                throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Nothing to encrypt");
            }
            try {
                final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey());
                final byte[] iv = cipher.getIV();
                final byte[] encrypted = cipher.doFinal(plain);
                final ByteArrayOutputStream out = new ByteArrayOutputStream(2 + iv.length + encrypted.length);
                out.write(FORMAT_VERSION);
                out.write(iv.length);
                out.write(iv, 0, iv.length);
                out.write(encrypted, 0, encrypted.length);
                Arrays.fill(encrypted, (byte) 0);
                return out.toByteArray();
            } catch (final GeneralSecurityException | RuntimeException e) {
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE,
                        "The key could not be encrypted: " + e.getClass().getSimpleName(), e);
            }
        }

        @Override
        public byte[] unwrap(final byte[] wrapped) throws GitSshKeyException {
            if (wrapped == null || wrapped.length < 3 || wrapped[0] != FORMAT_VERSION) {
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_FAILED,
                        "The stored key is not in a format this version wrote");
            }
            final int ivLength = wrapped[1] & 0xFF;
            if (ivLength <= 0 || 2 + ivLength >= wrapped.length) {
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_FAILED, "The stored key is truncated");
            }
            try {
                final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                        new GCMParameterSpec(TAG_BITS, wrapped, 2, ivLength));
                return cipher.doFinal(wrapped, 2 + ivLength, wrapped.length - 2 - ivLength);
            } catch (final GeneralSecurityException | RuntimeException e) {
                // A wiped Keystore key or a restored backup lands here; the key material is gone for
                // good, and the message must not suggest the passphrase is at fault.
                throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_FAILED,
                        "The stored key could not be decrypted: " + e.getClass().getSimpleName(), e);
            }
        }

        /** @return the store's AES key, creating it on first use; never null */
        private static synchronized SecretKey secretKey() throws GeneralSecurityException {
            final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            try {
                keyStore.load(null);
            } catch (final Exception e) {
                throw new GeneralSecurityException("Keystore cannot be loaded", e);
            }
            final KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }
}
