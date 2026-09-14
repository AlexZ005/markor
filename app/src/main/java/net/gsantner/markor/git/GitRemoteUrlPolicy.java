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
 * The last word on which remote URLs may carry an operation (roadmap task 7.5, decision D4).
 * <p>
 * {@code GitRemoteUrlValidator} already refuses anything but {@code https://} in the two dialogs, but
 * that only guards what the user types <i>here</i>. The URL a fetch, pull or push actually talks to
 * comes out of {@code .git/config}, and a repository lives in the notebook folder on shared external
 * storage: every app holding storage permission can rewrite that file, as can a repository cloned on
 * a desktop and copied over, or one a sync app brought along. Since the app-wide manifest sets
 * {@code usesCleartextTraffic="true"}, nothing below this class would stop the stored token from
 * being sent to {@code http://…} in the clear.
 * <p>
 * So every remote operation asks here first, against the URL it is about to use:
 * <ul>
 * <li><b>https</b> — allowed; this is the only scheme that carries credentials.</li>
 * <li><b>no host</b> ({@code file:///…} or a plain path) — allowed. Nothing leaves the device, and
 * {@link GitCredentialStore#hostKey(String)} returns {@code null} for these, so no token matches
 * them anyway. The JVM tests fetch and push against {@code file://} bare remotes.</li>
 * <li><b>anything else</b> — refused, by name. {@code http} and {@code ftp} would send the token
 * unencrypted; {@code ssh}, {@code git} and the scp-like {@code git@host:path} form have no
 * transport in the app (phase 8.1).</li>
 * </ul>
 * A URL carrying userinfo is refused as well, wherever it came from: see
 * {@link JGitRepos#hasUserinfo(String)}.
 * <p>
 * Plain Java, no Android types, so the rules are covered by JVM unit tests.
 */
final class GitRemoteUrlPolicy {

    /** The one scheme that may carry the access token. */
    private static final String HTTPS = "https";

    private GitRemoteUrlPolicy() {
    }

    /**
     * @param url the remote URL an operation is about to use; may be {@code null}
     * @return {@code null} when the operation may proceed, otherwise the sentence explaining why not.
     * The sentence never repeats the URL, which may hide a token in its userinfo.
     */
    static String refusalFor(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return "The remote has no URL configured";
        }
        final String trimmed = url.trim();

        URIish uri = null;
        try {
            uri = new URIish(trimmed);
        } catch (URISyntaxException e) {
            // Reported below, once the scheme has had its say.
        }
        // From the string when it is written as scheme://, because for https://:token@host/x URIish
        // reports neither a scheme nor a host; from URIish otherwise, since it also understands the
        // one-slash form file:/path that File.toURI() produces.
        String scheme = schemeOf(trimmed);
        if (scheme == null && uri != null && uri.getScheme() != null) {
            scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        }

        // Both judged before the userinfo, so an SSH remote is told SSH is unsupported rather than
        // being told to remove the "git@" - advice that would not help.
        if (scheme != null && !HTTPS.equals(scheme) && !"file".equals(scheme)) {
            return unsupported(scheme);
        }
        if (scheme == null && looksScpLike(trimmed)) {
            return unsupported("ssh");
        }
        if (JGitRepos.hasUserinfo(trimmed)) {
            return "The remote URL carries a user name or password. Remove it in the Git tab under"
                    + " \u22ee \u2023 Remote\u2026 and enter the token in the field below the URL, so it is"
                    + " not stored in .git/config.";
        }
        if (uri == null) {
            return "The remote URL cannot be parsed";
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            // file:// or a local path: no network, and no stored token can be keyed to it.
            return scheme == null || "file".equals(scheme) ? null : unsupported(scheme);
        }
        return HTTPS.equals(scheme) ? null : unsupported(scheme);
    }

    /**
     * The scp-like syntax git understands without a scheme: {@code [user@]host:path}, where the part
     * before the colon carries no slash (otherwise it is a plain relative path such as {@code a/b:c}).
     * Mirrors {@code GitRemoteUrlValidator.looksScpLike}, which judges the same shape in the dialogs.
     */
    private static boolean looksScpLike(final String url) {
        if (url.startsWith("/") || url.startsWith(".")) {
            return false;
        }
        final int colon = url.indexOf(':');
        if (colon <= 0 || colon == url.length() - 1) {
            return false;
        }
        return url.lastIndexOf('/', colon) < 0;
    }

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

    private static String unsupported(final String scheme) {
        if ("ssh".equals(scheme) || scheme != null && (scheme.endsWith("+ssh") || scheme.startsWith("ssh+"))) {
            return "Only https:// remotes are supported; SSH is not available in this version.";
        }
        if ("http".equals(scheme)) {
            return "This repository's remote uses plain http://, which would send the access token"
                    + " unencrypted. Change the remote to https:// before syncing.";
        }
        if (scheme == null) {
            return "Only https:// remotes are supported; this repository's remote is an SSH-style"
                    + " address, which this version cannot use.";
        }
        return "Only https:// remotes are supported; this repository's remote uses " + scheme + "://.";
    }
}
