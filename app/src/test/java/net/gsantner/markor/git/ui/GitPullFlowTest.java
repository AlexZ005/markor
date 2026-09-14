/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.GitAheadBehind;
import net.gsantner.markor.git.GitAuthor;
import net.gsantner.markor.git.GitCommitInfo;
import net.gsantner.markor.git.GitCredentialsSource;
import net.gsantner.markor.git.GitDiff;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitPullStrategy;
import net.gsantner.markor.git.GitRepoInfo;
import net.gsantner.markor.git.GitRepoState;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.markor.git.GitStatusEntry;
import net.gsantner.markor.git.ui.GitPullFlow.Op;
import net.gsantner.markor.git.ui.GitPullFlow.State;
import net.gsantner.opoc.wrapper.GsCallback;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Drives {@link GitPullFlow} through every transition with a {@link GitService} that returns scripted
 * results and a host that runs work inline and records what it was asked to show.
 */
public class GitPullFlowTest {

    private static final File ROOT = new File("/tmp/flow-test-repo");
    private static final GitAuthor BOB = new GitAuthor("Bob", "bob@example.com");
    private static final GitRepoInfo NORMAL_INFO = new GitRepoInfo(ROOT, "main", "0123456789abcdef0123456789abcdef01234567",
            false, GitRepoState.NORMAL, "origin", "https://example.com/r.git", "origin/main", 0);

    /** Records every call; each method pops a scripted result or fails the test with "unscripted". */
    private static final class ScriptedGit implements GitService {
        final List<String> calls = new ArrayList<>();
        final Deque<GitResult<?>> pulls = new ArrayDeque<>();
        final Deque<GitResult<?>> continues = new ArrayDeque<>();
        final Deque<GitResult<?>> aborts = new ArrayDeque<>();

        @SuppressWarnings("unchecked")
        private static <T> GitResult<T> pop(final Deque<GitResult<?>> queue, final String what) {
            final GitResult<?> next = queue.poll();
            if (next == null) {
                throw new AssertionError("unscripted call: " + what);
            }
            return (GitResult<T>) next;
        }

        @Override
        public GitResult<GitRepoInfo> pull(final File repoDir, final GitPullStrategy strategy, final GitCredentialsSource credentials,
                                           final GitAuthor author, final GitProgress progress) {
            calls.add("pull:" + strategy + ":" + (author == null ? "-" : author.getName()));
            return pop(pulls, "pull");
        }

        @Override
        public GitResult<GitRepoInfo> continueAfterConflictResolution(final File repoDir, final String message, final GitAuthor author, final GitProgress progress) {
            calls.add("continue:" + message + ":" + (author == null ? "-" : author.getName()));
            return pop(continues, "continue");
        }

        @Override
        public GitResult<GitRepoInfo> abortMergeOrRebase(final File repoDir, final GitProgress progress) {
            calls.add("abort");
            return pop(aborts, "abort");
        }

        // ---- never called by the flow

        @Override
        public GitResult<GitRepoInfo> open(final File anyPathInsideRepo, final GitProgress progress) {
            throw new AssertionError("open");
        }

        @Override
        public boolean isRepository(final File anyPath) {
            throw new AssertionError("isRepository");
        }

        @Override
        public File findRepositoryRoot(final File anyPath) {
            throw new AssertionError("findRepositoryRoot");
        }

        @Override
        public GitResult<GitRepoInfo> init(final File dir, final GitProgress progress) {
            throw new AssertionError("init");
        }

        @Override
        public GitResult<List<GitStatusEntry>> status(final File repoDir, final GitProgress progress) {
            throw new AssertionError("status");
        }

        @Override
        public GitResult<List<GitCommitInfo>> log(final File repoDir, final int limit, final int skip, final GitProgress progress) {
            throw new AssertionError("log");
        }

        @Override
        public GitResult<GitDiff> diffWorkingTree(final File repoDir, final String path, final GitProgress progress) {
            throw new AssertionError("diffWorkingTree");
        }

        @Override
        public GitResult<GitDiff> diffForCommit(final File repoDir, final String sha, final GitProgress progress) {
            throw new AssertionError("diffForCommit");
        }

        @Override
        public GitResult<GitCommitInfo> commit(final File repoDir, final String message, final Collection<String> paths, final GitAuthor author, final GitProgress progress) {
            throw new AssertionError("commit");
        }

        @Override
        public GitResult<GitRepoInfo> clone(final String url, final File targetDir, final GitCredentialsSource credentials, final GitProgress progress) {
            throw new AssertionError("clone");
        }

        @Override
        public GitResult<GitAheadBehind> fetch(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
            throw new AssertionError("fetch");
        }

        @Override
        public GitResult<Void> push(final File repoDir, final GitCredentialsSource credentials, final GitProgress progress) {
            throw new AssertionError("push");
        }

        @Override
        public GitResult<GitAheadBehind> aheadBehind(final File repoDir, final GitProgress progress) {
            throw new AssertionError("aheadBehind");
        }

        @Override
        public GitResult<Void> setRemoteUrl(final File repoDir, final String url, final GitProgress progress) {
            throw new AssertionError("setRemoteUrl");
        }

        @Override
        public GitResult<List<String>> lsRemote(final String url, final GitCredentialsSource credentials, final GitProgress progress) {
            throw new AssertionError("lsRemote");
        }
    }

    /** Runs work inline (or holds it back when {@link #deferResults} is set) and logs every request. */
    private static final class RecordingHost implements GitPullFlow.Host {
        final List<String> events = new ArrayList<>();
        final List<State> states = new ArrayList<>();
        final Deque<Runnable> deferred = new ArrayDeque<>();
        boolean deferResults;
        GitAuthor author = BOB;
        boolean deferAuthor;
        GsCallback.a1<GitAuthor> pendingAuthor;

        @Override
        public <T> void run(final Op op, final GitPullFlow.Work<T> work, final GsCallback.a1<GitResult<T>> onResult) {
            events.add("run:" + op);
            final Runnable deliver = () -> {
                GitResult<T> result;
                try {
                    result = work.run(GitProgress.NONE);
                } catch (Exception e) {
                    result = GitResult.failed(e.getMessage());
                }
                onResult.callback(result);
            };
            if (deferResults) {
                deferred.add(deliver);
            } else {
                deliver.run();
            }
        }

        void deliverDeferred() {
            final Runnable next = deferred.poll();
            assertThat(next).as("a deferred result to deliver").isNotNull();
            next.run();
        }

        @Override
        public void requireAuthor(final GsCallback.a1<GitAuthor> onAuthor) {
            events.add("requireAuthor");
            if (deferAuthor) {
                pendingAuthor = onAuthor;
            } else {
                onAuthor.callback(author);
            }
        }

        @Override
        public void showDiverged(final GitPullStrategy preselected) {
            events.add("showDiverged:" + preselected);
        }

        @Override
        public void showCommitFirst(final List<String> files) {
            events.add("showCommitFirst:" + files);
        }

        @Override
        public void openCommitDialog() {
            events.add("openCommitDialog");
        }

        @Override
        public void showStillConflicted(final List<String> files) {
            events.add("showStillConflicted:" + files);
        }

        @Override
        public void showConflictsRemain(final List<String> files) {
            events.add("showConflictsRemain:" + files);
        }

        @Override
        public void onStateChanged(final State previous, final State current) {
            states.add(current);
        }

        @Override
        public void onFinished(final Op op, final GitResult<?> result) {
            events.add("finished:" + op + ":" + result.getKind());
        }
    }

    private ScriptedGit _git;
    private RecordingHost _host;
    private List<String> _stillMarked;
    private GitPullFlow _flow;

    @Before
    public void setUp() {
        _git = new ScriptedGit();
        _host = new RecordingHost();
        _stillMarked = new ArrayList<>();
        _flow = new GitPullFlow(_git, ROOT, GitCredentialsSource.NONE, _host, files -> new ArrayList<>(_stillMarked));
    }

    // ---------------------------------------------------------------- plain pulls

    @Test
    public void fastForwardPullEndsIdle() {
        _git.pulls.add(GitResult.ok(NORMAL_INFO));

        assertThat(_flow.startPull(GitPullStrategy.FF_ONLY)).isTrue();

        assertThat(_git.calls).containsExactly("pull:FF_ONLY:-");
        assertThat(_host.events).containsExactly("run:PULL_FF_ONLY", "finished:PULL_FF_ONLY:OK");
        assertThat(_host.states).containsExactly(State.PULLING, State.IDLE);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
    }

    @Test
    public void failuresOfThePullEndIdleAndAreReported() {
        for (final GitResult<GitRepoInfo> failure : Arrays.<GitResult<GitRepoInfo>>asList(
                GitResult.authFailed(null), GitResult.network(null), GitResult.cancelled(), GitResult.failed("x"), GitResult.notARepo(ROOT))) {
            _git.pulls.add(failure);
            _host.events.clear();
            _flow.startPull(GitPullStrategy.FF_ONLY);
            assertThat(_flow.getState()).isEqualTo(State.IDLE);
            assertThat(_host.events).containsExactly("run:PULL_FF_ONLY", "finished:PULL_FF_ONLY:" + failure.getKind());
        }
    }

    @Test
    public void startPullIsRefusedWhileAStepRuns() {
        _host.deferResults = true;
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        assertThat(_flow.getState()).isEqualTo(State.PULLING);
        assertThat(_flow.isBusy()).isTrue();

        assertThat(_flow.startPull(GitPullStrategy.FF_ONLY)).isFalse();
        assertThat(_host.events).as("only one step was submitted").containsExactly("run:PULL_FF_ONLY");

        _host.deliverDeferred();
        assertThat(_git.calls).containsExactly("pull:FF_ONLY:-");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isBusy()).isFalse();
    }

    @Test
    public void aRebaseOrMergePullAsksForTheAuthorFirst() {
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        _flow.startPull(GitPullStrategy.MERGE);
        assertThat(_git.calls).containsExactly("pull:MERGE:Bob");
        assertThat(_host.events).startsWith("requireAuthor", "run:PULL_MERGE");

        _host.author = null;
        _flow.startPull(GitPullStrategy.REBASE);
        assertThat(_git.calls).as("declined author: no pull").hasSize(1);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    // ---------------------------------------------------------------- diverged

    @Test
    public void divergedAsksRebaseOrMergeWithRebaseAsDefault() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);

        assertThat(_flow.getState()).isEqualTo(State.DIVERGED);
        assertThat(_flow.isBusy()).isFalse();
        assertThat(_host.events).containsExactly("run:PULL_FF_ONLY", "showDiverged:REBASE");
    }

    @Test
    public void divergedDefaultFollowsTheRepositorysPullStrategy() {
        _flow.setPreferredStrategy(GitPullStrategy.MERGE);
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        assertThat(_host.events).contains("showDiverged:MERGE");

        _flow.cancel();
        _flow.setPreferredStrategy(GitPullStrategy.FF_ONLY); // not a valid default: falls back to rebase
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        assertThat(_host.events).endsWith("showDiverged:REBASE");
    }

    @Test
    public void chooseRebaseRunsARebasePullWithTheAuthor() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        _flow.startPull(GitPullStrategy.FF_ONLY);

        _flow.chooseStrategy(GitPullStrategy.REBASE);

        assertThat(_git.calls).containsExactly("pull:FF_ONLY:-", "pull:REBASE:Bob");
        assertThat(_host.events).containsExactly("run:PULL_FF_ONLY", "showDiverged:REBASE", "requireAuthor", "run:PULL_REBASE", "finished:PULL_REBASE:OK");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.getStrategy()).isEqualTo(GitPullStrategy.REBASE);
    }

    @Test
    public void chooseMergeRunsAMergePull() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.MERGE);
        assertThat(_git.calls).containsExactly("pull:FF_ONLY:-", "pull:MERGE:Bob");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void cancelOnDivergedGoesBackToIdleWithoutAnotherPull() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.cancel();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_git.calls).hasSize(1);
        assertThat(_host.events).doesNotContain("requireAuthor");
    }

    @Test
    public void decliningTheAuthorAfterChoosingRebaseGoesBackToIdle() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _host.author = null;
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_git.calls).hasSize(1);
    }

    @Test
    public void staleAnswersAreIgnored() {
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        _flow.cancel();
        _flow.commitFirst();
        _flow.onCommitted();
        _flow.markResolved(null);
        _flow.abort();
        assertThat(_git.calls).isEmpty();
        assertThat(_host.events).isEmpty();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);

        // FF_ONLY is not an answer to the diverged question
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.FF_ONLY);
        assertThat(_flow.getState()).isEqualTo(State.DIVERGED);
    }

    @Test
    public void aNewPullMayReplaceAnAbandonedQuestion() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        assertThat(_flow.getState()).isEqualTo(State.DIVERGED);

        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        assertThat(_flow.startPull(GitPullStrategy.FF_ONLY)).isTrue();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_git.calls).hasSize(2);
    }

    // ---------------------------------------------------------------- dirty work tree

    @Test
    public void dirtyWorkTreeOffersCommitFirstAndRetriesTheSamePullAfterTheCommit() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _git.pulls.add(GitResult.dirtyWorkTree(Collections.singletonList("todo.txt")));
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);

        assertThat(_flow.getState()).isEqualTo(State.NEEDS_COMMIT);
        assertThat(_flow.isBusy()).isFalse();
        assertThat(_host.events).endsWith("showCommitFirst:[todo.txt]");

        _flow.commitFirst();
        assertThat(_host.events).endsWith("openCommitDialog");
        assertThat(_flow.getState()).isEqualTo(State.NEEDS_COMMIT);

        _flow.onCommitted();
        assertThat(_git.calls).containsExactly("pull:FF_ONLY:-", "pull:REBASE:Bob", "pull:REBASE:Bob");
        assertThat(_host.events).endsWith("run:PULL_REBASE", "finished:PULL_REBASE:OK");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void cancelOnCommitFirstGoesBackToIdle() {
        _git.pulls.add(GitResult.dirtyWorkTree(Collections.singletonList("todo.txt")));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        assertThat(_flow.getState()).isEqualTo(State.NEEDS_COMMIT);
        _flow.cancel();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_git.calls).hasSize(1);
    }

    // ---------------------------------------------------------------- conflicts

    private void pullIntoRebaseConflict(final String... files) {
        _git.pulls.add(GitResult.nonFastForward(null));
        _git.pulls.add(GitResult.conflicts(Arrays.asList(files)));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        _host.events.clear();
        _git.calls.clear();
    }

    @Test
    public void conflictsEnterTheResolvingPhaseWithTheFiles() {
        _git.pulls.add(GitResult.nonFastForward(null));
        _git.pulls.add(GitResult.conflicts(Arrays.asList("a.md", "b.md")));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.MERGE);

        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.isResolving()).isTrue();
        assertThat(_flow.isBusy()).isFalse();
        assertThat(_flow.getConflictFiles()).containsExactly("a.md", "b.md");
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.MERGING);
        assertThat(_flow.canContinue()).isTrue();
        assertThat(_host.events).endsWith("finished:PULL_MERGE:CONFLICTS");
        assertThat(_host.states).endsWith(State.PULLING, State.RESOLVING);
    }

    @Test
    public void rebaseConflictsRememberTheRebaseState() {
        pullIntoRebaseConflict("todo.txt");
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.REBASING);
        assertThat(_flow.startPull(GitPullStrategy.FF_ONLY)).as("no pull while resolving").isFalse();
    }

    @Test
    public void markResolvedRefusesWhileMarkersRemainAndNamesTheFiles() {
        pullIntoRebaseConflict("a.md", "b.md");
        _stillMarked.add("b.md");

        _flow.markResolved(null);

        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "showStillConflicted:[b.md]");
        assertThat(_git.calls).as("continue must not be called").isEmpty();
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.isResolving()).isTrue();
        assertThat(_flow.getConflictFiles()).containsExactly("a.md", "b.md");
    }

    @Test
    public void markResolvedContinuesWithMessageAndAuthorWhenClean() {
        pullIntoRebaseConflict("a.md");
        _git.continues.add(GitResult.ok(NORMAL_INFO));

        _flow.markResolved("Merge remote changes");

        assertThat(_git.calls).containsExactly("continue:Merge remote changes:Bob");
        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "requireAuthor", "run:CONTINUE", "finished:CONTINUE:OK");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
        assertThat(_flow.getConflictFiles()).isEmpty();
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.NORMAL);
    }

    @Test
    public void decliningTheAuthorOnMarkResolvedKeepsResolving() {
        pullIntoRebaseConflict("a.md");
        _host.author = null;
        _flow.markResolved(null);
        assertThat(_git.calls).isEmpty();
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.isResolving()).isTrue();
    }

    @Test
    public void continueThatHitsMoreConflictsStaysResolvingWithTheNewFiles() {
        pullIntoRebaseConflict("a.md");
        _git.continues.add(GitResult.conflicts(Collections.singletonList("c.md")));

        _flow.markResolved(null);

        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "requireAuthor", "run:CONTINUE",
                "showConflictsRemain:[c.md]", "finished:CONTINUE:CONFLICTS");
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.getConflictFiles()).containsExactly("c.md");
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.REBASING);

        // and the next round works the same
        _git.continues.add(GitResult.ok(NORMAL_INFO));
        _flow.markResolved(null);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void continueFailureKeepsTheBannerUntilTheRepositorySaysOtherwise() {
        pullIntoRebaseConflict("a.md");
        _git.continues.add(GitResult.failed("Nothing to continue"));
        _flow.markResolved(null);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_host.events).endsWith("finished:CONTINUE:FAILED");

        _flow.syncWithRepository(GitRepoState.NORMAL, Collections.<String>emptyList());
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
    }

    @Test
    public void markerScanFailureIsReportedAndKeepsResolving() {
        _flow = new GitPullFlow(_git, ROOT, GitCredentialsSource.NONE, _host, files -> {
            throw new java.io.IOException("disk");
        });
        pullIntoRebaseConflict("a.md");
        _flow.markResolved(null);
        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "finished:CHECK_MARKERS:FAILED");
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
    }

    @Test
    public void abortRunsTheAbortAndEndsIdle() {
        pullIntoRebaseConflict("a.md");
        _git.aborts.add(GitResult.ok(NORMAL_INFO));

        _flow.abort();

        assertThat(_git.calls).containsExactly("abort");
        assertThat(_host.events).containsExactly("run:ABORT", "finished:ABORT:OK");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
        assertThat(_flow.getConflictFiles()).isEmpty();
    }

    @Test
    public void failedAbortKeepsResolving() {
        pullIntoRebaseConflict("a.md");
        _git.aborts.add(GitResult.failed("boom"));
        _flow.abort();
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_host.events).endsWith("finished:ABORT:FAILED");
    }

    @Test
    public void bannerStaysUpWhileStepsRun() {
        pullIntoRebaseConflict("a.md");
        _host.deferResults = true;
        _git.continues.add(GitResult.ok(NORMAL_INFO));

        _flow.markResolved(null);
        assertThat(_flow.getState()).isEqualTo(State.CHECKING);
        assertThat(_flow.isResolving()).isTrue();
        assertThat(_flow.isBusy()).isTrue();
        _host.deliverDeferred(); // scan → author → continue submitted
        assertThat(_flow.getState()).isEqualTo(State.CONTINUING);
        assertThat(_flow.isResolving()).isTrue();
        assertThat(_flow.isBusy()).isTrue();
        assertThat(_flow.startPull(GitPullStrategy.FF_ONLY)).isFalse();
        _flow.abort(); // ignored while continuing
        assertThat(_flow.getState()).isEqualTo(State.CONTINUING);
        _host.deliverDeferred();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
    }

    // ---------------------------------------------------------------- repository sync

    @Test
    public void syncEntersResolvingWhenTheRepositoryIsMergingAfterProcessDeath() {
        _flow.syncWithRepository(GitRepoState.MERGING, Arrays.asList("x.md", "y.md"));

        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.isResolving()).isTrue();
        assertThat(_flow.getConflictFiles()).containsExactly("x.md", "y.md");
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.MERGING);
        assertThat(_flow.canContinue()).isTrue();
        assertThat(_host.states).containsExactly(State.RESOLVING);
    }

    @Test
    public void syncWithAnUndrivenStateOffersAbortOnly() {
        _flow.syncWithRepository(GitRepoState.OTHER, Collections.<String>emptyList());
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.canContinue()).isFalse();
        _flow.markResolved(null);
        assertThat(_host.events).as("mark resolved refused for cherry-pick etc.").isEmpty();
        _git.aborts.add(GitResult.ok(NORMAL_INFO));
        _flow.abort();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void syncLeavesResolvingWhenTheRepositoryIsNormalAgain() {
        pullIntoRebaseConflict("a.md");
        _flow.syncWithRepository(GitRepoState.NORMAL, Collections.<String>emptyList());
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
        assertThat(_flow.getConflictFiles()).isEmpty();
    }

    @Test
    public void syncUpdatesTheFileListWhileResolving() {
        pullIntoRebaseConflict("a.md", "b.md");
        _host.states.clear();
        _flow.syncWithRepository(GitRepoState.REBASING, Collections.singletonList("b.md"));
        assertThat(_flow.getConflictFiles()).containsExactly("b.md");
        assertThat(_host.states).containsExactly(State.RESOLVING);

        _host.states.clear();
        _flow.syncWithRepository(GitRepoState.REBASING, Collections.singletonList("b.md"));
        assertThat(_host.states).as("nothing changed: no re-render").isEmpty();
    }

    @Test
    public void syncIsIgnoredWhileAStepRuns() {
        _host.deferResults = true;
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        _flow.startPull(GitPullStrategy.FF_ONLY);

        _flow.syncWithRepository(GitRepoState.MERGING, Collections.singletonList("x.md"));
        assertThat(_flow.getState()).isEqualTo(State.PULLING);
        assertThat(_flow.isResolving()).isFalse();

        _host.deliverDeferred();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void syncWithNormalWhileIdleDoesNothing() {
        _flow.syncWithRepository(GitRepoState.NORMAL, Collections.<String>emptyList());
        _flow.syncWithRepository(null, null);
        assertThat(_host.states).isEmpty();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void lateResultsForAnAbandonedStepAreIgnored() {
        _host.deferResults = true;
        _git.pulls.add(GitResult.nonFastForward(null));
        _flow.startPull(GitPullStrategy.FF_ONLY);
        final Runnable latePull = _host.deferred.poll();

        // The machine moved on (e.g. the repository turned out to be merging meanwhile)
        _host.deferResults = false;
        _flow.syncWithRepository(GitRepoState.MERGING, Collections.singletonList("m.md")); // ignored: PULLING
        assertThat(latePull).isNotNull();
        latePull.run();
        assertThat(_flow.getState()).isEqualTo(State.DIVERGED);

        // A stale author answer after the question was cancelled
        _host.deferAuthor = true;
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        assertThat(_flow.getState()).isEqualTo(State.ASKING_AUTHOR);
        _flow.syncWithRepository(GitRepoState.NORMAL, null); // ignored while asking
        final GsCallback.a1<GitAuthor> answer = _host.pendingAuthor;
        _host.pendingAuthor = null;
        _git.pulls.add(GitResult.ok(NORMAL_INFO));
        answer.callback(BOB); // runs the rebase pull
        assertThat(_git.calls).containsExactly("pull:FF_ONLY:-", "pull:REBASE:Bob");
    }
}
