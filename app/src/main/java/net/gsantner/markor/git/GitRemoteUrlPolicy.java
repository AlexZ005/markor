/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The last word on which remote URLs may carry an operation, and on <i>which credential</i> each one
 * may be given (roadmap tasks 7.5 and 8.1c, decision D4 as revised by
 * {@code doc/adr/0002-ssh-on-android.md}).
 * <p>
 * {@code GitRemoteUrlValidator} already judges what the user types in the two dialogs, but that only
 * guards what is entered <i>there</i>. The URL a fetch, pull or push actually talks to comes out of
 * {@code .git/config}, and a repository lives in the notebook folder on shared external storage:
 * every app holding storage permission can rewrite that file, as can a repository cloned on a desktop
 * and copied over, or one a sync app brought along. Since the app-wide manifest sets
 * {@code usesCleartextTraffic="true"}, nothing below this class would stop the stored token from
 * being sent to {@code http://…} in the clear.
 * <p>
 * So every remote operation asks here first, against the URL it is about to use, and gets back the
 * {@link Transport} that URL belongs to:
 * <ul>
 * <li><b>https</b> — {@link Transport#HTTPS}. The only scheme the access token may be sent to; a URL
 * carrying any userinfo at all is refused, see {@link JGitRepos#hasUserinfo(String)}.</li>
 * <li><b>ssh</b> ({@code ssh://}, {@code git+ssh://}, {@code ssh+git://} and the scp-like
 * {@code user@host:path}) — {@link Transport#SSH}. The only transport an SSH key may be offered to.
 * Userinfo here is <i>not</i> a credential: it is the SSH login name, which the server needs and
 * which is not a secret, so exactly one plain user name is allowed and anything else — a password,
 * an empty name, a name with unusual characters — is refused. The name is also
 * <b>required</b>: Android has no {@code ~/.ssh/config} and no login name to fall back on, so
 * {@code github.com:me/notes.git} would reach JSch with a null user and fail unhelpfully.</li>
 * <li><b>no host</b> ({@code file:///…} or a plain path) — {@link Transport#LOCAL}. Nothing leaves
 * the device, and {@link GitCredentialStore#hostKey(String)} returns {@code null} for these, so no
 * token matches them anyway. The JVM tests fetch and push against {@code file://} bare remotes.</li>
 * <li><b>anything else</b> — refused, by name. {@code http} and {@code ftp} would send the token
 * unencrypted; {@code git://} is unauthenticated and unencrypted.</li>
 * </ul>
 * <p>
 * <b>The transport decides the credential, not the other way round.</b> Deciding here rather than at
 * the point where a secret is handed over is what keeps the two apart: the token goes only to
 * {@link Transport#HTTPS} ({@link JGitCredentials#isTlsUri}), the SSH key only to
 * {@link Transport#SSH} ({@code JGitRemoteOps} attaches the session factory for nothing else).
 * Allowing SSH does not widen the HTTPS rules by one character.
 * <p>
 * Being allowed here is necessary but not sufficient for an SSH operation: because {@code .git/config}
 * is untrusted input, a remote that is SSH on disk must also be the SSH remote the app itself stored
 * for that repository, or the key is not offered at all. That second gate is
 * {@link net.gsantner.markor.git.ssh.GitSshRemoteTrust}.
 * <p>
 * Plain Java, no Android types, so the rules are covered by JVM unit tests.
 */
public final class GitRemoteUrlPolicy {

    /** Which transport a URL names, and therefore which credential it may be given. */
    public enum Transport {
        /** {@code https://…}: may carry the access token. */
        HTTPS,
        /** {@code ssh://…} or {@code user@host:path}: may carry an SSH key. */
        SSH,
        /** {@code file://…} or a plain path: no network, no credential. */
        LOCAL
    }

    /** What {@link #decide(String)} concluded: either a transport, or the sentence refusing the URL. */
    public static final class Decision {
        private final Transport _transport;
        private final String _refusal;

        private Decision(final Transport transport, final String refusal) {
            _transport = transport;
            _refusal = refusal;
        }

        /** @return the transport, or {@code null} when the URL was refused */
        public Transport getTransport() {
            return _transport;
        }

        /** @return the reason the URL may not be used, or {@code null} when it may. Never repeats the URL. */
        public String getRefusal() {
            return _refusal;
        }

        public boolean isAllowed() {
            return _refusal == null;
        }

        public boolean isSsh() {
            return _transport == Transport.SSH;
        }

        @Override
        public String toString() {
            // Deliberately without the URL: it may hide a token in its userinfo.
            return "GitRemoteUrlPolicy.Decision{" + (_refusal == null ? _transport : "refused") + "}";
        }
    }

    /** The one scheme that may carry the access token. */
    private static final String HTTPS = "https";

    private static final String USERINFO_REFUSAL =
            "The remote URL carries a user name or password. Remove it in the Git tab under"
                    + " ⋮ ‣ Remote… and enter the token in the field below the URL, so it is"
                    + " not stored in .git/config.";

    private GitRemoteUrlPolicy() {
    }

    /**
     * @param url the remote URL an operation is about to use; may be {@code null}
     * @return never {@code null}; {@link Decision#isAllowed()} says whether the operation may proceed
     */
    public static Decision decide(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return refused("The remote has no URL configured");
        }
        final String trimmed = url.trim();

        // From the string when it is written as scheme://, because for https://:token@host/x URIish
        // reports neither a scheme nor a host; from URIish otherwise, since it also understands the
        // one-slash form file:/path that File.toURI() produces.
        String scheme = schemeOf(trimmed);
        // URIish only recognises a lower-case scheme, and .git/config may hold any spelling.
        final String forParsing = scheme == null
                ? trimmed
                : scheme + trimmed.substring(trimmed.indexOf("://"));

        URIish uri = null;
        try {
            uri = new URIish(forParsing);
        } catch (URISyntaxException e) {
            // Reported below, once the scheme has had its say.
        }
        if (scheme == null && uri != null && uri.getScheme() != null) {
            scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        }

        // SSH first, and by shape rather than by scheme alone, so that the scp-like spelling is judged
        // by the SSH rules instead of falling through to "no scheme, therefore unsupported".
        final boolean scpLike = scheme == null && looksScpLike(trimmed);
        if (isSshScheme(scheme) || scpLike) {
            return decideSsh(trimmed, uri, scpLike);
        }

        if (scheme != null && !HTTPS.equals(scheme) && !"file".equals(scheme)) {
            return refused(unsupported(scheme));
        }
        if (JGitRepos.hasUserinfo(trimmed)) {
            return refused(USERINFO_REFUSAL);
        }
        if (uri == null) {
            return refused("The remote URL cannot be parsed");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            // file:// or a local path: no network, and no stored token can be keyed to it.
            return scheme == null || "file".equals(scheme) ? allowed(Transport.LOCAL) : refused(unsupported(scheme));
        }
        return HTTPS.equals(scheme) ? allowed(Transport.HTTPS) : refused(unsupported(scheme));
    }

    /**
     * @param url the remote URL an operation is about to use; may be {@code null}
     * @return {@code null} when the operation may proceed, otherwise the sentence explaining why not.
     * The sentence never repeats the URL, which may hide a token in its userinfo.
     */
    static String refusalFor(final String url) {
        return decide(url).getRefusal();
    }

    // ---------------------------------------------------------------- ssh

    /**
     * The SSH rules, applied to both spellings. The user name is the one piece of userinfo that is
     * allowed anywhere in this app, because for SSH it is not a credential: {@code git@github.com}
     * says which account on the server to log in as, every provider's own copy button produces it,
     * and the secret is the key, which never appears in a URL.
     *
     * @param uri     the parsed URL, or {@code null} when it did not parse
     * @param scpLike {@code true} for {@code user@host:path}, {@code false} for {@code ssh://…}
     */
    private static Decision decideSsh(final String url, final URIish uri, final boolean scpLike) {
        final String authority = sshAuthority(url, scpLike);
        if (authority == null || authority.isEmpty()) {
            return refused("The SSH remote URL cannot be parsed");
        }
        final int at = authority.lastIndexOf('@');
        if (at < 0) {
            return refused("SSH remotes need the user name the server logs you in as, for example"
                    + " git@github.com:me/notes.git. Add it in front of the host name.");
        }
        if (!isPlainSshUser(authority.substring(0, at))) {
            // user:pass@host, an empty name, or anything that is not a plain login name.
            return refused("The SSH remote URL carries a password or an unusual user name. Only a plain"
                    + " user name such as git@ may stand in front of the host; the key is what"
                    + " authenticates, and it is chosen in the Git tab.");
        }
        if (uri == null || uri.getHost() == null || uri.getHost().isEmpty()) {
            return refused("The SSH remote URL cannot be parsed");
        }
        if (uri.getPass() != null && !uri.getPass().isEmpty()) {
            return refused("The SSH remote URL carries a password. Remove it; an SSH remote"
                    + " authenticates with a key, chosen in the Git tab.");
        }
        return allowed(Transport.SSH);
    }

    /**
     * @return the {@code [user@]host[:port]} part of an SSH URL, or {@code null} when there is none
     */
    public static String sshAuthority(final String url, final boolean scpLike) {
        if (url == null) {
            return null;
        }
        if (scpLike) {
            // The colon that separates host from path is the first one *after* the userinfo: in
            // git:hunter2@host:path the leading colon belongs to the password, and cutting there
            // would report an authority of "git" and hide the rest from every check below.
            final int firstSlash = url.indexOf('/');
            final int limit = firstSlash < 0 ? url.length() : firstSlash;
            final int at = url.lastIndexOf('@', limit - 1);
            final int colon = url.indexOf(':', at + 1);
            return colon <= 0 ? null : url.substring(0, colon);
        }
        final int start = url.indexOf("://");
        if (start < 0) {
            return null;
        }
        final String rest = url.substring(start + 3);
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                return rest.substring(0, i);
            }
        }
        return rest;
    }

    /**
     * One plain login name and nothing else: no {@code :password}, no second {@code @}, no empty
     * name, nothing that could smuggle another meaning past JSch. The character set is the one
     * {@code useradd} allows plus a dot, which covers every forge (GitHub, GitLab, Gitea, Codeberg
     * all use {@code git}) and every self-hosted account name anyone actually has.
     */
    public static boolean isPlainSshUser(final String user) {
        if (user == null || user.isEmpty() || user.length() > 64) {
            return false;
        }
        for (int i = 0; i < user.length(); i++) {
            final char c = user.charAt(i);
            final boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** @return {@code true} for the schemes JGit routes to {@code TransportGitSsh} */
    public static boolean isSshScheme(final String scheme) {
        return "ssh".equals(scheme) || "git+ssh".equals(scheme) || "ssh+git".equals(scheme);
    }

    /**
     * Whether a URL is <i>shaped</i> like an SSH remote, regardless of whether it would be allowed.
     * Used where something has to be done to a URL that may already be broken — sanitizing one for
     * display, for instance, where a wrong answer would either hide the host or show a password.
     */
    public static boolean looksLikeSsh(final String url) {
        if (url == null) {
            return false;
        }
        final String trimmed = url.trim();
        final String scheme = schemeOf(trimmed);
        return isSshScheme(scheme) || scheme == null && looksScpLike(trimmed);
    }

    /**
     * The scp-like syntax git understands without a scheme: {@code [user@]host:path}, where the part
     * before the colon carries no slash (otherwise it is a plain relative path such as {@code a/b:c}).
     */
    public static boolean looksScpLike(final String url) {
        if (url == null || url.startsWith("/") || url.startsWith(".")) {
            return false;
        }
        final int colon = url.indexOf(':');
        if (colon <= 0 || colon == url.length() - 1) {
            return false;
        }
        return url.lastIndexOf('/', colon) < 0;
    }

    // ---------------------------------------------------------------- helpers

    /** @return the lower-case scheme written in front of {@code ://}, or {@code null} when there is none */
    private static String schemeOf(final String url) {
        final int end = url.indexOf("://");
        if (end <= 0) {
            return null;
        }
        final String scheme = url.substring(0, end).toLowerCase(Locale.ROOT);
        for (int i = 0; i < scheme.length(); i++) {
            final char c = scheme.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '.' && c != '-') {
                return null;
            }
        }
        return scheme;
    }

    private static Decision allowed(final Transport transport) {
        return new Decision(transport, null);
    }

    private static Decision refused(final String refusal) {
        return new Decision(null, refusal);
    }

    private static String unsupported(final String scheme) {
        if ("http".equals(scheme)) {
            return "This repository's remote uses plain http://, which would send the access token"
                    + " unencrypted. Change the remote to https:// before syncing.";
        }
        if ("git".equals(scheme)) {
            return "This repository's remote uses the git:// protocol, which is neither encrypted nor"
                    + " authenticated. Change the remote to https:// or ssh:// before syncing.";
        }
        if (scheme == null) {
            return "Only https:// and SSH remotes are supported; this repository's remote is neither.";
        }
        return "Only https:// and SSH remotes are supported; this repository's remote uses " + scheme + "://.";
    }
}
