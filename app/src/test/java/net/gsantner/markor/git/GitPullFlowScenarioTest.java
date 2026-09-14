/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static net.gsantner.markor.git.GitTestRepos.ALICE;
import static net.gsantner.markor.git.GitTestRepos.BOB;
import static net.gsantner.markor.git.GitTestRepos.BRANCH;
import static net.gsantner.markor.git.GitTestRepos.commitFile;
import static net.gsantner.markor.git.GitTestRepos.head;
import static net.gsantner.markor.git.GitTestRepos.log;
import static net.gsantner.markor.git.GitTestRepos.read;
import static net.gsantner.markor.git.GitTestRepos.write;
import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.ui.GitPullFlow;
import net.gsantner.markor.git.ui.GitPullFlow.Op;
import net.gsantner.markor.git.ui.GitPullFlow.State;
import net.gsantner.opoc.wrapper.GsCallback;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link GitPullFlow} against real repositories: Alice pushes to a bare {@code file://} remote,
 * Bob's clone is driven through the state machine the way the Git tab drives it. The host runs
 * every step inline with the real {@link JGitService}.
 */
public class GitPullFlowScenarioTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Inline host: the fragment without the views. */
    private static final class InlineHost implements GitPullFlow.Host {
        final List<String> events = new ArrayList<>();
        GitAuthor author = BOB;

        @Override
        public <T> void run(final Op op, final GitPullFlow.Work<T> work, final GsCallback.a1<GitResult<T>> onResult) {
            events.add("run:" + op);
            GitResult<T> result;
            try {
                result = work.run(GitProgress.NONE);
            } catch (Exception e) {
                result = GitResult.failed(e.toString());
            }
            onResult.callback(result);
        }

        @Override
        public void requireAuthor(final GsCallback.a1<GitAuthor> onAuthor) {
            onAuthor.callback(author);
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
        }

        @Override
        public void onFinished(final Op op, final GitResult<?> result) {
            events.add("finished:" + op + ":" + result.getKind());
        }
    }

    private final GitService _git = new JGitService();
    private File _bare;
    private Git _alice;
    private Git _bob;
    private File _bobDir;
    private InlineHost _host;
    private GitPullFlow _flow;

    @Before
    public void setUp() throws Exception {
        _bare = GitTestRepos.newBareRemote(tmp, "origin.git");
        GitTestRepos.seedRemote(tmp, _bare);
        final File aliceDir = tmp.newFolder("alice");
        _bobDir = tmp.newFolder("bob");
        _alice = GitTestRepos.cloneInto(_bare, aliceDir);
        _bob = GitTestRepos.cloneInto(_bare, _bobDir);
        _host = new InlineHost();
        _flow = new GitPullFlow(_git, _bobDir, GitCredentialsSource.NONE, _host);
    }

    @After
    public void tearDown() {
        _alice.close();
        _bob.close();
    }

    private void alicePushes(final String path, final String content, final String message) throws Exception {
        commitFile(_alice, path, content, message, ALICE);
        _alice.push().setRemote("origin").call();
    }

    /** What the fragment does on every refresh: open + status, then tell the machine. */
    private void refreshLikeTheFragment(final GitPullFlow flow) {
        final GitRepoInfo info = _git.open(_bobDir, GitProgress.NONE).getValue();
        final List<String> conflicts = new ArrayList<>();
        for (final GitStatusEntry entry : _git.status(_bobDir, GitProgress.NONE).getValue()) {
            if (entry.getKind() == GitStatusEntry.Kind.CONFLICT) {
                conflicts.add(entry.getPath());
            }
        }
        flow.syncWithRepository(info.getState(), conflicts);
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    public void fastForwardPullNeedsNoQuestion() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");

        _flow.startPull(GitPullStrategy.FF_ONLY);

        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_host.events).containsExactly("run:PULL_FF_ONLY", "finished:PULL_FF_ONLY:OK");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n- call mom\n");
    }

    @Test
    public void divergedThenRebaseLinearizesWithoutConflicts() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        final ObjectId remoteHead = head(_alice);
        commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);

        _flow.startPull(GitPullStrategy.FF_ONLY);
        assertThat(_flow.getState()).isEqualTo(State.DIVERGED);
        assertThat(_host.events).containsExactly("run:PULL_FF_ONLY", "showDiverged:REBASE");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);

        _flow.chooseStrategy(GitPullStrategy.REBASE);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_host.events).endsWith("run:PULL_REBASE", "finished:PULL_REBASE:OK");
        final List<RevCommit> history = log(_bob);
        assertThat(history.get(0).getFullMessage()).isEqualTo("Bob notes");
        assertThat(history.get(0).getParent(0).getId()).isEqualTo(remoteHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n- call mom\n");
    }

    @Test
    public void divergedThenMergeCreatesAMergeCommitByTheAuthor() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.MERGE);

        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        final RevCommit merge = log(_bob).get(0);
        assertThat(merge.getParentCount()).isEqualTo(2);
        assertThat(merge.getCommitterIdent().getName()).isEqualTo("Bob");
    }

    // ---------------------------------------------------------------- conflicts: rebase

    @Test
    public void divergedRebaseConflictResolveContinue() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        final ObjectId remoteHead = head(_alice);
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);

        // banner state
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.isResolving()).isTrue();
        assertThat(_flow.getConflictFiles()).containsExactly("todo.txt");
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.REBASING);
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.REBASING);
        assertThat(_git.status(_bobDir, GitProgress.NONE).getValue())
                .containsExactly(new GitStatusEntry("todo.txt", GitStatusEntry.Kind.CONFLICT));
        assertThat(read(_bobDir, "todo.txt")).contains("<<<<<<< ").contains(">>>>>>> ");

        // Mark resolved too early: refused, file named, nothing changed
        _host.events.clear();
        _flow.markResolved(null);
        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "showStillConflicted:[todo.txt]");
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.REBASING);

        // the user edits the file in the editor, then marks it resolved
        write(_bobDir, "todo.txt", "- buy oat and soy milk\n");
        _host.events.clear();
        _flow.markResolved(null);
        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "run:CONTINUE", "finished:CONTINUE:OK");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();

        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        assertThat(_bob.status().call().isClean()).isTrue();
        final List<RevCommit> history = log(_bob);
        assertThat(history.get(0).getFullMessage()).isEqualTo("Bob edits milk");
        assertThat(history.get(0).getParent(0).getId()).isEqualTo(remoteHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy oat and soy milk\n");
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 1, 0));
    }

    @Test
    public void rebaseWithTwoConflictingCommitsAsksTwice() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        commitFile(_bob, "todo.txt", "- buy soy milk\n- and bread\n", "Bob adds bread", BOB);

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);

        write(_bobDir, "todo.txt", "- buy oat and soy milk\n");
        _host.events.clear();
        _flow.markResolved(null);
        // the second of Bob's commits conflicts as well
        assertThat(_host.events).containsExactly("run:CHECK_MARKERS", "run:CONTINUE", "showConflictsRemain:[todo.txt]", "finished:CONTINUE:CONFLICTS");
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.getConflictFiles()).containsExactly("todo.txt");
        assertThat(read(_bobDir, "todo.txt")).contains("<<<<<<< ");

        write(_bobDir, "todo.txt", "- buy oat and soy milk\n- and bread\n");
        _flow.markResolved(null);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        final List<RevCommit> history = log(_bob);
        assertThat(history.get(0).getFullMessage()).isEqualTo("Bob adds bread");
        assertThat(history.get(1).getFullMessage()).isEqualTo("Bob edits milk");
        assertThat(history.get(2).getFullMessage()).isEqualTo("Alice edits milk");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy oat and soy milk\n- and bread\n");
    }

    @Test
    public void abortAfterRebaseConflictRestoresHeadAndWorkTree() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        final ObjectId bobHead = head(_bob);

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        write(_bobDir, "todo.txt", "half-done edit\n"); // edits made while resolving are discarded by abort

        _host.events.clear();
        _flow.abort();

        assertThat(_host.events).containsExactly("run:ABORT", "finished:ABORT:OK");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
        assertThat(head(_bob)).isEqualTo(bobHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk\n");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        assertThat(_bob.status().call().isClean()).isTrue();
    }

    // ---------------------------------------------------------------- conflicts: merge

    @Test
    public void divergedMergeConflictResolveContinueMakesAMergeCommit() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        final ObjectId remoteHead = head(_alice);
        final RevCommit bobCommit = commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.MERGE);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);
        assertThat(_flow.getRepoState()).isEqualTo(GitRepoState.MERGING);
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.MERGING);

        write(_bobDir, "todo.txt", "- buy oat and soy milk\n");
        _flow.markResolved("Merge the milk");
        assertThat(_flow.getState()).isEqualTo(State.IDLE);

        final RevCommit merge = log(_bob).get(0);
        assertThat(merge.getFullMessage()).isEqualTo("Merge the milk");
        assertThat(merge.getParentCount()).isEqualTo(2);
        assertThat(merge.getParent(0).getId()).isEqualTo(bobCommit.getId());
        assertThat(merge.getParent(1).getId()).isEqualTo(remoteHead);
        assertThat(merge.getAuthorIdent().getName()).isEqualTo("Bob");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy oat and soy milk\n");
        assertThat(_bob.status().call().isClean()).isTrue();
    }

    @Test
    public void abortAfterMergeConflictKeepsUnrelatedEdits() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        final ObjectId bobHead = head(_bob);
        write(_bobDir, "scratch.md", "untracked scratch\n");

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.MERGE);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);

        _flow.abort();
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(head(_bob)).isEqualTo(bobHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk\n");
        assertThat(read(_bobDir, "scratch.md")).isEqualTo("untracked scratch\n");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
    }

    // ---------------------------------------------------------------- dirty work tree

    @Test
    public void uncommittedEditsLeadToCommitFirstThenThePullResumes() throws Exception {
        alicePushes("notes.md", "# Notes\nalice\n", "Alice notes");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        write(_bobDir, "todo.txt", "- buy soy milk\n- uncommitted\n");

        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);

        assertThat(_flow.getState()).isEqualTo(State.NEEDS_COMMIT);
        assertThat(_host.events).endsWith("showCommitFirst:[todo.txt]");
        assertThat(read(_bobDir, "todo.txt")).as("nothing touched").isEqualTo("- buy soy milk\n- uncommitted\n");

        _flow.commitFirst();
        assertThat(_host.events).endsWith("openCommitDialog");
        // the commit dialog commits (the fragment's CommitDialog does this through GitService.commit)
        assertThat(_git.commit(_bobDir, "Uncommitted", null, BOB, GitProgress.NONE).isOk()).isTrue();
        _flow.onCommitted();

        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_host.events).endsWith("run:PULL_REBASE", "finished:PULL_REBASE:OK");
        final List<RevCommit> history = log(_bob);
        assertThat(history.get(0).getFullMessage()).isEqualTo("Uncommitted");
        assertThat(history.get(2).getFullMessage()).isEqualTo("Alice notes");
        assertThat(read(_bobDir, "notes.md")).isEqualTo("# Notes\nalice\n");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk\n- uncommitted\n");
    }

    // ---------------------------------------------------------------- process death

    @Test
    public void aFreshMachineReentersTheBannerFromTheRepositoryState() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.REBASE);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);

        // process death: a new fragment, a new machine, one refresh
        final InlineHost host2 = new InlineHost();
        final GitPullFlow revived = new GitPullFlow(_git, _bobDir, GitCredentialsSource.NONE, host2);
        assertThat(revived.getState()).isEqualTo(State.IDLE);
        refreshLikeTheFragment(revived);

        assertThat(revived.getState()).isEqualTo(State.RESOLVING);
        assertThat(revived.isResolving()).isTrue();
        assertThat(revived.getConflictFiles()).containsExactly("todo.txt");
        assertThat(revived.getRepoState()).isEqualTo(GitRepoState.REBASING);
        assertThat(revived.canContinue()).isTrue();

        // and it can finish the job
        write(_bobDir, "todo.txt", "- buy oat and soy milk\n");
        revived.markResolved(null);
        assertThat(revived.getState()).isEqualTo(State.IDLE);
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        assertThat(log(_bob).get(0).getFullMessage()).isEqualTo("Bob edits milk");

        // the old machine learns the same from its next refresh
        refreshLikeTheFragment(_flow);
        assertThat(_flow.getState()).isEqualTo(State.IDLE);
    }

    @Test
    public void aMergeFinishedByAnotherClientLeavesTheBanner() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        _flow.startPull(GitPullStrategy.FF_ONLY);
        _flow.chooseStrategy(GitPullStrategy.MERGE);
        assertThat(_flow.getState()).isEqualTo(State.RESOLVING);

        // a desktop git aborts the merge behind the app's back
        assertThat(_git.abortMergeOrRebase(_bobDir, GitProgress.NONE).isOk()).isTrue();
        refreshLikeTheFragment(_flow);

        assertThat(_flow.getState()).isEqualTo(State.IDLE);
        assertThat(_flow.isResolving()).isFalse();
    }
}
