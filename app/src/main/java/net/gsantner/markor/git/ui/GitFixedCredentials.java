/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import net.gsantner.markor.git.GitCredentialsSource;

import java.util.Arrays;
import java.util.Locale;

/**
 * The username and token the user just typed, for one operation only — used when testing a
 * connection or cloning, both of which happen before anything is stored.
 * <p>
 * The secret is handed out only for the host the URL named, so a remote that redirects elsewhere
 * never sees it. {@link #wipe()} clears it; the caller does that as soon as the operation has
 * finished, so the token is in memory for the duration of the call and no longer.
 */
final class GitFixedCredentials implements GitCredentialsSource {

    private final String _host;
    private final String _username;
    private final char[] _secret;
    private volatile boolean _wiped;

    /**
     * @param host     lower-case host the credentials belong to; {@code null} hands them to any host
     * @param username may be {@code null}
     * @param secret   copied; the caller keeps ownership of its own array
     */
    GitFixedCredentials(final String host, final String username, final char[] secret) {
        _host = host == null ? null : host.toLowerCase(Locale.ROOT);
        _username = username;
        _secret = secret == null ? new char[0] : secret.clone();
    }

    private boolean matches(final String host) {
        return _host == null || (host != null && _host.equals(host.trim().toLowerCase(Locale.ROOT)));
    }

    @Override
    public String getUsername(final String host) {
        return matches(host) ? _username : null;
    }

    @Override
    public char[] getSecret(final String host) {
        return !_wiped && matches(host) && _secret.length > 0 ? _secret.clone() : null;
    }

    /** Overwrites the stored secret; every later {@link #getSecret} returns {@code null}. */
    void wipe() {
        _wiped = true;
        Arrays.fill(_secret, '\0');
    }

    @Override
    public String toString() {
        return "GitFixedCredentials{host=" + _host + "}";
    }
}
