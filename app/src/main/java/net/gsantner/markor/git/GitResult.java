/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Typed outcome of every {@link GitService} operation. Operations never throw JGit exceptions;
 * they return one of the {@link Kind}s below instead, so the UI can switch on the kind.
 * <p>
 * Only {@link Kind#OK} carries a value. {@link Kind#CONFLICTS} and {@link Kind#DIRTY_WORK_TREE}
 * carry a list of repository-relative file paths. Every non-OK result carries a human readable,
 * credential-free {@link #getMessage() message} that a UI may show as a fallback for kinds it does
 * not handle specially. A result never contains a token, a password or a URL with userinfo.
 * <p>
 * Instances are immutable and safe to hand to another thread.
 *
 * @param <T> type of the value carried by an OK result; {@link Void} for operations without one
 */
public final class GitResult<T> {

    /** The outcome category. Switch on this in the UI. */
    public enum Kind {
        /** Success. {@link #getValue()} holds the result (may be {@code null} for {@link Void}). */
        OK,
        /** The remote rejected the credentials (HTTP 401/403, missing credentials). Re-prompt for the token. */
        AUTH_FAILED,
        /**
         * Push: the remote has commits the local branch does not ("pull first").
         * Pull with {@link GitPullStrategy#FF_ONLY}: local and remote diverged; offer REBASE or MERGE.
         */
        NON_FAST_FORWARD,
        /**
         * A merge or rebase stopped on conflicting files. The repository is left in the MERGING or REBASING
         * state (see {@link GitRepoInfo#getState()}), the conflict markers are in the files listed by
         * {@link #getFiles()}. Resolve them in the editor, then call
         * {@link GitService#continueAfterConflictResolution} or {@link GitService#abortMergeOrRebase}.
         */
        CONFLICTS,
        /**
         * The operation did not start because uncommitted local changes to the files in {@link #getFiles()}
         * would be overwritten. The repository is unchanged and in the NORMAL state. Commit (or discard) those
         * files first, then retry. Do not show the conflict-resolution UI for this kind.
         */
        DIRTY_WORK_TREE,
        /** Host unreachable, DNS failure, timeout, TLS handshake failure, connection reset. Retry later. */
        NETWORK,
        /**
         * SSH only: the server presented a host key that is not the one in the app's
         * {@code known_hosts}. Either the server was rebuilt, or something is between the app and it.
         * Not an authentication problem — the key was never offered — so it must not be answered with
         * "check your key", and the stored host key is never replaced as part of handling it: the
         * user forgets it deliberately under Settings &rsaquo; Git and connects again.
         */
        HOST_KEY_MISMATCH,
        /** The given path is not inside a (non-bare) git repository. */
        NOT_A_REPO,
        /** {@link GitProgress#isCancelled()} became true; the operation stopped early. Repository state is unchanged unless documented otherwise. */
        CANCELLED,
        /** Anything else. {@link #getMessage()} explains. */
        FAILED
    }

    private final Kind _kind;
    private final T _value;
    private final String _message;
    private final List<String> _files;

    private GitResult(final Kind kind, final T value, final String message, final Collection<String> files) {
        _kind = kind;
        _value = value;
        _message = message;
        _files = files == null || files.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(files));
    }

    /** Success with a value ({@code null} allowed). */
    public static <T> GitResult<T> ok(final T value) {
        return new GitResult<>(Kind.OK, value, null, null);
    }

    /** Success without a value. */
    public static GitResult<Void> ok() {
        return new GitResult<>(Kind.OK, null, null, null);
    }

    public static <T> GitResult<T> authFailed(final String message) {
        return new GitResult<>(Kind.AUTH_FAILED, null, orDefault(message, "Authentication failed"), null);
    }

    public static <T> GitResult<T> nonFastForward(final String message) {
        return new GitResult<>(Kind.NON_FAST_FORWARD, null, orDefault(message, "Not a fast-forward: local and remote have diverged"), null);
    }

    /** @param files repository-relative paths of the conflicting files (non-empty in practice) */
    public static <T> GitResult<T> conflicts(final Collection<String> files) {
        final int n = files == null ? 0 : files.size();
        return new GitResult<>(Kind.CONFLICTS, null, n + (n == 1 ? " file has" : " files have") + " conflicts", files);
    }

    /** @param files repository-relative paths with uncommitted changes that block the operation */
    public static <T> GitResult<T> dirtyWorkTree(final Collection<String> files) {
        final int n = files == null ? 0 : files.size();
        return new GitResult<>(Kind.DIRTY_WORK_TREE, null, "Uncommitted changes in " + n + (n == 1 ? " file" : " files") + " would be overwritten; commit them first", files);
    }

    /** @param message names the host and says the stored key is forgotten under Settings &rsaquo; Git */
    public static <T> GitResult<T> hostKeyMismatch(final String message) {
        return new GitResult<>(Kind.HOST_KEY_MISMATCH, null,
                orDefault(message, "The server's host key changed since this app last connected to it"), null);
    }

    public static <T> GitResult<T> network(final String message) {
        return new GitResult<>(Kind.NETWORK, null, orDefault(message, "Network error"), null);
    }

    public static <T> GitResult<T> notARepo(final File path) {
        return new GitResult<>(Kind.NOT_A_REPO, null, "Not a git repository: " + (path == null ? "(null)" : path.getPath()), null);
    }

    public static <T> GitResult<T> cancelled() {
        return new GitResult<>(Kind.CANCELLED, null, "Cancelled", null);
    }

    public static <T> GitResult<T> failed(final String message) {
        return new GitResult<>(Kind.FAILED, null, orDefault(message, "Operation failed"), null);
    }

    private static String orDefault(final String message, final String fallback) {
        return message == null || message.trim().isEmpty() ? fallback : message;
    }

    public Kind getKind() {
        return _kind;
    }

    public boolean isOk() {
        return _kind == Kind.OK;
    }

    /**
     * @return the value of an OK result
     * @throws IllegalStateException when this result is not OK
     */
    public T getValue() {
        if (_kind != Kind.OK) {
            throw new IllegalStateException("No value: result is " + this);
        }
        return _value;
    }

    /** @return the value when OK, otherwise {@code null} */
    public T getValueOrNull() {
        return _kind == Kind.OK ? _value : null;
    }

    /** @return credential-free explanation; {@code null} for OK */
    public String getMessage() {
        return _message;
    }

    /** @return repository-relative paths for CONFLICTS and DIRTY_WORK_TREE, otherwise an empty list */
    public List<String> getFiles() {
        return _files;
    }

    /**
     * Re-types a non-OK result so it can be returned from a method with a different value type.
     *
     * @throws IllegalStateException when this result is OK
     */
    @SuppressWarnings("unchecked")
    public <U> GitResult<U> asError() {
        if (_kind == Kind.OK) {
            throw new IllegalStateException("asError() on an OK result");
        }
        return (GitResult<U>) this;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitResult)) return false;
        final GitResult<?> that = (GitResult<?>) o;
        return _kind == that._kind && Objects.equals(_value, that._value)
                && Objects.equals(_message, that._message) && _files.equals(that._files);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_kind, _value, _message, _files);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("GitResult{").append(_kind);
        if (_kind == Kind.OK) {
            sb.append(", value=").append(_value);
        } else {
            sb.append(", message='").append(_message).append('\'');
            if (!_files.isEmpty()) {
                sb.append(", files=").append(_files);
            }
        }
        return sb.append('}').toString();
    }
}
