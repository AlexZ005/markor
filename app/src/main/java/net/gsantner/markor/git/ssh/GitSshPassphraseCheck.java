/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

/**
 * Answers "is this the right passphrase for this key?" without connecting to anything.
 * <p>
 * The alternative would be to hand a wrong passphrase to the session factory and read the answer out
 * of a failed connection, which costs a round trip, tells the user "authentication failed" for
 * something that is not the server's doing, and leaves a wrong passphrase cached for the rest of the
 * session. Checking here means the passphrase dialog can say "wrong passphrase" and ask again.
 * <p>
 * The bytes are re-loaded on every call, never {@code decrypt}ed twice on one {@link KeyPair}: the
 * Phase 1 spike found that a failed attempt can leave a {@code KeyPair} unusable for some formats
 * (defect 2 in {@code doc/adr/0002-ssh-on-android.md}). A copy is loaded so JSch cannot write into
 * the caller's array, and both the copy and the {@code KeyPair} are disposed of afterwards.
 */
public final class GitSshPassphraseCheck {

    private GitSshPassphraseCheck() {
    }

    /**
     * @param privateKeyBytes the stored private key, in whichever format it was stored in
     * @param passphrase      UTF-8 bytes of the attempt; {@code null} or empty asks whether the key
     *                        needs one at all
     * @return {@code true} when the key can be read with that passphrase — which for an unencrypted
     * key is true of every passphrase, including none
     */
    public static boolean canDecrypt(final byte[] privateKeyBytes, final byte[] passphrase) {
        if (privateKeyBytes == null || privateKeyBytes.length == 0) {
            return false;
        }
        final byte[] copy = privateKeyBytes.clone();
        KeyPair pair = null;
        try {
            pair = KeyPair.load(new JSch(), copy, null);
            if (!pair.isEncrypted()) {
                return true;
            }
            return passphrase != null && passphrase.length > 0 && pair.decrypt(passphrase.clone());
        } catch (JSchException | RuntimeException e) {
            // An unreadable key is not a passphrase problem, but from here it is indistinguishable
            // and the caller's next step - report it and ask again - is the same either way.
            return false;
        } finally {
            if (pair != null) {
                pair.dispose();
            }
            java.util.Arrays.fill(copy, (byte) 0);
        }
    }

    /**
     * @return {@code true} when the key cannot be used without a passphrase. Used to decide whether
     * to ask at all, so that a key the store believes is encrypted but is not costs no dialog.
     */
    public static boolean isEncrypted(final byte[] privateKeyBytes) {
        return !canDecrypt(privateKeyBytes, null);
    }
}
