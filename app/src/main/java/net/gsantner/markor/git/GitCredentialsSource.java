/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * Supplies the credentials a remote operation may need: the HTTPS username and personal access token
 * on demand, keyed by remote host, and — since roadmap task 8.1c — the SSH identity, through
 * {@link #ssh()}.
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

    /**
     * The SSH side of the same operation: which key to authenticate with and whether a new host may
     * be trusted. Kept behind a method with a default rather than in the operation's argument list,
     * because every implementation that predates SSH is right to answer "none" — and because the
     * two are never both used. {@link GitRemoteUrlPolicy} decides from the URL which transport an
     * operation runs on; the token goes only to https and the key only to SSH.
     *
     * @return never {@code null}; {@link GitSshAuthSource#NONE} when this source knows no keys
     */
    default GitSshAuthSource ssh() {
        return GitSshAuthSource.NONE;
    }

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
