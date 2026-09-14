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

    /** Schemes JGit can reach over the network but that this app must never use. */
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
        if (JGitRepos.hasUserinfo(trimmed)) {
            return "The remote URL carries a user name or password. Remove it from the URL and enter"
                    + " the token in the remote settings instead, so it is not stored in .git/config.";
        }

        final URIish uri;
        try {
            uri = new URIish(trimmed);
        } catch (URISyntaxException e) {
            return "The remote URL cannot be parsed";
        }

        final String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            // file:// or a local path: no network, and no stored token can be keyed to it.
            return scheme == null || "file".equals(scheme) ? null : unsupported(scheme);
        }
        return HTTPS.equals(scheme) ? null : unsupported(scheme);
    }

    private static String unsupported(final String scheme) {
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
