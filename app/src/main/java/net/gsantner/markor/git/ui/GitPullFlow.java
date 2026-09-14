/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import net.gsantner.markor.git.GitAuthor;
import net.gsantner.markor.git.GitConflictMarkers;
import net.gsantner.markor.git.GitCredentialsSource;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitPullStrategy;
import net.gsantner.markor.git.GitRepoInfo;
import net.gsantner.markor.git.GitRepoState;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The pull state machine of the Git tab (roadmap task 5.3): fast-forward first, then the
 * <i>diverged</i> question (rebase or merge), then — when a merge or rebase stops — the
 * <i>Resolving conflicts</i> phase that ends with <i>Mark resolved and commit</i> or <i>Abort</i>.
 * <p>
 * The class holds the state and decides what happens next; it does not know about threads, dialogs or
 * views. Everything that touches the world goes through the {@link Host}: running a git call off the
 * main thread, asking for the author identity, showing a question, opening the commit dialog. The
 * fragment implements the host; the tests implement it with a scripted {@link GitService} and a
 * host that runs work inline.
 * <p>
 * All methods are called on one thread (the main thread in the app). A result arriving for a step
 * the machine is no longer in is ignored, and an event that makes no sense in the current state is
 * ignored too, so a stale dialog button can never corrupt the state.
 * <p>
 * <b>Surviving process death.</b> Nothing here is persisted. What survives is the repository itself:
 * a stopped merge or rebase leaves it in {@link GitRepoState#MERGING} / {@link GitRepoState#REBASING}
 * with the conflicted paths in its index. The fragment reports that on every refresh through
 * {@link #syncWithRepository}, which re-enters (or leaves) the resolving phase accordingly.
 */
public final class GitPullFlow {

    /** Where the machine is. Only {@link #IDLE} allows a new pull. */
    public enum State {
        /** Nothing in progress. */
        IDLE,
        /** A pull (of any strategy) is running. */
        PULLING,
        /** The fast-forward pull found diverged branches; the host shows the rebase / merge question. */
        DIVERGED,
        /** The pull would overwrite uncommitted edits; the host offers <i>Commit first</i>. */
        NEEDS_COMMIT,
        /** The host asks for the author identity before a step that creates commits. */
        ASKING_AUTHOR,
        /** A merge or rebase stopped on conflicts; the banner is up, the user edits files. */
        RESOLVING,
        /** <i>Mark resolved</i> was pressed; the files are being scanned for leftover markers. */
        CHECKING,
        /** The merge commit / rebase continue is running. */
        CONTINUING,
        /** The abort is running. */
        ABORTING
    }

    /** The background steps the machine asks its host to run; the host picks the progress label. */
    public enum Op {
        PULL_FF_ONLY, PULL_REBASE, PULL_MERGE, CHECK_MARKERS, CONTINUE, ABORT
    }

    /** One blocking git call, run by the host off the main thread. */
    public interface Work<T> {
        GitResult<T> run(GitProgress progress) throws Exception;
    }

    /**
     * Everything the machine needs from the outside world. Every method is called on the machine's
     * thread; {@link #run} and {@link #requireAuthor} <b>must</b> call back exactly once, and may do so
     * synchronously.
     */
    public interface Host {
        /**
         * Runs {@code work} off the main thread and delivers its result. A step that could not run or
         * was cancelled is still delivered, as a CANCELLED or FAILED result — never dropped.
         */
        <T> void run(Op op, Work<T> work, GsCallback.a1<GitResult<T>> onResult);

        /** Hands over the author identity for commits, or {@code null} when the user declined. */
        void requireAuthor(GsCallback.a1<GitAuthor> onAuthor);

        /**
         * Local and remote diverged. Show the question; answer with {@link #chooseStrategy} or {@link #cancel}.
         *
         * @param preselected the button to present as the default (REBASE or MERGE)
         */
        void showDiverged(GitPullStrategy preselected);

        /** The pull refused because of uncommitted edits to {@code files}; offer {@link #commitFirst} or {@link #cancel}. */
        void showCommitFirst(List<String> files);

        /** Open the commit dialog; report a successful commit with {@link #onCommitted}. */
        void openCommitDialog();

        /** <i>Mark resolved</i> was refused: these files still contain conflict markers. */
        void showStillConflicted(List<String> files);

        /** The continue ran but conflicts remain (leftover markers, or the next rebased commit conflicts too). */
        void showConflictsRemain(List<String> files);

        /** The state changed; re-render. Called after the machine's fields are updated. */
        void onStateChanged(State previous, State current);

        /**
         * A background step finished and the machine has moved on. The host refreshes the tab and
         * tells the user what happened where the machine did not already show a question or a banner.
         */
        void onFinished(Op op, GitResult<?> result);
    }

    /** Finds the files that still carry markers; replaceable for tests. */
    public interface MarkerScanner {
        List<String> stillConflicted(Collection<String> files) throws IOException;
    }

    private final GitService _git;
    private final File _root;
    private final GitCredentialsSource _credentials;
    private final Host _host;
    private final MarkerScanner _scanner;

    private State _state = State.IDLE;
    private boolean _resolving;
    private GitPullStrategy _strategy = GitPullStrategy.FF_ONLY;
    private GitPullStrategy _preferred = GitPullStrategy.REBASE;
    private GitAuthor _author;
    private GitRepoState _repoState = GitRepoState.NORMAL;
    private final List<String> _conflictFiles = new ArrayList<>();

    /**
     * @param git         the service that runs the operations
     * @param root        working-tree root of the repository
     * @param credentials credentials for the fetch part of every pull
     * @param host        the fragment (or a test double)
     */
    public GitPullFlow(final GitService git, final File root, final GitCredentialsSource credentials, final Host host) {
        this(git, root, credentials, host, files -> GitConflictMarkers.scan(root, files));
    }

    GitPullFlow(final GitService git, final File root, final GitCredentialsSource credentials, final Host host, final MarkerScanner scanner) {
        _git = git;
        _root = root;
        _credentials = credentials == null ? GitCredentialsSource.NONE : credentials;
        _host = host;
        _scanner = scanner;
    }

    // ---------------------------------------------------------------- queries

    public State getState() {
        return _state;
    }

    /**
     * @return {@code true} while the <i>Resolving conflicts</i> banner belongs on screen: from the moment
     * a merge or rebase stopped until it was continued or aborted, including while a step runs
     */
    public boolean isResolving() {
        return _resolving;
    }

    /** @return {@code true} while a background step runs (or the author is being asked) and the buttons should be disabled */
    public boolean isBusy() {
        switch (_state) {
            case PULLING:
            case ASKING_AUTHOR:
            case CHECKING:
            case CONTINUING:
            case ABORTING:
                return true;
            default:
                return false;
        }
    }

    /** @return the conflicted paths shown in the banner (repository-relative), empty outside the resolving phase */
    public List<String> getConflictFiles() {
        return Collections.unmodifiableList(new ArrayList<>(_conflictFiles));
    }

    /** @return what the repository is in the middle of, as far as the machine knows */
    public GitRepoState getRepoState() {
        return _repoState;
    }

    /**
     * @return {@code true} when <i>Mark resolved and commit</i> can finish the operation — a merge or a
     * rebase. For a state this app does not drive (cherry-pick, revert, …) only <i>Abort</i> is offered.
     */
    public boolean canContinue() {
        return _repoState == GitRepoState.MERGING || _repoState == GitRepoState.REBASING;
    }

    /** @return the strategy of the current or last pull attempt */
    public GitPullStrategy getStrategy() {
        return _strategy;
    }

    /**
     * @param preferred the repository's configured pull strategy; REBASE or MERGE become the default
     *                  button of the diverged question, anything else keeps REBASE as the default
     */
    public void setPreferredStrategy(final GitPullStrategy preferred) {
        _preferred = preferred == GitPullStrategy.MERGE ? GitPullStrategy.MERGE : GitPullStrategy.REBASE;
    }

    // ---------------------------------------------------------------- pull

    /**
     * Starts a pull. Allowed from {@link State#IDLE}; also from {@link State#DIVERGED} and
     * {@link State#NEEDS_COMMIT}, whose questions the user may simply have walked away from.
     *
     * @return {@code false} when a step is running and the request was ignored
     */
    public boolean startPull(final GitPullStrategy strategy) {
        if (_state != State.IDLE && _state != State.DIVERGED && _state != State.NEEDS_COMMIT) {
            return false;
        }
        _strategy = strategy == null ? GitPullStrategy.FF_ONLY : strategy;
        _author = null;
        if (_strategy == GitPullStrategy.FF_ONLY) {
            runPull();
        } else {
            askAuthor(State.IDLE, this::runPull);
        }
        return true;
    }

    /** Answer to {@link Host#showDiverged}: integrate with {@code strategy} (REBASE or MERGE). */
    public void chooseStrategy(final GitPullStrategy strategy) {
        if (_state != State.DIVERGED || strategy == null || strategy == GitPullStrategy.FF_ONLY) {
            return;
        }
        _strategy = strategy;
        askAuthor(State.IDLE, this::runPull);
    }

    /** Answer to {@link Host#showDiverged} or {@link Host#showCommitFirst}: do nothing. */
    public void cancel() {
        if (_state == State.DIVERGED || _state == State.NEEDS_COMMIT) {
            setState(State.IDLE);
        }
    }

    /** Answer to {@link Host#showCommitFirst}: open the commit dialog; the pull resumes after {@link #onCommitted}. */
    public void commitFirst() {
        if (_state == State.NEEDS_COMMIT) {
            _host.openCommitDialog();
        }
    }

    /** The commit dialog opened by {@link #commitFirst} made its commit: retry the pull with the same strategy. */
    public void onCommitted() {
        if (_state == State.NEEDS_COMMIT) {
            runPull();
        }
    }

    private void runPull() {
        final GitPullStrategy strategy = _strategy;
        final GitAuthor author = _author;
        setState(State.PULLING);
        _host.run(opFor(strategy),
                progress -> _git.pull(_root, strategy, _credentials, author, progress),
                this::onPullResult);
    }

    private void onPullResult(final GitResult<GitRepoInfo> result) {
        if (_state != State.PULLING) {
            return;
        }
        final Op op = opFor(_strategy);
        switch (result.getKind()) {
            case NON_FAST_FORWARD:
                setState(State.DIVERGED);
                _host.showDiverged(_preferred);
                break;
            case CONFLICTS:
                enterResolving(result.getFiles(), _strategy == GitPullStrategy.REBASE ? GitRepoState.REBASING : GitRepoState.MERGING);
                _host.onFinished(op, result);
                break;
            case DIRTY_WORK_TREE:
                setState(State.NEEDS_COMMIT);
                _host.showCommitFirst(result.getFiles());
                break;
            case OK:
            default:
                setState(State.IDLE);
                _host.onFinished(op, result);
                break;
        }
    }

    private static Op opFor(final GitPullStrategy strategy) {
        switch (strategy) {
            case REBASE:
                return Op.PULL_REBASE;
            case MERGE:
                return Op.PULL_MERGE;
            case FF_ONLY:
            default:
                return Op.PULL_FF_ONLY;
        }
    }

    // ---------------------------------------------------------------- resolving

    /**
     * <i>Mark resolved and commit</i>. Scans the banner's files for leftover markers first; when any
     * remain the host names them and nothing is changed, otherwise the merge is committed or the rebase
     * continued.
     *
     * @param message commit message for a merge commit, {@code null} for git's prepared one; ignored by a rebase
     */
    public void markResolved(final String message) {
        if (_state != State.RESOLVING || !canContinue()) {
            return;
        }
        final List<String> files = new ArrayList<>(_conflictFiles);
        setState(State.CHECKING);
        _host.run(Op.CHECK_MARKERS,
                progress -> GitResult.ok(_scanner.stillConflicted(files)),
                (GitResult<List<String>> result) -> onMarkersChecked(message, result));
    }

    private void onMarkersChecked(final String message, final GitResult<List<String>> result) {
        if (_state != State.CHECKING) {
            return;
        }
        if (!result.isOk()) {
            setState(State.RESOLVING);
            _host.onFinished(Op.CHECK_MARKERS, result);
            return;
        }
        final List<String> stillMarked = result.getValue();
        if (stillMarked != null && !stillMarked.isEmpty()) {
            setState(State.RESOLVING);
            _host.showStillConflicted(stillMarked);
            return;
        }
        askAuthor(State.RESOLVING, () -> runContinue(message));
    }

    private void runContinue(final String message) {
        final GitAuthor author = _author;
        setState(State.CONTINUING);
        _host.run(Op.CONTINUE,
                progress -> _git.continueAfterConflictResolution(_root, message, author, progress),
                this::onContinueResult);
    }

    private void onContinueResult(final GitResult<GitRepoInfo> result) {
        if (_state != State.CONTINUING) {
            return;
        }
        switch (result.getKind()) {
            case OK:
                setIdleFromResolving();
                break;
            case CONFLICTS:
                // Leftover markers the pre-scan did not see, or the next rebased commit conflicts too.
                enterResolving(result.getFiles(), _repoState);
                _host.showConflictsRemain(result.getFiles());
                break;
            default:
                // Keep the banner; the next refresh's syncWithRepository corrects it if the repository moved on.
                setState(State.RESOLVING);
                break;
        }
        _host.onFinished(Op.CONTINUE, result);
    }

    /** <i>Abort</i>: return the repository to the state before the pull. */
    public void abort() {
        if (_state != State.RESOLVING) {
            return;
        }
        setState(State.ABORTING);
        _host.run(Op.ABORT,
                progress -> _git.abortMergeOrRebase(_root, progress),
                this::onAbortResult);
    }

    private void onAbortResult(final GitResult<GitRepoInfo> result) {
        if (_state != State.ABORTING) {
            return;
        }
        if (result.isOk()) {
            setIdleFromResolving();
        } else {
            setState(State.RESOLVING);
        }
        _host.onFinished(Op.ABORT, result);
    }

    /**
     * What the repository itself says, from the fragment's refresh. Enters the resolving phase when a
     * merge or rebase is in progress and the machine is idle (process death, or a stop made by another
     * git client), leaves it when the repository is back to normal (finished elsewhere), and updates
     * the banner's file list. Ignored while a step is running: its result decides.
     *
     * @param state         {@link GitRepoInfo#getState()}
     * @param conflictFiles the paths {@code status} reports as CONFLICT
     */
    public void syncWithRepository(final GitRepoState state, final List<String> conflictFiles) {
        final GitRepoState repoState = state == null ? GitRepoState.NORMAL : state;
        final List<String> files = conflictFiles == null ? Collections.<String>emptyList() : conflictFiles;
        switch (_state) {
            case IDLE:
            case DIVERGED:
            case NEEDS_COMMIT:
                if (repoState != GitRepoState.NORMAL) {
                    enterResolving(files, repoState);
                }
                break;
            case RESOLVING:
                if (repoState == GitRepoState.NORMAL) {
                    setIdleFromResolving();
                } else if (repoState != _repoState || !files.equals(_conflictFiles)) {
                    enterResolving(files, repoState);
                }
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- internals

    private void enterResolving(final List<String> files, final GitRepoState repoState) {
        final State previous = _state;
        _conflictFiles.clear();
        if (files != null) {
            _conflictFiles.addAll(files);
        }
        _repoState = repoState == null ? GitRepoState.OTHER : repoState;
        _resolving = true;
        _state = State.RESOLVING;
        // Always notify: the file list may have changed even when the state did not.
        _host.onStateChanged(previous, _state);
    }

    private void setIdleFromResolving() {
        _conflictFiles.clear();
        _repoState = GitRepoState.NORMAL;
        _resolving = false;
        setState(State.IDLE);
    }

    private void askAuthor(final State fallback, final Runnable next) {
        setState(State.ASKING_AUTHOR);
        _host.requireAuthor(author -> {
            if (_state != State.ASKING_AUTHOR) {
                return;
            }
            if (author == null) {
                setState(fallback);
                return;
            }
            _author = author;
            next.run();
        });
    }

    private void setState(final State state) {
        if (state == _state) {
            return;
        }
        final State previous = _state;
        _state = state;
        if (state == State.IDLE) {
            _resolving = false;
        }
        _host.onStateChanged(previous, state);
    }

    @Override
    public String toString() {
        return "GitPullFlow{" + _state + ", " + _strategy + (_resolving ? ", resolving " + _repoState + " " + _conflictFiles : "") + '}';
    }
}
