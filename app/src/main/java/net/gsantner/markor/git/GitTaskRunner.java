/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import android.os.Handler;
import android.os.Looper;

import net.gsantner.opoc.wrapper.GsCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs git operations off the UI thread, one repository at a time.
 * <p>
 * Every repository gets its own single-thread executor, created lazily and keyed by the normalized
 * working folder path. Two operations on the same repository therefore never interleave - a commit
 * started while a pull is running simply queues - while operations on different repositories run in
 * parallel.
 * <p>
 * Results are delivered on the callback executor, which in the app is the Android main thread
 * ({@link #mainThreadExecutor()}); unit tests pass a direct executor instead. A callback can be tied
 * to an owner (typically "the fragment still has a view"); the owner is asked right before delivery
 * and the result is dropped if it says it is gone, so a callback never touches a destroyed view.
 * <p>
 * Cancellation is cooperative, see {@link GitCancelToken}. A cancelled task is delivered as
 * {@link GitTaskResult#cancelled()}, never as success, whether it was cancelled before it started or
 * while it ran.
 * <p>
 * Thread safe; {@link #submit} may be called from any thread.
 */
public class GitTaskRunner {

    /**
     * Creates the worker executor of one repository. Exists so tests can supply their own.
     */
    public interface ExecutorFactory {
        ExecutorService newSerialExecutor(String repoPath);
    }

    private static final Logger LOGGER = Logger.getLogger("net.gsantner.markor.git");

    private static volatile GitTaskRunner _instance;

    /**
     * The app-wide runner, delivering callbacks on the main thread.
     */
    public static GitTaskRunner get() {
        if (_instance == null) {
            synchronized (GitTaskRunner.class) {
                if (_instance == null) {
                    _instance = new GitTaskRunner(mainThreadExecutor());
                }
            }
        }
        return _instance;
    }

    /**
     * @return an executor that posts to the Android main looper. Not usable in JVM unit tests.
     */
    public static Executor mainThreadExecutor() {
        final Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    /**
     * State of one repository: its worker thread and the tasks that are still queued or running.
     */
    private static final class RepoLane {
        final ExecutorService executor;
        final List<GitCancelToken> tokens = new ArrayList<>();
        int pending;

        RepoLane(final ExecutorService executor) {
            this.executor = executor;
        }
    }

    private final Executor _callbackExecutor;
    private final ExecutorFactory _executorFactory;
    private final Map<String, RepoLane> _lanes = new HashMap<>();
    private final Object _lock = new Object();
    private boolean _shutdown;

    /**
     * @param callbackExecutor where callbacks are delivered, usually {@link #mainThreadExecutor()}
     */
    public GitTaskRunner(final Executor callbackExecutor) {
        this(callbackExecutor, GitTaskRunner::defaultSerialExecutor);
    }

    public GitTaskRunner(final Executor callbackExecutor, final ExecutorFactory executorFactory) {
        _callbackExecutor = callbackExecutor == null ? Runnable::run : callbackExecutor;
        _executorFactory = executorFactory == null ? GitTaskRunner::defaultSerialExecutor : executorFactory;
    }

    /**
     * Queue a task on the repository's worker thread.
     *
     * @param repoPath working folder of the repository; normalized, so spelling does not matter
     * @param task     the work; run on a background thread, may block
     * @param callback receives the outcome on the callback executor; may be null
     * @return the token of this task, already usable to cancel just this one
     */
    public <T> GitCancelToken submit(final String repoPath, final GitTask<T> task, final GitTaskCallback<T> callback) {
        return submit(repoPath, task, null, callback);
    }

    /**
     * Queue a task on the repository's worker thread.
     *
     * @param repoPath working folder of the repository; normalized, so spelling does not matter
     * @param task     the work; run on a background thread, may block
     * @param isAlive  asked right before delivery: when it returns false the callback is dropped.
     *                 Pass {@code () -> getView() != null} from a fragment, or null to always deliver
     * @param callback receives the outcome on the callback executor; may be null
     * @return the token of this task, already usable to cancel just this one
     */
    public <T> GitCancelToken submit(
            final String repoPath,
            final GitTask<T> task,
            final GsCallback.b0 isAlive,
            final GitTaskCallback<T> callback
    ) {
        if (task == null) {
            throw new IllegalArgumentException("GitTaskRunner.submit: task is null");
        }
        final String key = GitPaths.normalize(repoPath);
        final GitCancelToken token = new GitCancelToken();

        final RepoLane lane;
        synchronized (_lock) {
            lane = _shutdown ? null : laneFor(key);
            if (lane != null) {
                lane.tokens.add(token);
                lane.pending++;
            }
        }
        if (lane == null) {
            // Never deliver while holding the lock: a direct callback executor would run the
            // callback inline, and a callback that calls back into the runner would deadlock
            deliver(isAlive, callback, GitTaskResult.<T>cancelled());
            return token;
        }

        try {
            lane.executor.execute(() -> runAndDeliver(lane, token, task, isAlive, callback));
        } catch (final RejectedExecutionException e) {
            // The lane was shut down between the lock above and here
            finished(lane, token);
            deliver(isAlive, callback, GitTaskResult.<T>cancelled());
        }
        return token;
    }

    /**
     * @return true while a task for this repository is queued or running
     */
    public boolean isBusy(final String repoPath) {
        synchronized (_lock) {
            final RepoLane lane = _lanes.get(GitPaths.normalize(repoPath));
            return lane != null && lane.pending > 0;
        }
    }

    /**
     * @return true if any repository has work queued or running
     */
    public boolean isBusy() {
        synchronized (_lock) {
            for (final RepoLane lane : _lanes.values()) {
                if (lane.pending > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Ask every queued and running task of this repository to stop. A task that has not started yet
     * never runs; a running task stops at its next checkpoint. Either way the callback still fires,
     * with a cancelled result. Tasks submitted after this call are unaffected.
     *
     * @return the number of tasks that were asked to stop
     */
    public int cancel(final String repoPath) {
        final List<GitCancelToken> tokens;
        synchronized (_lock) {
            final RepoLane lane = _lanes.get(GitPaths.normalize(repoPath));
            if (lane == null) {
                return 0;
            }
            tokens = new ArrayList<>(lane.tokens);
        }
        int cancelled = 0;
        for (final GitCancelToken token : tokens) {
            if (token.cancel()) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * {@link #cancel(String)} for every repository.
     */
    public int cancelAll() {
        final List<String> paths;
        synchronized (_lock) {
            paths = new ArrayList<>(_lanes.keySet());
        }
        int cancelled = 0;
        for (final String path : paths) {
            cancelled += cancel(path);
        }
        return cancelled;
    }

    /**
     * Cancel this repository's tasks and release its worker thread; call it when a repository is
     * removed from the registry. The lane is recreated if the repository is used again.
     */
    public void shutdownRepo(final String repoPath) {
        cancel(repoPath);
        final RepoLane lane;
        synchronized (_lock) {
            lane = _lanes.remove(GitPaths.normalize(repoPath));
        }
        if (lane != null) {
            lane.executor.shutdown();
        }
    }

    /**
     * Cancel everything and release all worker threads. The runner accepts no further tasks;
     * anything submitted afterwards is delivered as cancelled.
     */
    public void shutdown() {
        cancelAll();
        final List<RepoLane> lanes;
        synchronized (_lock) {
            _shutdown = true;
            lanes = new ArrayList<>(_lanes.values());
            _lanes.clear();
        }
        for (final RepoLane lane : lanes) {
            lane.executor.shutdown();
        }
    }

    // Must be called while holding _lock
    private RepoLane laneFor(final String key) {
        RepoLane lane = _lanes.get(key);
        if (lane == null) {
            lane = new RepoLane(_executorFactory.newSerialExecutor(key));
            _lanes.put(key, lane);
        }
        return lane;
    }

    private <T> void runAndDeliver(
            final RepoLane lane,
            final GitCancelToken token,
            final GitTask<T> task,
            final GsCallback.b0 isAlive,
            final GitTaskCallback<T> callback
    ) {
        GitTaskResult<T> result;
        if (token.isCancelled()) {
            result = GitTaskResult.cancelled();
        } else {
            try {
                final T value = task.run(token);
                // A task that ignored the token, or finished just as it was flipped, is still cancelled
                result = token.isCancelled() ? GitTaskResult.<T>cancelled() : GitTaskResult.success(value);
            } catch (final GitCancelToken.CancelledException e) {
                result = GitTaskResult.cancelled();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                result = GitTaskResult.cancelled();
            } catch (final Throwable t) {
                // Throwable, not Exception: JGit on Android can raise NoClassDefFoundError, and a
                // worker thread dying silently would leave the UI waiting forever
                result = token.isCancelled() ? GitTaskResult.<T>cancelled() : GitTaskResult.<T>error(t);
            }
        }
        finished(lane, token);
        deliver(isAlive, callback, result);
    }

    private void finished(final RepoLane lane, final GitCancelToken token) {
        synchronized (_lock) {
            lane.tokens.remove(token);
            if (lane.pending > 0) {
                lane.pending--;
            }
        }
    }

    private <T> void deliver(final GsCallback.b0 isAlive, final GitTaskCallback<T> callback, final GitTaskResult<T> result) {
        if (callback == null) {
            return;
        }
        try {
            _callbackExecutor.execute(() -> {
                if (isAlive != null && !isAlive.callback()) {
                    return; // Owner is gone, e.g. the fragment view was destroyed
                }
                callback.onGitTaskResult(result);
            });
        } catch (final RejectedExecutionException e) {
            LOGGER.log(Level.WARNING, "Git result could not be delivered, callback executor rejected it");
        }
    }

    /** An idle worker thread ends after this long; the lane starts a new one for its next task. */
    static final long WORKER_IDLE_SECONDS = 30;

    /**
     * At most one thread, tasks in submission order, and the thread is released once the repository
     * has been quiet for {@link #WORKER_IDLE_SECONDS}. A lane is created for every repository, clone
     * target and initialized folder the app ever touches, and only a repository the user explicitly
     * removes is shut down, so a plain single-thread executor pinned one thread per path for the life
     * of the process.
     */
    static ExecutorService defaultSerialExecutor(final String repoPath) {
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "markor-git-" + shortName(repoPath));
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, WORKER_IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), factory);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static String shortName(final String repoPath) {
        final int slash = repoPath == null ? -1 : repoPath.lastIndexOf('/');
        return slash >= 0 && slash < repoPath.length() - 1 ? repoPath.substring(slash + 1) : String.valueOf(repoPath);
    }
}
