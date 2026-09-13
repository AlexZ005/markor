/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * Progress reporting and cancellation for one {@link GitService} operation.
 * <p>
 * Callbacks are invoked on the thread that runs the operation (never the main thread by itself);
 * an implementation that updates views must post to the main thread. Callbacks may be frequent
 * during clone/fetch/push; implementations should be cheap. {@link #isCancelled()} is polled by
 * the service between steps and by JGit during transfers; returning {@code true} makes the running
 * operation stop as soon as possible and return {@link GitResult.Kind#CANCELLED}.
 * <p>
 * Task names come from JGit (e.g. "Receiving objects", "Resolving deltas", "Updating references")
 * or from the service itself and are suitable for display.
 */
public interface GitProgress {

    /** Total work is not known in advance (indeterminate progress). */
    int UNKNOWN = -1;

    /**
     * A task started.
     *
     * @param task      display name of the task
     * @param totalWork number of work units, or {@link #UNKNOWN}
     */
    void onTaskBegin(String task, int totalWork);

    /**
     * Progress within the current task. Called at most once per percent change, plus once at the end.
     *
     * @param task          display name of the task
     * @param completedWork units done so far
     * @param totalWork     total units, or {@link #UNKNOWN}
     * @param percent       0..100, or {@link #UNKNOWN} when total is unknown
     */
    void onTaskProgress(String task, int completedWork, int totalWork, int percent);

    /** The current task finished (successfully or not). */
    void onTaskEnd(String task);

    /** @return {@code true} to stop the running operation as soon as possible */
    boolean isCancelled();

    /** Reports nothing and is never cancelled. Use for fire-and-forget calls and tests. */
    GitProgress NONE = new GitProgress() {
        @Override
        public void onTaskBegin(final String task, final int totalWork) {
        }

        @Override
        public void onTaskProgress(final String task, final int completedWork, final int totalWork, final int percent) {
        }

        @Override
        public void onTaskEnd(final String task) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };
}
