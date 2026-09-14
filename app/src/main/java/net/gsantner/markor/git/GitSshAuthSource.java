/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import net.gsantner.markor.git.ssh.GitSshSessionFactory;

import java.io.Closeable;
import java.io.File;

/**
 * Supplies the SSH identity one remote operation authenticates with, and answers the one question
 * that can arise while it runs: whether a host nobody has seen before may be trusted.
 * <p>
 * The counterpart of {@link GitCredentialsSource}, which supplies the HTTPS token, and reached
 * through it ({@link GitCredentialsSource#ssh()}) so that an operation carries one credential
 * argument rather than two. Which of the two is used is never the source's decision:
 * {@link GitRemoteUrlPolicy} decides it from the URL, the token goes to
 * {@link GitRemoteUrlPolicy.Transport#HTTPS} and the key to
 * {@link GitRemoteUrlPolicy.Transport#SSH}, and neither is even asked for on the other transport.
 * <p>
 * <b>Threading.</b> {@link #resolve} and {@link GitSshSessionFactory.HostKeyPrompt#acceptNewHostKey}
 * both run on the operation's worker thread and <i>may</i> block on the user — asking for a key's
 * passphrase, or showing a fingerprint to confirm. That is the opposite of the rule for
 * {@link GitCredentialsSource}, and deliberately so: a token that is not stored can be asked for
 * afterwards, because the operation simply ends in {@code AUTH_FAILED}; a host key cannot, because
 * the answer decides whether the connection that is being opened right now may continue. An
 * implementation that blocks must post to the main thread and must give up on its own after a while,
 * so an operation cannot hang forever behind an activity that is gone.
 * <p>
 * Plain Java, no Android types. The Android implementation is {@code GitSshAuth}.
 */
public interface GitSshAuthSource extends GitSshSessionFactory.HostKeyPrompt {

    /**
     * What one operation needs in order to authenticate over SSH: the identity, the app's
     * {@code known_hosts} — or the reason it may not proceed.
     * <p>
     * {@link Closeable} because the identity holds the decrypted private key: the operation closes
     * it in a {@code finally}, which wipes the bytes.
     */
    final class Resolution implements Closeable {
        private final GitSshSessionFactory.Identity _identity;
        private final File _knownHosts;
        private final String _refusal;

        private Resolution(final GitSshSessionFactory.Identity identity, final File knownHosts, final String refusal) {
            _identity = identity;
            _knownHosts = knownHosts;
            _refusal = refusal;
        }

        /**
         * @param identity   the key this operation authenticates with; wiped by {@link #close()}
         * @param knownHosts the app-private {@code known_hosts} file, created if it does not exist
         */
        public static Resolution of(final GitSshSessionFactory.Identity identity, final File knownHosts) {
            if (identity == null || knownHosts == null) {
                return refused("No SSH key is available for this repository");
            }
            return new Resolution(identity, knownHosts, null);
        }

        /** @param message why this operation may not authenticate; shown to the user as it is */
        public static Resolution refused(final String message) {
            return new Resolution(null, null,
                    message == null || message.trim().isEmpty() ? "No SSH key is available for this repository" : message);
        }

        public GitSshSessionFactory.Identity getIdentity() {
            return _identity;
        }

        public File getKnownHosts() {
            return _knownHosts;
        }

        /** @return {@code null} when the operation may proceed, otherwise the sentence refusing it */
        public String getRefusal() {
            return _refusal;
        }

        public boolean isOk() {
            return _refusal == null;
        }

        /** Overwrites the private key and passphrase bytes. Idempotent. */
        @Override
        public void close() {
            if (_identity != null) {
                _identity.wipe();
            }
        }

        @Override
        public String toString() {
            return "GitSshAuthSource.Resolution{" + (_refusal == null ? "ok" : "refused") + "}";
        }
    }

    /**
     * Decrypts and hands over the key this operation should use. Called once per operation, just
     * before the transport is opened, and only for a URL {@link GitRemoteUrlPolicy} classified as
     * {@link GitRemoteUrlPolicy.Transport#SSH}.
     *
     * @param repoDir the repository the operation runs on, or {@code null} for a clone or an
     *                ls-remote, where there is no repository yet and the key was chosen in the dialog
     * @param url     the URL the operation is about to connect to, as it stands in {@code .git/config}.
     *                The implementation checks it against the app's own record — see
     *                {@link net.gsantner.markor.git.ssh.GitSshRemoteTrust} — because a remote that
     *                became SSH behind the app's back must not be handed a key.
     * @return never {@code null}
     */
    Resolution resolve(File repoDir, String url);

    /** Knows no keys: every SSH operation is refused with a sentence saying where to set one up. */
    GitSshAuthSource NONE = new GitSshAuthSource() {
        @Override
        public Resolution resolve(final File repoDir, final String url) {
            return Resolution.refused("This repository's remote uses SSH, but no SSH key is set up."
                    + " Add one under Settings › Git.");
        }

        @Override
        public boolean acceptNewHostKey(final String host, final String keyType, final String fingerprint) {
            return false;
        }
    };
}
