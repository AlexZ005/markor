/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * What the remote-setup and clone dialogs accept as a remote URL (roadmap decision D4: HTTPS with a
 * personal access token; SSH is phase 8.1). Plain Java with no Android types, so the rules are
 * covered by JVM unit tests; the caller maps {@link Problem} to a string resource.
 * <p>
 * Only {@code https://} passes. {@code ssh://} and the scp-like {@code git@host:path} form are
 * refused because there is no SSH transport in the app yet, and plain {@code http://} is refused
 * because it would send the token in clear text (roadmap open question 4 — blocked until that is
 * decided). A trailing {@code .git} and surrounding whitespace are fine; a password embedded in the
 * URL is not, since {@code GitService} rejects such URLs and it would end up in {@code .git/config}.
 */
public final class GitRemoteUrlValidator {

    /** Why a URL was refused; {@link #NONE} means it is usable. */
    public enum Problem {
        /** The URL is usable. */
        NONE,
        /** Nothing was entered. */
        EMPTY,
        /** {@code ssh://}, {@code git+ssh://} or the scp-like {@code git@host:path} form. */
        SSH_NOT_SUPPORTED,
        /** Plain {@code http://}: the token would travel unencrypted. */
        CLEARTEXT_HTTP,
        /** Any scheme other than https, or no scheme at all. */
        UNSUPPORTED_SCHEME,
        /** {@code https://user:password@host/...}; the password must not be stored in the repository. */
        CONTAINS_PASSWORD,
        /** https, but not parsable or without a host. */
        MALFORMED
    }

    /** Outcome of {@link #validate(String)}; immutable. */
    public static final class Result {
        private final Problem _problem;
        private final String _url;
        private final String _host;

        Result(final Problem problem, final String url, final String host) {
            _problem = problem;
            _url = url;
            _host = host;
        }

        public Problem getProblem() {
            return _problem;
        }

        public boolean isValid() {
            return _problem == Problem.NONE;
        }

        /** @return the input with surrounding whitespace removed; what should be stored and shown */
        public String getUrl() {
            return _url;
        }

        /** @return the lower-case host of a valid URL, otherwise {@code null} */
        public String getHost() {
            return _host;
        }

        @Override
        public String toString() {
            // Deliberately without the URL: it is user input that may carry userinfo.
            return "GitRemoteUrlValidator.Result{" + _problem + "}";
        }
    }

    private GitRemoteUrlValidator() {
    }

    /**
     * @param input what the user typed; {@code null} is treated as empty
     * @return never {@code null}; {@link Result#isValid()} says whether it may be used
     */
    public static Result validate(final String input) {
        final String url = input == null ? "" : input.trim();
        if (url.isEmpty()) {
            return new Result(Problem.EMPTY, url, null);
        }
        if (containsWhitespace(url)) {
            return new Result(Problem.MALFORMED, url, null);
        }

        final int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            // No scheme: "git@github.com:me/notes.git" and "github.com:me/notes" are the scp-like SSH form.
            return new Result(looksScpLike(url) ? Problem.SSH_NOT_SUPPORTED : Problem.UNSUPPORTED_SCHEME, url, null);
        }

        final String scheme = url.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (scheme.isEmpty()) {
            return new Result(Problem.MALFORMED, url, null);
        }
        if ("ssh".equals(scheme) || scheme.endsWith("+ssh") || scheme.startsWith("ssh+")) {
            return new Result(Problem.SSH_NOT_SUPPORTED, url, null);
        }
        if ("http".equals(scheme)) {
            return new Result(Problem.CLEARTEXT_HTTP, url, null);
        }
        if (!"https".equals(scheme)) {
            return new Result(Problem.UNSUPPORTED_SCHEME, url, null);
        }

        if (hasUserinfoPassword(url.substring(schemeEnd + 3))) {
            return new Result(Problem.CONTAINS_PASSWORD, url, null);
        }

        // URIish only recognises a lower-case scheme, and it is what GitCredentialStore.hostKey parses with.
        final URIish parsed;
        try {
            parsed = new URIish(scheme + url.substring(schemeEnd));
        } catch (URISyntaxException e) {
            return new Result(Problem.MALFORMED, url, null);
        }
        if (parsed.getPass() != null && !parsed.getPass().isEmpty()) {
            return new Result(Problem.CONTAINS_PASSWORD, url, null);
        }
        final String host = parsed.getHost() == null ? "" : parsed.getHost().trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return new Result(Problem.MALFORMED, url, null);
        }
        return new Result(Problem.NONE, url, host);
    }

    /**
     * {@code URIish} silently drops an empty user name, so {@code https://:token@host/x} would parse as
     * a plain URL. The authority is therefore inspected directly: everything before the last {@code @}
     * is userinfo, and a colon in it means a password.
     *
     * @param afterScheme the URL without {@code https://}
     */
    private static boolean hasUserinfoPassword(final String afterScheme) {
        int end = afterScheme.length();
        for (int i = 0; i < afterScheme.length(); i++) {
            final char c = afterScheme.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        final String authority = afterScheme.substring(0, end);
        final int at = authority.lastIndexOf('@');
        return at >= 0 && authority.lastIndexOf(':', at) >= 0;
    }

    private static boolean containsWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The scp-like syntax git understands without a scheme: {@code [user@]host:path}, where the part
     * before the colon carries no slash (otherwise it is a plain relative path such as {@code a/b:c}).
     */
    private static boolean looksScpLike(final String url) {
        if (url.startsWith("/") || url.startsWith(".")) {
            return false;
        }
        final int colon = url.indexOf(':');
        if (colon <= 0 || colon == url.length() - 1) {
            return false;
        }
        final String before = url.substring(0, colon);
        return before.indexOf('/') < 0 && !before.isEmpty();
    }
}
