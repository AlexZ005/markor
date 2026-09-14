/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import net.gsantner.markor.git.GitCredentialsSource;
import net.gsantner.markor.git.GitSshAuthSource;

/**
 * Puts the two halves of "how this operation authenticates" into one object: the HTTPS token source
 * the credential store hands out, and the SSH source for a key (roadmap task 8.1c).
 * <p>
 * Only one of them is ever used, and the operation does not choose which:
 * {@code GitRemoteUrlPolicy} decides that from the URL, so the token reaches https and nothing else
 * and the key reaches an SSH remote and nothing else. Bundling them means neither the fragment nor
 * the dialogs have to know which transport a repository is on before they start an operation.
 */
final class GitCredentials {

    private GitCredentials() {
    }

    /**
     * @param https the token source; {@code null} means no token is known
     * @param ssh   the key source; {@code null} means no key is known
     * @return a source carrying both, never {@code null}
     */
    static GitCredentialsSource of(final GitCredentialsSource https, final GitSshAuthSource ssh) {
        final GitCredentialsSource token = https == null ? GitCredentialsSource.NONE : https;
        final GitSshAuthSource keys = ssh == null ? GitSshAuthSource.NONE : ssh;
        return new GitCredentialsSource() {
            @Override
            public String getUsername(final String host) {
                return token.getUsername(host);
            }

            @Override
            public char[] getSecret(final String host) {
                return token.getSecret(host);
            }

            @Override
            public GitSshAuthSource ssh() {
                return keys;
            }
        };
    }
}
