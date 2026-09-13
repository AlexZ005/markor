/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation for one submitted git operation.
 * <p>
 * {@link GitTaskRunner} hands a token to every task and flips it on
 * {@link GitTaskRunner#cancel(String)}. A task is expected to poll {@link #isCancelled()} (or call
 * {@link #throwIfCancelled()}) at its own checkpoints, for example from a JGit
 * {@code ProgressMonitor}. Nothing is interrupted or killed: a task that never polls simply runs to
 * the end, but its result is still delivered as cancelled, never as success.
 * <p>
 * Thread safe.
 */
public class GitCancelToken {

    /**
     * Thrown by {@link #throwIfCancelled()}. Unchecked so it can be raised from inside a JGit
     * callback. {@link GitTaskRunner} turns it into a cancelled result, never into an error.
     */
    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Git operation cancelled");
        }
    }

    /**
     * A token that is never cancelled, for callers that run a task outside the runner.
     */
    public static final GitCancelToken NEVER = new GitCancelToken();

    private final AtomicBoolean _cancelled = new AtomicBoolean(false);

    /**
     * @return true once cancellation was requested. Poll this from long operations.
     */
    public boolean isCancelled() {
        return _cancelled.get();
    }

    /**
     * Request cancellation. Idempotent.
     *
     * @return true if this call was the one that flipped the token
     */
    public boolean cancel() {
        return this != NEVER && _cancelled.compareAndSet(false, true);
    }

    /**
     * @throws CancelledException if cancellation was requested
     */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }
}
