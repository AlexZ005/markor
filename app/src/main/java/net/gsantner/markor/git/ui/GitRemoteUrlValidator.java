/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import net.gsantner.markor.git.GitRemoteUrlPolicy;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * What the remote-setup and clone dialogs accept as a remote URL: {@code https://} with a personal
 * access token, and — since roadmap task 8.1c — SSH with a key (decision D4 as revised by
 * {@code doc/adr/0002-ssh-on-android.md}). Plain Java with no Android types, so the rules are covered
 * by JVM unit tests; the caller maps {@link Problem} to a string resource.
 * <p>
 * A valid result names its {@link Result#getTransport() transport}, which is what tells the dialogs
 * whether to ask for a user name and token or for an SSH key. Plain {@code http://} is refused
 * because it would send the token in clear text, and so is every other scheme.
 * <p>
 * <b>Userinfo.</b> For https any userinfo at all is refused: the form GitHub's instructions produce
 * is {@code https://<token>@github.com/me/notes.git}, where the token <i>is</i> the user name, and it
 * would end up in {@code .git/config} in the notebook folder. For SSH the opposite holds — the user
 * name is not a secret but the login name the server needs, so exactly one plain name is required and
 * a password is refused. Both readings live in {@link GitRemoteUrlPolicy}, which the remote
 * operations themselves consult, so the dialog and the point of use cannot drift apart.
 */
public final class GitRemoteUrlValidator {

    /** Why a URL was refused; {@link #NONE} means it is usable. */
    public enum Problem {
        /** The URL is usable. */
        NONE,
        /** Nothing was entered. */
        EMPTY,
        /**
         * An SSH URL without the login name: {@code github.com:me/notes.git} or
         * {@code ssh://github.com/me/notes.git}. Android has no {@code ~/.ssh/config} and no login
         * name to default to, so the name has to be written out ({@code git@…}).
         */
        SSH_USER_MISSING,
        /** Plain {@code http://}: the token would travel unencrypted. */
        CLEARTEXT_HTTP,
        /** Any scheme other than https and ssh, or no scheme at all. */
        UNSUPPORTED_SCHEME,
        /**
         * {@code https://user:password@host/...}, {@code https://token@host/...} or
         * {@code https://:token@host/...}: credentials in the URL end up in {@code .git/config}.
         * For SSH: a password behind the user name, or a user name that is not a plain login name.
         */
        CONTAINS_PASSWORD,
        /** Not parsable, or without a host. */
        MALFORMED
    }

    /** Outcome of {@link #validate(String)}; immutable. */
    public static final class Result {
        private final Problem _problem;
        private final String _url;
        private final String _host;
        private final GitRemoteUrlPolicy.Transport _transport;

        Result(final Problem problem, final String url, final String host, final GitRemoteUrlPolicy.Transport transport) {
            _problem = problem;
            _url = url;
            _host = host;
            _transport = transport;
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

        /**
         * @return {@link GitRemoteUrlPolicy.Transport#HTTPS} or
         * {@link GitRemoteUrlPolicy.Transport#SSH} for a valid URL, otherwise {@code null}. The
         * dialogs ask for a token for the first and for a key for the second.
         */
        public GitRemoteUrlPolicy.Transport getTransport() {
            return _transport;
        }

        /** @return {@code true} when this URL authenticates with an SSH key rather than a token */
        public boolean isSsh() {
            return _transport == GitRemoteUrlPolicy.Transport.SSH;
        }

        @Override
        public String toString() {
            // Deliberately without the URL: it is user input that may carry userinfo.
            return "GitRemoteUrlValidator.Result{" + _problem + ", " + _transport + "}";
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
            return problem(Problem.EMPTY, url);
        }
        if (containsWhitespace(url)) {
            return problem(Problem.MALFORMED, url);
        }

        final int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            // No scheme: "git@github.com:me/notes.git" and "github.com:me/notes" are the scp-like SSH form.
            return GitRemoteUrlPolicy.looksScpLike(url) ? validateSsh(url, url, true) : problem(Problem.UNSUPPORTED_SCHEME, url);
        }

        final String scheme = url.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (scheme.isEmpty()) {
            return problem(Problem.MALFORMED, url);
        }
        if (GitRemoteUrlPolicy.isSshScheme(scheme)) {
            // URIish only recognises a lower-case scheme; the stored URL keeps what the user typed.
            return validateSsh(url, scheme + url.substring(schemeEnd), false);
        }
        if ("http".equals(scheme)) {
            return problem(Problem.CLEARTEXT_HTTP, url);
        }
        if (!"https".equals(scheme)) {
            return problem(Problem.UNSUPPORTED_SCHEME, url);
        }

        if (hasUserinfo(url.substring(schemeEnd + 3))) {
            return problem(Problem.CONTAINS_PASSWORD, url);
        }

        // URIish only recognises a lower-case scheme, and it is what GitCredentialStore.hostKey parses with.
        final URIish parsed = parse(scheme + url.substring(schemeEnd));
        if (parsed == null) {
            return problem(Problem.MALFORMED, url);
        }
        if (parsed.getPass() != null && !parsed.getPass().isEmpty()) {
            return problem(Problem.CONTAINS_PASSWORD, url);
        }
        final String host = hostOf(parsed);
        if (host.isEmpty()) {
            return problem(Problem.MALFORMED, url);
        }
        return new Result(Problem.NONE, url, host, GitRemoteUrlPolicy.Transport.HTTPS);
    }

    /**
     * The SSH spellings. The rules are {@link GitRemoteUrlPolicy}'s, asked one at a time so that each
     * refusal can be named: the policy hands back one sentence, while the dialogs want to put a
     * different error on a different field depending on what is wrong.
     *
     * @param forParsing the same URL with a lower-cased scheme, which is all {@code URIish} accepts
     * @param scpLike    {@code true} for {@code user@host:path}, {@code false} for {@code ssh://…}
     */
    private static Result validateSsh(final String url, final String forParsing, final boolean scpLike) {
        final String authority = GitRemoteUrlPolicy.sshAuthority(url, scpLike);
        if (authority == null || authority.isEmpty()) {
            return problem(Problem.MALFORMED, url);
        }
        final int at = authority.lastIndexOf('@');
        if (at < 0) {
            return problem(Problem.SSH_USER_MISSING, url);
        }
        if (!GitRemoteUrlPolicy.isPlainSshUser(authority.substring(0, at))) {
            return problem(Problem.CONTAINS_PASSWORD, url);
        }
        final URIish parsed = parse(forParsing);
        if (parsed == null) {
            return problem(Problem.MALFORMED, url);
        }
        if (parsed.getPass() != null && !parsed.getPass().isEmpty()) {
            return problem(Problem.CONTAINS_PASSWORD, url);
        }
        final String host = hostOf(parsed);
        if (host.isEmpty() || parsed.getPath() == null || parsed.getPath().isEmpty()) {
            return problem(Problem.MALFORMED, url);
        }
        return new Result(Problem.NONE, url, host, GitRemoteUrlPolicy.Transport.SSH);
    }

    /**
     * Any userinfo at all is refused for https, not just a {@code user:password@} pair. The form
     * GitHub's own instructions produce is {@code https://<token>@github.com/me/notes.git}, where the
     * token is the <i>user name</i>; accepting it would write the token into {@code .git/config},
     * which sits in the notebook folder on shared storage. The username belongs in the field below
     * the URL, from where it goes to the Keystore.
     * <p>
     * The authority is inspected directly rather than through {@code URIish}, which reports neither a
     * user nor a host for {@code https://:token@host/x} and would let that spelling through.
     *
     * @param afterScheme the URL without {@code https://}
     */
    private static boolean hasUserinfo(final String afterScheme) {
        int end = afterScheme.length();
        for (int i = 0; i < afterScheme.length(); i++) {
            final char c = afterScheme.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return afterScheme.lastIndexOf('@', end - 1) >= 0;
    }

    private static URIish parse(final String url) {
        try {
            return new URIish(url);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String hostOf(final URIish parsed) {
        return parsed.getHost() == null ? "" : parsed.getHost().trim().toLowerCase(Locale.ROOT);
    }

    private static Result problem(final Problem problem, final String url) {
        return new Result(problem, url, null, null);
    }

    private static boolean containsWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
