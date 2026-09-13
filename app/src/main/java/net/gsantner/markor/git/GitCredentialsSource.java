/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * Supplies HTTPS credentials (username and personal access token) on demand, keyed by remote host.
 * <p>
 * The service asks only when the remote demands authentication and only for the host of the URL it
 * is talking to, copies the secret into JGit's credential item and zeroes its own copy immediately
 * after the transport has consumed it. Implementations must not keep the secret in a {@code String}
 * field; return a fresh {@code char[]} on every call and let the caller wipe it.
 * <p>
 * Called on the operation's worker thread. Must not block on the UI (no dialogs); when nothing is
 * stored return {@code null} and the operation ends with {@link GitResult.Kind#AUTH_FAILED}, which
 * is the UI's cue to prompt and retry. See {@code GitCredentialStore} for the Keystore-backed
 * implementation.
 */
public interface GitCredentialsSource {

    /**
     * @param host host name of the remote URL, lower case, e.g. {@code github.com}
     * @return the username for that host, or {@code null} when none is known
     */
    String getUsername(String host);

    /**
     * @param host host name of the remote URL, lower case
     * @return a fresh copy of the token or password; the caller zeroes it after use. {@code null} when none is known.
     */
    char[] getSecret(String host);

    /** Knows no credentials: remote operations succeed only against public/anonymous remotes. */
    GitCredentialsSource NONE = new GitCredentialsSource() {
        @Override
        public String getUsername(final String host) {
            return null;
        }

        @Override
        public char[] getSecret(final String host) {
            return null;
        }
    };
}
