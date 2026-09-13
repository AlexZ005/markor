/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import java.util.Arrays;
import java.util.Locale;

/**
 * JGit {@link CredentialsProvider} that pulls username and token from a {@link GitCredentialsSource}
 * only when the transport asks for them (HTTP 401), keyed by the host of the URL being accessed.
 * Holds no secret itself: the token is copied into JGit's {@link CredentialItem.Password} and the
 * local copy is zeroed at once. Stateless apart from the source reference, so one instance per call
 * is cheap and nothing lingers after the call.
 */
final class JGitCredentials extends CredentialsProvider {
    private final GitCredentialsSource _source;

    private JGitCredentials(final GitCredentialsSource source) {
        _source = source;
    }

    /** @param source may be {@code null}, which means anonymous access */
    static JGitCredentials forSource(final GitCredentialsSource source) {
        return new JGitCredentials(source == null ? GitCredentialsSource.NONE : source);
    }

    /** @return host of the URI in lower case, empty string when there is none (e.g. file://) */
    static String hostOf(final URIish uri) {
        final String host = uri == null ? null : uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean supports(final CredentialItem... items) {
        for (final CredentialItem item : items) {
            if (!(item instanceof CredentialItem.Username) && !(item instanceof CredentialItem.Password)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean get(final URIish uri, final CredentialItem... items) throws UnsupportedCredentialItem {
        final String host = hostOf(uri);
        for (final CredentialItem item : items) {
            if (item instanceof CredentialItem.Username) {
                String user = _source.getUsername(host);
                if (user == null || user.isEmpty()) {
                    user = uri == null ? null : uri.getUser();
                }
                if (user == null || user.isEmpty()) {
                    return false;
                }
                ((CredentialItem.Username) item).setValue(user);
            } else if (item instanceof CredentialItem.Password) {
                final char[] secret = _source.getSecret(host);
                if (secret == null || secret.length == 0) {
                    return false;
                }
                try {
                    ((CredentialItem.Password) item).setValue(secret);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            } else {
                throw new UnsupportedCredentialItem(uri, item.getClass().getSimpleName() + ": " + item.getPromptText());
            }
        }
        return true;
    }
}
