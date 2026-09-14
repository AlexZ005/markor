/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

/**
 * Why a {@link GitSshKeyStore} call could not be carried out (roadmap task 8.1b).
 * <p>
 * Checked on purpose: every caller has to decide what to tell the user, and {@link Reason} is what
 * the UI maps to a string resource. The message is for developers and never contains key material,
 * a passphrase or a file's content - {@link #getMessage()} may end up in a log.
 */
public class GitSshKeyException extends Exception {

    /** What went wrong, in the form the UI can turn into one sentence. */
    public enum Reason {
        /** No key with that id (deleted meanwhile, or a stale per-repository selection). */
        NOT_FOUND,
        /** The store's folder or index could not be read or written. */
        IO,
        /** The Android Keystore is unusable on this device, so no private key can be stored. */
        CRYPTO_UNAVAILABLE,
        /** A stored key could not be decrypted with the store's Keystore key (wiped key, restored backup). */
        CRYPTO_FAILED,
        /** The imported file is encrypted and no passphrase was given. */
        PASSPHRASE_REQUIRED,
        /** The imported file is encrypted and the passphrase does not open it. */
        BAD_PASSPHRASE,
        /** The imported bytes are not a private key in any format this build reads. */
        UNREADABLE_KEY,
        /** Key generation failed inside JSch. */
        GENERATE_FAILED,
        /** The key name was empty. */
        INVALID_REQUEST,
        /** The requested key type cannot be generated in this build (ed25519 needs Bouncy Castle). */
        UNSUPPORTED_TYPE
    }

    private final Reason _reason;

    public GitSshKeyException(final Reason reason, final String message) {
        super(message);
        _reason = reason == null ? Reason.IO : reason;
    }

    public GitSshKeyException(final Reason reason, final String message, final Throwable cause) {
        super(message, cause);
        _reason = reason == null ? Reason.IO : reason;
    }

    public Reason getReason() {
        return _reason;
    }
}
