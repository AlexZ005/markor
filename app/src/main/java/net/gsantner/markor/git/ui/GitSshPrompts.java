/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

/**
 * The two questions an SSH operation can raise while it runs, asked from the git worker thread and
 * answered by the user (roadmap task 8.1c).
 * <p>
 * Both implementations block the worker thread until the user answers, which is unusual in this
 * codebase and deliberate. A connection cannot be paused: the host key decides whether the session
 * that is being opened right now may continue, and the passphrase has to be in the identity before
 * the transport is built, because JSch's own interactive prompt is switched off (ADR 0002,
 * "what 8.1c must implement", item 6). The alternative — ask first, on the main thread, before every
 * fetch — would ask about hosts and keys that the operation turns out not to need.
 * <p>
 * An implementation must therefore give up on its own after a while, so that an operation started
 * behind an activity that is gone ends in a failure instead of holding the repository's worker
 * thread forever.
 */
public interface GitSshPrompts {

    /**
     * The first contact with a server: show what it presented and let the user compare it with what
     * their forge publishes.
     *
     * @param host        the host from the remote URL
     * @param keyType     {@code ssh-ed25519}, {@code ecdsa-sha2-nistp256}, {@code ssh-rsa}, …
     * @param fingerprint {@code SHA256:…}, the spelling GitHub, GitLab and {@code ssh-keygen -lf} use
     * @return {@code true} to trust the server and store its key in {@code known_hosts}
     */
    boolean confirmHostKey(String host, String keyType, String fingerprint);

    /**
     * Asks for the passphrase of an imported key. Called only for a key that actually needs one, and
     * only once per key per app run — the answer is kept for the session, like the access token.
     *
     * @param keyName     the key's name, for the dialog's title; never a secret
     * @param fingerprint the key's {@code SHA256:…}, so the right key is obviously meant
     * @param wasWrong    {@code true} when this is a retry after a passphrase that did not fit
     * @return UTF-8 characters of the passphrase; {@code null} when the user cancelled. The caller
     * wipes the array.
     */
    char[] askPassphrase(String keyName, String fingerprint, boolean wasWrong);

    /** Asks nobody and answers no to everything: for a context with no UI to ask in. */
    GitSshPrompts NONE = new GitSshPrompts() {
        @Override
        public boolean confirmHostKey(final String host, final String keyType, final String fingerprint) {
            return false;
        }

        @Override
        public char[] askPassphrase(final String keyName, final String fingerprint, final boolean wasWrong) {
            return null;
        }
    };
}
