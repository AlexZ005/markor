/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The runner is exercised with real worker threads (that is the behaviour under test: one thread
 * per repository) and a direct callback executor standing in for the Android main thread. Timing is
 * made deterministic with latches; every wait has a timeout so a broken runner fails instead of
 * hanging the build.
 */
public class GitTaskRunnerTest {

    private static final long TIMEOUT_SEC = 10;

    /**
     * Runs callbacks inline, like a main-thread handler that is always ready, and records how often
     * a delivery was scheduled - including the ones dropped because the owner was gone.
     */
    private static final class RecordingExecutor implements Executor {
        final AtomicInteger scheduled = new AtomicInteger();
        volatile CountDownLatch latch = new CountDownLatch(0);

        @Override
        public void execute(final Runnable command) {
            scheduled.incrementAndGet();
            try {
                command.run();
            } finally {
                latch.countDown();
            }
        }

        void expect(final int deliveries) {
            latch = new CountDownLatch(deliveries);
        }

        void await() throws InterruptedException {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for " + latch.getCount() + " more result deliveries");
            }
        }
    }

    private RecordingExecutor _callbacks;
    private GitTaskRunner _runner;

    @Before
    public void setUp() {
        _callbacks = new RecordingExecutor();
        _runner = new GitTaskRunner(_callbacks);
    }

    @After
    public void tearDown() {
        _runner.shutdown();
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for a latch");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for a latch");
        }
    }

    @Test
    public void resultOfASuccessfulTaskIsDelivered() throws Exception {
        _callbacks.expect(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> "hello", got::add);
        _callbacks.await();

        assertThat(got).hasSize(1);
        assertThat(got.get(0).isSuccess()).isTrue();
        assertThat(got.get(0).getValue()).isEqualTo("hello");
        assertThat(got.get(0).getError()).isNull();
    }

    @Test
    public void tasksOnOneRepoNeverOverlapAndRunInOrder() throws Exception {
        _callbacks.expect(3);
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final List<Integer> order = new CopyOnWriteArrayList<>();
        final CountDownLatch gate = new CountDownLatch(1);

        for (int i = 0; i < 3; i++) {
            final int index = i;
            _runner.submit("/repo", token -> {
                final int now = concurrent.incrementAndGet();
                maxConcurrent.set(Math.max(maxConcurrent.get(), now));
                if (index == 0) {
                    await(gate); // Hold the lane so the others have to queue
                }
                order.add(index);
                concurrent.decrementAndGet();
                return index;
            }, result -> {
            });
        }

        assertThat(_runner.isBusy("/repo")).isTrue();
        gate.countDown();
        _callbacks.await();

        assertThat(maxConcurrent.get()).isEqualTo(1);
        assertThat(order).containsExactly(0, 1, 2);
        assertThat(_runner.isBusy("/repo")).isFalse();
        assertThat(_runner.isBusy()).isFalse();
    }

    @Test
    public void tasksOnDifferentReposRunInParallel() throws Exception {
        _callbacks.expect(2);
        // Each task waits for the other to have started; this can only finish if both run at once
        final CountDownLatch startedA = new CountDownLatch(1);
        final CountDownLatch startedB = new CountDownLatch(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo-a", token -> {
            startedA.countDown();
            await(startedB);
            return "a";
        }, got::add);
        _runner.submit("/repo-b", token -> {
            startedB.countDown();
            await(startedA);
            return "b";
        }, got::add);

        _callbacks.await();

        assertThat(got).hasSize(2);
        assertThat(got).allMatch(GitTaskResult::isSuccess);
    }

    @Test
    public void isBusyIsPerRepository() throws Exception {
        _callbacks.expect(1);
        final CountDownLatch gate = new CountDownLatch(1);

        _runner.submit("/repo-a", token -> {
            await(gate);
            return null;
        }, result -> {
        });

        assertThat(_runner.isBusy("/repo-a")).isTrue();
        assertThat(_runner.isBusy("/repo-b")).isFalse();
        assertThat(_runner.isBusy()).isTrue();

        gate.countDown();
        _callbacks.await();
        assertThat(_runner.isBusy("/repo-a")).isFalse();
    }

    @Test
    public void repoPathIsNormalizedWhenKeyingTheLane() throws Exception {
        _callbacks.expect(1);
        final CountDownLatch gate = new CountDownLatch(1);

        _runner.submit("/repo/", token -> {
            await(gate);
            return null;
        }, result -> {
        });

        assertThat(_runner.isBusy("  /repo  ")).isTrue();

        gate.countDown();
        _callbacks.await();
    }

    @Test
    public void cancelBeforeStartMeansTheTaskNeverRuns() throws Exception {
        _callbacks.expect(2);
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicBoolean secondRan = new AtomicBoolean(false);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> {
            await(gate);
            return "first";
        }, got::add);
        _runner.submit("/repo", token -> {
            secondRan.set(true);
            return "second";
        }, got::add);

        assertThat(_runner.cancel("/repo")).isEqualTo(2);
        gate.countDown();
        _callbacks.await();

        assertThat(secondRan.get()).isFalse();
        assertThat(got).hasSize(2);
        assertThat(got).allMatch(GitTaskResult::isCancelled);
    }

    @Test
    public void cancelDuringRunIsDeliveredAsCancelledNotSuccess() throws Exception {
        _callbacks.expect(1);
        final CountDownLatch started = new CountDownLatch(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> {
            started.countDown();
            while (!token.isCancelled()) {
                Thread.sleep(1); // Poll like a JGit ProgressMonitor would
            }
            return "finished anyway"; // The runner must not report this as success
        }, got::add);

        await(started);
        assertThat(_runner.cancel("/repo")).isEqualTo(1);
        _callbacks.await();

        assertThat(got).hasSize(1);
        assertThat(got.get(0).isCancelled()).isTrue();
        assertThat(got.get(0).getValue()).isNull();
    }

    @Test
    public void taskThrowingTheCancelExceptionIsCancelledNotAnError() throws Exception {
        _callbacks.expect(1);
        final CountDownLatch started = new CountDownLatch(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.<String>submit("/repo", token -> {
            started.countDown();
            while (true) {
                token.throwIfCancelled();
                Thread.sleep(1);
            }
        }, got::add);

        await(started);
        _runner.cancel("/repo");
        _callbacks.await();

        assertThat(got.get(0).isCancelled()).isTrue();
        assertThat(got.get(0).isError()).isFalse();
    }

    @Test
    public void cancelOnlyAffectsTheGivenRepository() throws Exception {
        _callbacks.expect(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        assertThat(_runner.cancel("/repo-a")).isZero(); // Unknown repo: nothing to cancel
        _runner.submit("/repo-b", token -> {
            assertThat(token.isCancelled()).isFalse();
            return "b";
        }, got::add);
        _runner.cancel("/repo-a");
        _callbacks.await();

        assertThat(got.get(0).isSuccess()).isTrue();
    }

    @Test
    public void tasksSubmittedAfterACancelAreNotCancelled() throws Exception {
        _callbacks.expect(2);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> "first", got::add);
        _runner.cancel("/repo");
        _runner.submit("/repo", token -> "second", got::add);
        _callbacks.await();

        assertThat(got).hasSize(2);
        assertThat(got.get(1).isSuccess()).isTrue();
        assertThat(got.get(1).getValue()).isEqualTo("second");
    }

    @Test
    public void callbackIsDroppedWhenTheOwnerIsGone() throws Exception {
        _callbacks.expect(1);
        final AtomicInteger callbackCalls = new AtomicInteger();

        _runner.submit("/repo", token -> "value", () -> false, result -> callbackCalls.incrementAndGet());
        _callbacks.await();

        assertThat(_callbacks.scheduled.get()).isEqualTo(1); // Delivery was attempted ...
        assertThat(callbackCalls.get()).isZero();            // ... and dropped at the owner check
    }

    @Test
    public void callbackIsDeliveredWhileTheOwnerIsAlive() throws Exception {
        _callbacks.expect(1);
        final AtomicInteger callbackCalls = new AtomicInteger();

        _runner.submit("/repo", token -> "value", () -> true, result -> callbackCalls.incrementAndGet());
        _callbacks.await();

        assertThat(callbackCalls.get()).isEqualTo(1);
    }

    @Test
    public void ownerIsCheckedAtDeliveryTimeNotAtSubmitTime() throws Exception {
        _callbacks.expect(1);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger callbackCalls = new AtomicInteger();

        _runner.submit("/repo", token -> {
            await(gate);
            return "value";
        }, alive::get, result -> callbackCalls.incrementAndGet());

        alive.set(false); // The fragment view goes away while the task runs
        gate.countDown();
        _callbacks.await();

        assertThat(callbackCalls.get()).isZero();
    }

    @Test
    public void exceptionInATaskBecomesAnErrorResult() throws Exception {
        _callbacks.expect(1);
        final IOException boom = new IOException("boom");
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.<String>submit("/repo", token -> {
            throw boom;
        }, got::add);
        _callbacks.await();

        assertThat(got).hasSize(1);
        assertThat(got.get(0).isError()).isTrue();
        assertThat(got.get(0).getError()).isSameAs(boom);
        assertThat(got.get(0).getValue()).isNull();
    }

    @Test
    public void errorInATaskDoesNotKillTheLane() throws Exception {
        _callbacks.expect(2);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.<String>submit("/repo", token -> {
            throw new NoClassDefFoundError("jgit"); // What a missing desugared class looks like
        }, got::add);
        _runner.submit("/repo", token -> "still working", got::add);
        _callbacks.await();

        assertThat(got.get(0).isError()).isTrue();
        assertThat(got.get(0).getError()).isInstanceOf(NoClassDefFoundError.class);
        assertThat(got.get(1).isSuccess()).isTrue();
        assertThat(_runner.isBusy("/repo")).isFalse();
    }

    @Test
    public void aNullCallbackIsAllowed() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);

        _runner.submit("/repo", token -> {
            done.countDown();
            return null;
        }, null);

        await(done);
        assertThat(_callbacks.scheduled.get()).isZero(); // Nothing to deliver
    }

    @Test(expected = IllegalArgumentException.class)
    public void submittingANullTaskIsARejectedProgrammingError() {
        _runner.submit("/repo", null, null);
    }

    @Test
    public void submitAfterShutdownIsDeliveredAsCancelled() throws Exception {
        _runner.shutdown();
        _callbacks.expect(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> "never", got::add);
        _callbacks.await();

        assertThat(got).hasSize(1);
        assertThat(got.get(0).isCancelled()).isTrue();
    }

    @Test
    public void shutdownRepoCancelsThatRepoAndLetsItBeUsedAgain() throws Exception {
        _callbacks.expect(1);
        final CountDownLatch started = new CountDownLatch(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> {
            started.countDown();
            while (!token.isCancelled()) {
                Thread.sleep(1);
            }
            return "x";
        }, got::add);
        await(started);
        _runner.shutdownRepo("/repo");
        _callbacks.await();
        assertThat(got.get(0).isCancelled()).isTrue();

        _callbacks.expect(1);
        got.clear();
        _runner.submit("/repo", token -> "again", got::add);
        _callbacks.await();
        assertThat(got.get(0).isSuccess()).isTrue();
    }

    @Test
    public void cancellingASingleTaskLeavesTheOthersAlone() throws Exception {
        _callbacks.expect(2);
        final CountDownLatch gate = new CountDownLatch(1);
        final List<GitTaskResult<String>> got = new CopyOnWriteArrayList<>();

        _runner.submit("/repo", token -> {
            await(gate);
            return "first";
        }, got::add);
        final GitCancelToken second = _runner.submit("/repo", token -> "second", got::add);

        second.cancel();
        gate.countDown();
        _callbacks.await();

        assertThat(got.get(0).isSuccess()).isTrue();
        assertThat(got.get(0).getValue()).isEqualTo("first");
        assertThat(got.get(1).isCancelled()).isTrue();
    }

    @Test
    public void aCustomExecutorFactoryIsUsedOncePerRepository() throws Exception {
        final List<String> created = Collections.synchronizedList(new java.util.ArrayList<>());
        final GitTaskRunner runner = new GitTaskRunner(_callbacks, repoPath -> {
            created.add(repoPath);
            return java.util.concurrent.Executors.newSingleThreadExecutor();
        });
        try {
            _callbacks.expect(3);
            runner.submit("/repo-a", token -> "1", result -> {
            });
            runner.submit("/repo-a/", token -> "2", result -> {
            });
            runner.submit("/repo-b", token -> "3", result -> {
            });
            _callbacks.await();

            assertThat(created).containsExactly("/repo-a", "/repo-b");
        } finally {
            runner.shutdown();
        }
    }

    @Test
    public void neverTokenCannotBeCancelled() {
        assertThat(GitCancelToken.NEVER.cancel()).isFalse();
        assertThat(GitCancelToken.NEVER.isCancelled()).isFalse();
    }
}
