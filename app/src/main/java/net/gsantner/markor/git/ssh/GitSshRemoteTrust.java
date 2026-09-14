/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import net.gsantner.markor.git.GitRemoteUrlPolicy;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The second gate in front of an SSH key: the remote in {@code .git/config} has to be the remote the
 * app itself stored for that repository (roadmap task 8.1c, item 7 of "what 8.1c must implement" in
 * {@code doc/adr/0002-ssh-on-android.md}).
 * <p>
 * <b>Why a scheme allowlist is not enough.</b> The security review (task 7.5) established that
 * {@code .git/config} is untrusted input: repositories live on {@code /storage/emulated/0}, so any
 * app holding storage permission can rewrite a remote URL. For HTTPS that is answered by refusing
 * every scheme but https and by keying the token to a host. Neither answer works for SSH. A key is
 * not keyed to a host — it is one identity the user has — so an attacker who rewrote
 * <pre>[remote "origin"] url = https://github.com/me/notes.git</pre>
 * to {@code git@attacker.example:me/notes.git} would get a signature from the user's key, aimed at a
 * host of their choosing, on the next sync. Trust-on-first-use does not stop that: the host is new,
 * so the user is asked to confirm a fingerprint they have no way to judge, and confirming is the
 * obvious thing to do when a sync is what they wanted.
 * <p>
 * So the key is offered only when the app's own record — {@code GitRepoConfig.getRemoteUrl()}, which
 * lives in app-private storage and is written only by the remote and clone dialogs — already says
 * this repository is that SSH remote. A URL that changed underneath fails the operation with a
 * sentence naming where to fix it, exactly as the HTTPS policy already does for a scheme change.
 * Confirming the new address is then a deliberate act in the Git tab, not a fingerprint dialog.
 * <p>
 * Plain Java, no Android types, so the rules are covered by JVM unit tests.
 */
public final class GitSshRemoteTrust {

    private GitSshRemoteTrust() {
    }

    /**
     * @param storedUrl the URL the app has on record for this repository
     *                  ({@code GitRepoConfig.getRemoteUrl()}); {@code null} when it has none
     * @param urlInUse  the URL the operation is about to connect to, read from {@code .git/config}
     * @return {@code null} when the SSH key may be used, otherwise the sentence refusing the
     * operation. The sentence never repeats either URL.
     */
    public static String refusalFor(final String storedUrl, final String urlInUse) {
        if (!GitRemoteUrlPolicy.decide(urlInUse).isSsh()) {
            // Nothing calls this for a non-SSH URL; if something ever does, no key is handed out.
            return "This remote does not use SSH, so no SSH key is offered to it.";
        }
        if (storedUrl == null || storedUrl.trim().isEmpty()) {
            return "The Git tab has no remote on record for this repository, so it will not"
                    + " authenticate with your SSH key. Open ⋮ ‣ Remote… and save the"
                    + " remote address first.";
        }
        if (!GitRemoteUrlPolicy.decide(storedUrl).isSsh() || !sameRemote(storedUrl, urlInUse)) {
            return "The remote address in .git/config is not the one the Git tab stored for this"
                    + " repository, so your SSH key is not offered to it. Open ⋮ ‣"
                    + " Remote… to see the stored address and save it again if the change was"
                    + " yours.";
        }
        return null;
    }

    /**
     * Whether two remote URLs name the same repository on the same server as the same user.
     * <p>
     * Compared field by field rather than as strings, so that the two spellings of one SSH remote —
     * {@code git@github.com:me/notes.git} and {@code ssh://git@github.com/me/notes.git} — count as
     * equal: the dialogs store whichever the user pasted and {@code git remote set-url} may have
     * written the other. Nothing else is normalised. In particular {@code notes} and {@code notes.git}
     * stay different, and so do two different ports: treating them as equal would widen what the
     * stored URL vouches for, which is the whole point of the check.
     */
    public static boolean sameRemote(final String a, final String b) {
        final URIish ua = parse(a);
        final URIish ub = parse(b);
        if (ua == null || ub == null) {
            return a != null && b != null && a.trim().equals(b.trim());
        }
        return sameScheme(ua.getScheme(), ub.getScheme())
                && eq(ua.getUser(), ub.getUser())
                && eqIgnoreCase(ua.getHost(), ub.getHost())
                && ua.getPort() == ub.getPort()
                && path(ua).equals(path(ub));
    }

    /** The repository path without a leading or trailing slash; the only spelling difference allowed. */
    private static String path(final URIish uri) {
        String p = uri.getPath() == null ? "" : uri.getPath();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * The scp-like form has no scheme at all, so a missing one on either side is not a difference:
     * that is exactly the spelling change this method exists to see through. Two <i>different</i>
     * schemes are a difference, even two SSH ones.
     */
    private static boolean sameScheme(final String a, final String b) {
        return a == null || b == null || eqIgnoreCase(a, b);
    }

    private static boolean eqIgnoreCase(final String a, final String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private static boolean eq(final String a, final String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static URIish parse(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            return new URIish(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
