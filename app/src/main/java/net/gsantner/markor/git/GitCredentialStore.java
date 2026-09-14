/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import android.content.Context;
import android.content.SharedPreferences;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import other.de.stanetz.jpencconverter.PasswordStore;

/**
 * Where the HTTPS username and personal access token for each remote host live (roadmap task 2.5).
 * <p>
 * On API 23 and newer the token is encrypted with a per-host key in the Android Keystore through
 * {@link PasswordStore}; the username is plain, in a dedicated preferences file. Below API 23 (or
 * when the Keystore is unusable) everything is held in memory only and forgotten when the process
 * dies; {@link #isPersistent()} tells the UI to ask for the token again on each remote operation.
 * <p>
 * Keyed by the lower-case host of the remote URL ({@link #hostKey(String)}), so one token serves
 * every repository on the same host. The token is never logged, never put into a repository
 * configuration and never kept in a {@code String} field of this class; {@link #asSource()} hands
 * JGit a fresh copy on demand that the caller wipes.
 * <p>
 * This is the only class of the package that may import Android types; the storage backends are
 * behind {@link Backend} so the in-memory path and the key derivation run in JVM unit tests.
 */
public final class GitCredentialStore {

    /** Storage of username and token per host key. Implementations must be thread safe. */
    interface Backend {
        String loadUsername(String hostKey);

        /** @return a fresh copy the caller may zero, or {@code null} */
        char[] loadSecret(String hostKey);

        /** Stores copies of both values; replaces what was there. */
        boolean store(String hostKey, String username, char[] secret);

        void clear(String hostKey);

        /** @return {@code true} when data survives process death */
        boolean isPersistent();
    }

    private static GitCredentialStore sInstance;

    private final Backend _backend;

    GitCredentialStore(final Backend backend) {
        _backend = backend;
    }

    /**
     * The process-wide store. A singleton because the in-memory fallback must be the same object for
     * the dialog that collects the token and the worker that uses it.
     *
     * @param context any context; the application context is retained
     */
    public static synchronized GitCredentialStore get(final Context context) {
        if (sInstance == null) {
            sInstance = new GitCredentialStore(createBackend(context));
        }
        return sInstance;
    }

    private static Backend createBackend(final Context context) {
        // The Keystore is available on every supported device since this fork's minSdk is 26; only a
        // ROM that refuses it, or a call without a context, still ends up memory-only.
        if (context != null) {
            try {
                return new KeystoreBackend(context.getApplicationContext() != null ? context.getApplicationContext() : context);
            } catch (RuntimeException e) {
                // Keystore unavailable (odd ROMs, restricted profiles): degrade to memory-only.
            }
        }
        return new InMemoryBackend();
    }

    /** @return {@code true} when credentials survive process death (Keystore-backed) */
    public boolean isPersistent() {
        return _backend.isPersistent();
    }

    /**
     * Derives the storage key from a remote URL: the host name in lower case, without user, port or path.
     *
     * @return e.g. {@code github.com}; {@code null} when the URL has no host (file://, local paths, garbage)
     */
    public static String hostKey(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            return normalizeHost(new URIish(url.trim()).getHost());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String normalizeHost(final String host) {
        if (host == null) {
            return null;
        }
        final String h = host.trim().toLowerCase(Locale.ROOT);
        return h.isEmpty() ? null : h;
    }

    /** @return {@code true} when a token is stored for the URL's host */
    public boolean has(final String url) {
        final String key = hostKey(url);
        if (key == null) {
            return false;
        }
        final char[] secret = _backend.loadSecret(key);
        if (secret == null) {
            return false;
        }
        Arrays.fill(secret, '\0');
        return true;
    }

    /** @return the stored username for the URL's host, or {@code null} */
    public String getUsername(final String url) {
        final String key = hostKey(url);
        return key == null ? null : _backend.loadUsername(key);
    }

    /**
     * Stores username and token for the URL's host, replacing previous values. The array is copied;
     * the caller should zero its own copy afterwards.
     *
     * @return {@code false} when the URL has no host, either value is blank, or the backend failed (nothing stored then)
     */
    public boolean save(final String url, final String username, final char[] secret) {
        final String key = hostKey(url);
        if (key == null || username == null || username.trim().isEmpty() || secret == null || secret.length == 0) {
            return false;
        }
        return _backend.store(key, username.trim(), secret);
    }

    /** Removes username and token for the URL's host. */
    public void forget(final String url) {
        final String key = hostKey(url);
        if (key != null) {
            _backend.clear(key);
        }
    }

    /** @return a source for {@link GitService} remote calls, reading this store on demand */
    public GitCredentialsSource asSource() {
        return new GitCredentialsSource() {
            @Override
            public String getUsername(final String host) {
                final String key = normalizeHost(host);
                return key == null ? null : _backend.loadUsername(key);
            }

            @Override
            public char[] getSecret(final String host) {
                final String key = normalizeHost(host);
                return key == null ? null : _backend.loadSecret(key);
            }
        };
    }

    // ---------------------------------------------------------------- backends

    /** Memory only; forgotten on process death. Used below API 23 and in tests. */
    static final class InMemoryBackend implements Backend {
        private static final class Entry {
            final String _username;
            final char[] _secret;

            Entry(final String username, final char[] secret) {
                _username = username;
                _secret = secret;
            }
        }

        private final Map<String, Entry> _entries = new HashMap<>();

        @Override
        public synchronized String loadUsername(final String hostKey) {
            final Entry e = _entries.get(hostKey);
            return e == null ? null : e._username;
        }

        @Override
        public synchronized char[] loadSecret(final String hostKey) {
            final Entry e = _entries.get(hostKey);
            return e == null ? null : e._secret.clone();
        }

        @Override
        public synchronized boolean store(final String hostKey, final String username, final char[] secret) {
            clear(hostKey);
            _entries.put(hostKey, new Entry(username, secret.clone()));
            return true;
        }

        @Override
        public synchronized void clear(final String hostKey) {
            final Entry old = _entries.remove(hostKey);
            if (old != null) {
                Arrays.fill(old._secret, '\0');
            }
        }

        @Override
        public boolean isPersistent() {
            return false;
        }
    }

    /**
     * Token in the Android Keystore via {@link PasswordStore} (AES/GCM, one key per host, alias
     * {@code markor.git.token.<host>}); username in the {@code git_credentials} preferences file.
     * {@link PasswordStore#storeKey} takes the token as a {@code String}, which is the one place the
     * token exists as an immutable string; it is created for that call only.
     */
    private static final class KeystoreBackend implements Backend {
        private static final String PREFS_NAME = "git_credentials";
        private static final String USER_PREFIX = "user.";
        private static final String TOKEN_ALIAS_PREFIX = "markor.git.token.";

        private final PasswordStore _passwordStore;
        private final SharedPreferences _prefs;

        KeystoreBackend(final Context context) {
            _passwordStore = new PasswordStore(context);
            _prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        @Override
        public String loadUsername(final String hostKey) {
            return _prefs.getString(USER_PREFIX + hostKey, null);
        }

        @Override
        public char[] loadSecret(final String hostKey) {
            final char[] raw = _passwordStore.loadKey(TOKEN_ALIAS_PREFIX + hostKey);
            if (raw == null) {
                return null;
            }
            // PasswordStore returns the decoder's backing array, which can carry trailing NULs.
            int len = raw.length;
            while (len > 0 && raw[len - 1] == '\0') {
                len--;
            }
            if (len == 0) {
                Arrays.fill(raw, '\0');
                return null;
            }
            final char[] trimmed = Arrays.copyOf(raw, len);
            Arrays.fill(raw, '\0');
            return trimmed;
        }

        @Override
        public boolean store(final String hostKey, final String username, final char[] secret) {
            final boolean ok = _passwordStore.storeKey(new String(secret), TOKEN_ALIAS_PREFIX + hostKey, PasswordStore.SecurityMode.NONE);
            if (!ok) {
                clear(hostKey);
                return false;
            }
            _prefs.edit().putString(USER_PREFIX + hostKey, username).apply();
            return true;
        }

        @Override
        public void clear(final String hostKey) {
            _passwordStore.storeKey(null, TOKEN_ALIAS_PREFIX + hostKey, PasswordStore.SecurityMode.NONE);
            _prefs.edit().remove(USER_PREFIX + hostKey).apply();
        }

        @Override
        public boolean isPersistent() {
            return true;
        }
    }
}
