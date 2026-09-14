/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Passphrases of imported SSH keys, for as long as the app is running (roadmap task 8.1c).
 * <p>
 * Same bargain as the access token, and for the same reason: "fetch when the tab opens" is on by
 * default, so a key with a passphrase would otherwise put a dialog in front of every sync. The
 * passphrase is kept in a {@code char[]} in memory, never written anywhere, never logged, and gone
 * when the process is — unlike the token, which the Keystore keeps across restarts. A passphrase is
 * only ever stored here after {@link net.gsantner.markor.git.ssh.GitSshPassphraseCheck} confirmed it
 * opens the key, so a cached one cannot be the wrong one.
 * <p>
 * Not a cache of keys: the private key itself is decrypted per operation and wiped in its
 * {@code finally} (see {@code JGitSsh}).
 */
final class GitSshPassphrases {

    private static final Map<String, char[]> REMEMBERED = new HashMap<>();

    private GitSshPassphrases() {
    }

    /**
     * @param keyId {@code GitSshKey.getId()}
     * @return a fresh copy the caller wipes, or {@code null} when none is remembered
     */
    static synchronized char[] get(final String keyId) {
        final char[] stored = keyId == null ? null : REMEMBERED.get(keyId);
        return stored == null ? null : stored.clone();
    }

    /** @param passphrase copied; the caller keeps ownership of its own array */
    static synchronized void remember(final String keyId, final char[] passphrase) {
        if (keyId == null || passphrase == null || passphrase.length == 0) {
            return;
        }
        forget(keyId);
        REMEMBERED.put(keyId, passphrase.clone());
    }

    /** Called when a key is deleted or its passphrase turned out not to fit any more. */
    static synchronized void forget(final String keyId) {
        final char[] stored = keyId == null ? null : REMEMBERED.remove(keyId);
        if (stored != null) {
            Arrays.fill(stored, '\0');
        }
    }

    /** Wipes every remembered passphrase. */
    static synchronized void clear() {
        for (final char[] stored : REMEMBERED.values()) {
            Arrays.fill(stored, '\0');
        }
        REMEMBERED.clear();
    }
}
