/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

import java.io.File;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * Maps JGit and I/O exceptions to {@link GitResult} kinds. Package-private, shared by both ops
 * classes. Messages are passed through {@link JGitRepos#sanitizeUrl} so no userinfo leaks.
 */
final class JGitErrors {
    /** 401 / 403 as standalone tokens, e.g. "401 Unauthorized", "status 403". */
    private static final java.util.regex.Pattern HTTP_AUTH_STATUS = java.util.regex.Pattern.compile("\\b40[13]\\b");


    private JGitErrors() {
    }

    /**
     * @param e        the exception caught around a JGit call
     * @param progress the operation's progress; when it reports cancellation, the result is CANCELLED
     * @param repoDir  the repository argument, for the NOT_A_REPO message (may be null)
     */
    static <T> GitResult<T> map(final Exception e, final GitProgress progress, final File repoDir) {
        if (progress != null && progress.isCancelled()) {
            return GitResult.cancelled();
        }
        // Walk the cause chain once: JGit wraps I/O problems in TransportException/JGitInternalException.
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RepositoryNotFoundException) {
                return GitResult.notARepo(repoDir);
            }
            if (t instanceof org.eclipse.jgit.errors.TransportException && isAuthMessage(t.getMessage())) {
                return GitResult.authFailed("Authentication failed: " + sanitize(t.getMessage()));
            }
            if (t instanceof TransportException && isAuthMessage(t.getMessage())) {
                return GitResult.authFailed("Authentication failed: " + sanitize(t.getMessage()));
            }
            if (t instanceof NoRemoteRepositoryException) {
                return GitResult.failed("Remote repository not found: " + sanitize(t.getMessage()));
            }
            if (t instanceof InvalidRemoteException) {
                return GitResult.failed("Invalid remote: " + sanitize(t.getMessage()));
            }
            if (t instanceof CheckoutConflictException) {
                return GitResult.dirtyWorkTree(((CheckoutConflictException) t).getConflictingPaths());
            }
            if (isNetwork(t)) {
                return GitResult.network("Network error: " + sanitize(describe(t)));
            }
        }
        if (e instanceof TransportException || e instanceof org.eclipse.jgit.errors.TransportException) {
            final String msg = sanitize(e.getMessage());
            if (isNetworkMessage(msg)) {
                return GitResult.network("Network error: " + msg);
            }
            return GitResult.failed("Transport error: " + msg);
        }
        return GitResult.failed(describe(e));
    }

    /** Human readable "Type: message" without userinfo. */
    static String describe(final Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        final String msg = t.getMessage();
        final String name = t.getClass().getSimpleName();
        return sanitize(msg == null || msg.trim().isEmpty() ? name : msg);
    }

    static String sanitize(final String message) {
        if (message == null) {
            return "";
        }
        // Redact any scheme://user:pass@ occurrence inside free text, then run JGit's parser on the whole.
        return message.replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]*@", "$1");
    }

    private static boolean isAuthMessage(final String message) {
        if (message == null) {
            return false;
        }
        final String m = message.toLowerCase(Locale.ROOT);
        // Texts from JGitText: notAuthorized, authenticationNotSupported, noCredentialsProvider, serviceNotPermitted
        // HTTP status codes only as whole tokens: a temp path such as /tmp/junit4013/... must not read as 401.
        // Over SSH the message comes from JSch instead: "Auth fail for methods 'publickey'", "Auth cancel",
        // "USERAUTH fail", and OpenSSH's own "Permission denied (publickey)" when the server says it.
        return m.contains("not authorized") || m.contains("authentication") || m.contains("not permitted")
                || m.contains("credentialsprovider") || m.contains("auth fail") || m.contains("auth cancel")
                || m.contains("userauth") || m.contains("publickey") || HTTP_AUTH_STATUS.matcher(m).find();
    }

    private static boolean isNetwork(final Throwable t) {
        return t instanceof UnknownHostException || t instanceof ConnectException || t instanceof SocketTimeoutException
                || t instanceof NoRouteToHostException || t instanceof PortUnreachableException
                || t instanceof SocketException || t instanceof SSLException || t instanceof InterruptedIOException;
    }

    private static boolean isNetworkMessage(final String message) {
        if (message == null) {
            return false;
        }
        final String m = message.toLowerCase(Locale.ROOT);
        return m.contains("unknown host") || m.contains("connection") || m.contains("time out") || m.contains("timeout")
                || m.contains("remote hung up") || m.contains("network is unreachable") || m.contains("unreachable");
    }
}
