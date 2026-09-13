/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitTaskRunner;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Bridges a {@link GitProgress} — called on the worker thread, often several times per second — to a
 * listener on the main thread, and answers {@link #isCancelled()} from the runner's cancel token.
 * <p>
 * Updates are forwarded only when the task name or the whole percent changed, so a clone of a large
 * repository posts a handful of messages per second instead of thousands. Pass a {@code null}
 * listener for an operation that only needs to be cancellable.
 */
final class GitUiProgress implements GitProgress {

    /** Called on the main thread. */
    interface Listener {
        /**
         * @param task    display name of the running task, e.g. "Receiving objects"
         * @param percent 0..100, or {@link GitProgress#UNKNOWN} when the total is not known
         */
        void onGitProgress(String task, int percent);
    }

    private final GitCancelToken _token;
    private final Listener _listener;
    private final Executor _callbackExecutor;

    private String _lastTask;
    private int _lastPercent = Integer.MIN_VALUE;

    GitUiProgress(final GitCancelToken token, final Listener listener) {
        this(token, listener, GitTaskRunner.mainThreadExecutor());
    }

    /** @param callbackExecutor where the listener is called; the main thread in the app, direct in tests */
    GitUiProgress(final GitCancelToken token, final Listener listener, final Executor callbackExecutor) {
        _token = token;
        _listener = listener;
        _callbackExecutor = callbackExecutor;
    }

    /**
     * Cancellation only, for the read-only screens (diff viewer, commit detail) that show an
     * indeterminate spinner and have no use for task names or percentages.
     *
     * @param token the token {@code GitTaskRunner} handed to the running task; {@code null} never cancels
     */
    static GitProgress cancelOnly(final GitCancelToken token) {
        return token == null ? GitProgress.NONE : new GitUiProgress(token, null);
    }

    @Override
    public void onTaskBegin(final String task, final int totalWork) {
        publish(task, totalWork == UNKNOWN ? UNKNOWN : 0);
    }

    @Override
    public void onTaskProgress(final String task, final int completedWork, final int totalWork, final int percent) {
        publish(task, percent);
    }

    @Override
    public void onTaskEnd(final String task) {
        // The next onTaskBegin replaces the label; ending the last task is the operation's result.
    }

    @Override
    public boolean isCancelled() {
        return _token != null && _token.isCancelled();
    }

    private void publish(final String task, final int percent) {
        if (_listener == null || (percent == _lastPercent && Objects.equals(task, _lastTask))) {
            return;
        }
        _lastTask = task;
        _lastPercent = percent;
        _callbackExecutor.execute(() -> _listener.onGitProgress(task, percent));
    }
}
