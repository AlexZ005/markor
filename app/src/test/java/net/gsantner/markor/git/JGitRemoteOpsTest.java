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
import static net.gsantner.markor.git.GitTestRepos.fileUrl;
import static net.gsantner.markor.git.GitTestRepos.head;
import static net.gsantner.markor.git.GitTestRepos.log;
import static net.gsantner.markor.git.GitTestRepos.read;
import static net.gsantner.markor.git.GitTestRepos.write;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;

/**
 * Remote operations (task 2.3) against a bare {@code file://} remote seeded with one commit.
 * Two clones, "alice" and "bob", play the two devices. Fixtures use JGit directly; the code under
 * test is reached through the {@link GitService} facade.
 */
public class JGitRemoteOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();
    private File _bare;
    private Git _alice;
    private Git _bob;
    private File _aliceDir;
    private File _bobDir;

    @Before
    public void setUp() throws Exception {
        _bare = GitTestRepos.newBareRemote(tmp, "origin.git");
        GitTestRepos.seedRemote(tmp, _bare);
        _aliceDir = tmp.newFolder("alice");
        _bobDir = tmp.newFolder("bob");
        _alice = GitTestRepos.cloneInto(_bare, _aliceDir);
        _bob = GitTestRepos.cloneInto(_bare, _bobDir);
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

    private static void assertOk(final GitResult<?> r) {
        assertThat(r.isOk()).as(r.toString()).isTrue();
    }

    // ------------------------------------------------------------------ clone

    @Test
    public void cloneCreatesWorkingCopyWithUpstream() throws Exception {
        final File target = new File(tmp.getRoot(), "cloned");
        final GitTestRepos.RecordingProgress progress = new GitTestRepos.RecordingProgress();
        final GitResult<GitRepoInfo> r = _git.clone(fileUrl(_bare), target, GitCredentialsSource.NONE, progress);
        assertOk(r);
        final GitRepoInfo info = r.getValue();
        assertThat(info.getWorkTree().getCanonicalFile()).isEqualTo(target.getCanonicalFile());
        assertThat(info.getBranch()).isEqualTo(BRANCH);
        assertThat(info.getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(info.getRemoteName()).isEqualTo("origin");
        assertThat(info.getRemoteUrl()).isEqualTo(fileUrl(_bare));
        assertThat(info.getUpstream()).isEqualTo("origin/" + BRANCH);
        assertThat(info.getLastFetchEpochMillis()).isGreaterThan(0L);
        assertThat(read(target, "todo.txt")).isEqualTo("- buy milk\n");
        assertThat(progress.tasks).isNotEmpty();
        // every begin is followed by its end before the next begin
        for (int i = 0; i < progress.events.size(); i += 2) {
            assertThat(progress.events.get(i)).as(progress.events.toString()).startsWith("begin:");
            assertThat(progress.events.get(i + 1)).as(progress.events.toString()).isEqualTo("end:" + progress.events.get(i).substring(6));
        }
        assertThat(progress.ends).as(progress.events.toString()).isEqualTo(progress.tasks.size());
        assertThat(_git.aheadBehind(target, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));
    }

    @Test
    public void cloneRefusesBadTargetsAndPasswordUrls() throws Exception {
        final File nonEmpty = tmp.newFolder("nonempty");
        write(nonEmpty, "x.txt", "x");
        final GitResult<GitRepoInfo> r1 = _git.clone(fileUrl(_bare), nonEmpty, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(r1.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(r1.getMessage()).contains("not empty");
        assertThat(read(nonEmpty, "x.txt")).isEqualTo("x");

        final GitResult<GitRepoInfo> r2 = _git.clone("https://alice:tok3n@example.com/x.git", new File(tmp.getRoot(), "pw"), GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(r2.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(r2.getMessage()).contains("password").doesNotContain("tok3n");
        assertThat(new File(tmp.getRoot(), "pw")).doesNotExist();

        assertThat(_git.clone("", new File(tmp.getRoot(), "e"), GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(_git.clone("::not a url::", new File(tmp.getRoot(), "e"), GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);

        final GitResult<GitRepoInfo> r3 = _git.clone(fileUrl(new File(tmp.getRoot(), "does-not-exist.git")), new File(tmp.getRoot(), "missing"), GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(r3.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(new File(tmp.getRoot(), "missing")).doesNotExist();
    }

    @Test
    public void cloneCancelledLeavesNoFolder() throws Exception {
        final File target = new File(tmp.getRoot(), "cancelled");
        final GitTestRepos.RecordingProgress progress = new GitTestRepos.RecordingProgress();
        progress.cancelOnFirstCallback = true;
        final GitResult<GitRepoInfo> r = _git.clone(fileUrl(_bare), target, GitCredentialsSource.NONE, progress);
        assertThat(r.getKind()).as(r.toString()).isEqualTo(GitResult.Kind.CANCELLED);
        assertThat(target).doesNotExist();

        progress.cancelOnFirstCallback = false;
        progress.cancelled = true;
        assertThat(_git.clone(fileUrl(_bare), target, GitCredentialsSource.NONE, progress).getKind()).isEqualTo(GitResult.Kind.CANCELLED);
        assertThat(target).doesNotExist();
    }

    // ------------------------------------------------------------------ fetch / ahead-behind

    @Test
    public void fetchUpdatesAheadBehindWithoutTouchingTheWorkTree() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));

        final GitResult<GitAheadBehind> fetched = _git.fetch(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertOk(fetched);
        assertThat(fetched.getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 1));
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n");

        commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 1, 1));
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getLastFetchEpochMillis()).isGreaterThan(0L);
    }

    // ------------------------------------------------------------------ pull

    @Test
    public void pullFastForward() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        final ObjectId remoteHead = head(_alice);

        final GitResult<GitRepoInfo> r = _git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertOk(r);
        assertThat(r.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(head(_bob)).isEqualTo(remoteHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n- call mom\n");
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));

        // pulling again is a no-op
        assertOk(_git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE));
        assertThat(head(_bob)).isEqualTo(remoteHead);
    }

    @Test
    public void pullFfOnlyOnDivergedIsNonFastForwardAndChangesNothing() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);
        final ObjectId bobHead = head(_bob);

        final GitResult<GitRepoInfo> r = _git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(r.getKind()).as(r.toString()).isEqualTo(GitResult.Kind.NON_FAST_FORWARD);
        assertThat(head(_bob)).isEqualTo(bobHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        // the fetch part did happen
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 1, 1));
    }

    @Test
    public void pullRebaseOnDivergedLinearizesHistory() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        final ObjectId remoteHead = head(_alice);
        final RevCommit bobCommit = commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);

        final GitResult<GitRepoInfo> r = _git.pull(_bobDir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertOk(r);
        assertThat(r.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        final List<RevCommit> history = log(_bob);
        assertThat(history.get(0).getFullMessage()).isEqualTo("Bob notes");
        assertThat(history.get(0).getId()).isNotEqualTo(bobCommit.getId());
        assertThat(history.get(0).getParentCount()).isEqualTo(1);
        assertThat(history.get(0).getParent(0).getId()).isEqualTo(remoteHead);
        assertThat(history.get(0).getAuthorIdent().getName()).isEqualTo("Bob");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n- call mom\n");
        assertThat(read(_bobDir, "notes.md")).isEqualTo("# Notes\nbob\n");
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 1, 0));
    }

    @Test
    public void pullMergeOnDivergedCreatesMergeCommitWithGivenCommitter() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        final ObjectId remoteHead = head(_alice);
        final RevCommit bobCommit = commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);

        final GitResult<GitRepoInfo> r = _git.pull(_bobDir, GitPullStrategy.MERGE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertOk(r);
        final RevCommit merge = log(_bob).get(0);
        assertThat(merge.getParentCount()).isEqualTo(2);
        assertThat(merge.getParent(0).getId()).isEqualTo(bobCommit.getId());
        assertThat(merge.getParent(1).getId()).isEqualTo(remoteHead);
        assertThat(merge.getCommitterIdent().getName()).isEqualTo("Bob");
        assertThat(merge.getCommitterIdent().getEmailAddress()).isEqualTo("bob@example.com");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy milk\n- call mom\n");
        assertThat(read(_bobDir, "notes.md")).isEqualTo("# Notes\nbob\n");

        // MERGE fast-forwards when it can
        alicePushes("todo.txt", "- buy milk\n- call mom\n- gym\n", "Gym");
        _bob.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("origin/" + BRANCH).call();
        assertOk(_git.pull(_bobDir, GitPullStrategy.MERGE, GitCredentialsSource.NONE, BOB, GitProgress.NONE));
        assertThat(log(_bob).get(0).getParentCount()).isEqualTo(1);
        assertThat(head(_bob)).isEqualTo(head(_alice));
    }

    // ------------------------------------------------------------------ conflicts: merge

    @Test
    public void pullMergeConflictThenResolveAndContinue() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        final ObjectId bobHead = head(_bob);

        final GitResult<GitRepoInfo> r = _git.pull(_bobDir, GitPullStrategy.MERGE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(r.getKind()).as(r.toString()).isEqualTo(GitResult.Kind.CONFLICTS);
        assertThat(r.getFiles()).containsExactly("todo.txt");
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.MERGING);
        assertThat(_bob.status().call().getConflicting()).containsExactly("todo.txt");
        // the Changes list (task 2.2) must show the conflicted file exactly once, as CONFLICT
        final GitResult<List<GitStatusEntry>> status = _git.status(_bobDir, GitProgress.NONE);
        assertOk(status);
        assertThat(status.getValue()).containsExactly(new GitStatusEntry("todo.txt", GitStatusEntry.Kind.CONFLICT));
        // and commit() must refuse while merging
        assertThat(_git.commit(_bobDir, "x", null, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        final String conflicted = read(_bobDir, "todo.txt");
        assertThat(conflicted).contains("<<<<<<< ").contains("=======").contains(">>>>>>> ").contains("oat").contains("soy");

        // other operations are blocked while merging
        assertThat(_git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(_git.push(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);

        // markers still present -> refused, nothing changes
        final GitResult<GitRepoInfo> tooEarly = _git.continueAfterConflictResolution(_bobDir, "Merge", BOB, GitProgress.NONE);
        assertThat(tooEarly.getKind()).isEqualTo(GitResult.Kind.CONFLICTS);
        assertThat(tooEarly.getFiles()).containsExactly("todo.txt");
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.MERGING);

        // resolve in "the editor" and continue
        write(_bobDir, "todo.txt", "- buy oat and soy milk\n");
        final GitResult<GitRepoInfo> done = _git.continueAfterConflictResolution(_bobDir, "Merge alice's milk", BOB, GitProgress.NONE);
        assertOk(done);
        assertThat(done.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        assertThat(_bob.status().call().isClean()).isTrue();
        assertThat(_git.status(_bobDir, GitProgress.NONE).getValue()).isEmpty();
        final RevCommit merge = log(_bob).get(0);
        assertThat(merge.getFullMessage()).isEqualTo("Merge alice's milk");
        assertThat(merge.getParentCount()).isEqualTo(2);
        assertThat(merge.getParent(0).getId()).isEqualTo(bobHead);
        assertThat(merge.getParent(1).getId()).isEqualTo(head(_alice));
        assertThat(merge.getCommitterIdent().getName()).isEqualTo("Bob");

        // push the merge, alice fast-forwards to it
        assertOk(_git.push(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE));
        assertOk(_git.pull(_aliceDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, ALICE, GitProgress.NONE));
        assertThat(read(_aliceDir, "todo.txt")).isEqualTo("- buy oat and soy milk\n");
        assertThat(head(_alice)).isEqualTo(head(_bob));
    }

    @Test
    public void continueUsesGitsPreparedMessageWhenNoneGivenAndHandlesDeletedResolution() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        assertThat(_git.pull(_bobDir, GitPullStrategy.MERGE, GitCredentialsSource.NONE, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.CONFLICTS);

        // resolution: delete the file entirely
        assertThat(new File(_bobDir, "todo.txt").delete()).isTrue();
        final GitResult<GitRepoInfo> done = _git.continueAfterConflictResolution(_bobDir, null, BOB, GitProgress.NONE);
        assertOk(done);
        assertThat(_bob.status().call().isClean()).isTrue();
        final RevCommit merge = log(_bob).get(0);
        assertThat(merge.getParentCount()).isEqualTo(2);
        assertThat(merge.getFullMessage()).startsWith("Merge");
        assertThat(new File(_bobDir, "todo.txt")).doesNotExist();
    }

    @Test
    public void abortMergeRestoresFilesAndKeepsUnrelatedEdits() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        alicePushes("journal/day1.md", "new file from alice\n", "Alice journal");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        final ObjectId bobHead = head(_bob);
        // an unrelated, uncommitted edit that the merge does not touch
        write(_bobDir, "notes.md", "# Notes\nunsaved thoughts\n");

        assertThat(_git.pull(_bobDir, GitPullStrategy.MERGE, GitCredentialsSource.NONE, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.CONFLICTS);
        assertThat(new File(_bobDir, "journal/day1.md")).exists();

        final GitResult<GitRepoInfo> aborted = _git.abortMergeOrRebase(_bobDir, GitProgress.NONE);
        assertOk(aborted);
        assertThat(aborted.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        assertThat(head(_bob)).isEqualTo(bobHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk\n");
        assertThat(new File(_bobDir, "journal/day1.md")).doesNotExist();
        assertThat(read(_bobDir, "notes.md")).isEqualTo("# Notes\nunsaved thoughts\n");
        final org.eclipse.jgit.api.Status status = _bob.status().call();
        assertThat(status.getConflicting()).isEmpty();
        assertThat(status.getModified()).containsExactly("notes.md");
        assertThat(status.getAdded()).isEmpty();
        assertThat(status.getChanged()).isEmpty();

        assertThat(_git.abortMergeOrRebase(_bobDir, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(_git.continueAfterConflictResolution(_bobDir, null, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
    }

    // ------------------------------------------------------------------ conflicts: rebase

    @Test
    public void pullRebaseConflictThenContinue() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        final ObjectId remoteHead = head(_alice);
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);

        final GitResult<GitRepoInfo> r = _git.pull(_bobDir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(r.getKind()).as(r.toString()).isEqualTo(GitResult.Kind.CONFLICTS);
        assertThat(r.getFiles()).containsExactly("todo.txt");
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.REBASING);
        assertThat(_bob.status().call().getConflicting()).containsExactly("todo.txt");
        assertThat(_git.status(_bobDir, GitProgress.NONE).getValue()).containsExactly(new GitStatusEntry("todo.txt", GitStatusEntry.Kind.CONFLICT));
        assertThat(read(_bobDir, "todo.txt")).contains("<<<<<<< ").contains(">>>>>>> ");

        assertThat(_git.continueAfterConflictResolution(_bobDir, null, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.CONFLICTS);

        write(_bobDir, "todo.txt", "- buy oat and soy milk\n");
        final GitResult<GitRepoInfo> done = _git.continueAfterConflictResolution(_bobDir, "ignored for rebase", BOB, GitProgress.NONE);
        assertOk(done);
        assertThat(done.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(_bob.status().call().isClean()).isTrue();
        final List<RevCommit> history = log(_bob);
        assertThat(history.get(0).getFullMessage()).isEqualTo("Bob edits milk");
        assertThat(history.get(0).getParentCount()).isEqualTo(1);
        assertThat(history.get(0).getParent(0).getId()).isEqualTo(remoteHead);
        assertThat(history.get(0).getCommitterIdent().getName()).isEqualTo("Bob");
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy oat and soy milk\n");
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 1, 0));
        assertOk(_git.push(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE));
    }

    @Test
    public void rebaseResolvedToUpstreamVersionSkipsTheEmptyCommit() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        assertThat(_git.pull(_bobDir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.CONFLICTS);

        write(_bobDir, "todo.txt", "- buy oat milk\n"); // take theirs
        final GitResult<GitRepoInfo> done = _git.continueAfterConflictResolution(_bobDir, null, BOB, GitProgress.NONE);
        assertOk(done);
        assertThat(done.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(head(_bob)).isEqualTo(head(_alice));
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));
    }

    @Test
    public void abortRebaseRestoresHead() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        commitFile(_bob, "todo.txt", "- buy soy milk\n", "Bob edits milk", BOB);
        final ObjectId bobHead = head(_bob);
        assertThat(_git.pull(_bobDir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.CONFLICTS);

        final GitResult<GitRepoInfo> aborted = _git.abortMergeOrRebase(_bobDir, GitProgress.NONE);
        assertOk(aborted);
        assertThat(aborted.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(aborted.getValue().getBranch()).isEqualTo(BRANCH);
        assertThat(head(_bob)).isEqualTo(bobHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk\n");
        assertThat(_bob.status().call().isClean()).isTrue();
    }

    // ------------------------------------------------------------------ dirty work tree

    @Test
    public void pullRefusesToOverwriteUncommittedChanges() throws Exception {
        alicePushes("todo.txt", "- buy oat milk\n", "Alice edits milk");
        write(_bobDir, "todo.txt", "- buy soy milk (unsaved)\n");
        final ObjectId bobHead = head(_bob);

        final GitResult<GitRepoInfo> ff = _git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(ff.getKind()).as(ff.toString()).isEqualTo(GitResult.Kind.DIRTY_WORK_TREE);
        assertThat(ff.getFiles()).containsExactly("todo.txt");
        assertThat(head(_bob)).isEqualTo(bobHead);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk (unsaved)\n");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);

        // diverged + dirty: rebase and merge refuse too
        commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);
        final GitResult<GitRepoInfo> rebase = _git.pull(_bobDir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(rebase.getKind()).as(rebase.toString()).isEqualTo(GitResult.Kind.DIRTY_WORK_TREE);
        assertThat(rebase.getFiles()).containsExactly("todo.txt");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);

        final GitResult<GitRepoInfo> merge = _git.pull(_bobDir, GitPullStrategy.MERGE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(merge.getKind()).as(merge.toString()).isEqualTo(GitResult.Kind.DIRTY_WORK_TREE);
        assertThat(merge.getFiles()).containsExactly("todo.txt");
        assertThat(_bob.getRepository().getRepositoryState()).isEqualTo(RepositoryState.SAFE);
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy soy milk (unsaved)\n");

        // an untouched dirty file does not block a fast-forward of other files
        write(_bobDir, "todo.txt", "- buy milk\n"); // back to committed content
        write(_bobDir, "scratch.md", "untracked scratch\n");
        write(_bobDir, "notes.md", "# Notes\nbob\nmore unsaved\n");
        _bob.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT).setRef("HEAD~1").call();
        _bob.reset().setRef("HEAD").addPath("notes.md").call();
        assertThat(_bob.status().call().getModified()).containsExactly("notes.md");
        assertOk(_git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE));
        assertThat(read(_bobDir, "todo.txt")).isEqualTo("- buy oat milk\n");
        assertThat(read(_bobDir, "notes.md")).isEqualTo("# Notes\nbob\nmore unsaved\n");
        assertThat(read(_bobDir, "scratch.md")).isEqualTo("untracked scratch\n");
    }

    // ------------------------------------------------------------------ push

    @Test
    public void pushRejectedNonFastForwardThenRebaseAndPush() throws Exception {
        alicePushes("todo.txt", "- buy milk\n- call mom\n", "Add call");
        commitFile(_bob, "notes.md", "# Notes\nbob\n", "Bob notes", BOB);

        final GitResult<Void> rejected = _git.push(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(rejected.getKind()).as(rejected.toString()).isEqualTo(GitResult.Kind.NON_FAST_FORWARD);
        assertThat(rejected.getMessage()).containsIgnoringCase("pull");

        assertOk(_git.pull(_bobDir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE));
        final GitTestRepos.RecordingProgress progress = new GitTestRepos.RecordingProgress();
        assertOk(_git.push(_bobDir, GitCredentialsSource.NONE, progress));
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));
        assertOk(_git.push(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE)); // up to date

        assertOk(_git.pull(_aliceDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, ALICE, GitProgress.NONE));
        assertThat(read(_aliceDir, "notes.md")).isEqualTo("# Notes\nbob\n");
        assertThat(head(_alice)).isEqualTo(head(_bob));
    }

    @Test
    public void pushSetsUpstreamOnNewRepository() throws Exception {
        final File emptyBare = GitTestRepos.newBareRemote(tmp, "fresh.git");
        final File dir = tmp.newFolder("fresh");
        try (Git git = Git.init().setDirectory(dir).setInitialBranch(BRANCH).call()) {
            final GitResult<Void> noRemote = _git.push(dir, GitCredentialsSource.NONE, GitProgress.NONE);
            assertThat(noRemote.getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(noRemote.getMessage()).containsIgnoringCase("remote");
            assertThat(_git.fetch(dir, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(_git.pull(dir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, null, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);

            assertOk(_git.setRemoteUrl(dir, fileUrl(emptyBare), GitProgress.NONE));
            assertThat(_git.open(dir, GitProgress.NONE).getValue().getRemoteUrl()).isEqualTo(fileUrl(emptyBare));
            assertThat(_git.push(dir, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED); // no commits yet

            commitFile(git, "todo.txt", "- start\n", "First", BOB);
            assertThat(_git.aheadBehind(dir, GitProgress.NONE).getValue().hasUpstream()).isFalse();
            assertThat(_git.open(dir, GitProgress.NONE).getValue().getUpstream()).isNull();

            assertOk(_git.push(dir, GitCredentialsSource.NONE, GitProgress.NONE));
            assertThat(_git.open(dir, GitProgress.NONE).getValue().getUpstream()).isEqualTo("origin/" + BRANCH);
            assertThat(_git.aheadBehind(dir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));
            try (Git remote = Git.open(emptyBare)) {
                assertThat(remote.getRepository().resolve("refs/heads/" + BRANCH)).isEqualTo(head(git));
            }
            assertThat(_git.lsRemote(fileUrl(emptyBare), GitCredentialsSource.NONE, GitProgress.NONE).getValue()).containsExactly(BRANCH);

            // setRemoteUrl replaces an existing origin
            assertOk(_git.setRemoteUrl(dir, fileUrl(_bare), GitProgress.NONE));
            assertThat(_git.open(dir, GitProgress.NONE).getValue().getRemoteUrl()).isEqualTo(fileUrl(_bare));
            assertThat(_git.setRemoteUrl(dir, "https://u:pw@h/x.git", GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(_git.open(dir, GitProgress.NONE).getValue().getRemoteUrl()).isEqualTo(fileUrl(_bare));
        }
    }

    @Test
    public void pullIntoEmptyRepositoryFastForwardsAndRecordsUpstream() throws Exception {
        final File dir = tmp.newFolder("empty");
        try (Git git = Git.init().setDirectory(dir).setInitialBranch(BRANCH).call()) {
            assertOk(_git.setRemoteUrl(dir, fileUrl(_bare), GitProgress.NONE));
            final GitResult<GitAheadBehind> fetched = _git.fetch(dir, GitCredentialsSource.NONE, GitProgress.NONE);
            assertOk(fetched);
            assertThat(fetched.getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 1));

            final GitResult<GitRepoInfo> pulled = _git.pull(dir, GitPullStrategy.REBASE, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
            assertOk(pulled);
            assertThat(pulled.getValue().isEmpty()).isFalse();
            assertThat(pulled.getValue().getBranch()).isEqualTo(BRANCH);
            assertThat(pulled.getValue().getUpstream()).isEqualTo("origin/" + BRANCH);
            assertThat(head(git)).isEqualTo(head(_alice));
            assertThat(read(dir, "todo.txt")).isEqualTo("- buy milk\n");
            assertThat(_git.aheadBehind(dir, GitProgress.NONE).getValue()).isEqualTo(new GitAheadBehind("origin/" + BRANCH, 0, 0));
        }
    }

    // ------------------------------------------------------------------ ls-remote and misc errors

    @Test
    public void lsRemoteListsBranchesAndReportsErrors() throws Exception {
        final GitResult<List<String>> ok = _git.lsRemote(fileUrl(_bare), GitCredentialsSource.NONE, GitProgress.NONE);
        assertOk(ok);
        assertThat(ok.getValue()).containsExactly(BRANCH);

        final GitResult<List<String>> missing = _git.lsRemote(fileUrl(new File(tmp.getRoot(), "nope.git")), GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(missing.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(missing.getMessage()).isNotEmpty();
        assertThat(_git.lsRemote("https://u:pw@h/x.git", GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(_git.lsRemote(" ", GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
    }

    @Test
    public void operationsOutsideRepositoryAreNotARepo() throws Exception {
        final File plain = tmp.newFolder("plain");
        assertThat(_git.fetch(plain, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.pull(plain, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, null, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.push(plain, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.aheadBehind(plain, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.abortMergeOrRebase(plain, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.continueAfterConflictResolution(plain, null, null, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.setRemoteUrl(plain, fileUrl(_bare), GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    @Test
    public void detachedHeadIsRefused() throws Exception {
        _bob.checkout().setName(head(_bob).name()).call();
        assertThat(_git.open(_bobDir, GitProgress.NONE).getValue().isDetached()).isTrue();
        final GitResult<GitRepoInfo> pull = _git.pull(_bobDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, BOB, GitProgress.NONE);
        assertThat(pull.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(pull.getMessage()).containsIgnoringCase("detached");
        assertThat(_git.push(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(_git.aheadBehind(_bobDir, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        // fetch still works and reports "no upstream"
        final GitResult<GitAheadBehind> fetched = _git.fetch(_bobDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertOk(fetched);
        assertThat(fetched.getValue().hasUpstream()).isFalse();
    }

    @Test
    public void conflictMarkerDetection() {
        assertThat(JGitRemoteOps.isConflictMarker("<<<<<<< HEAD")).isTrue();
        assertThat(JGitRemoteOps.isConflictMarker(">>>>>>> refs/remotes/origin/main")).isTrue();
        assertThat(JGitRemoteOps.isConflictMarker("<<<<<<<")).isTrue();
        assertThat(JGitRemoteOps.isConflictMarker("=======")).isFalse(); // legitimate markdown setext underline
        assertThat(JGitRemoteOps.isConflictMarker("<<<<<<<<< not seven")).isFalse();
        assertThat(JGitRemoteOps.isConflictMarker(" <<<<<<< indented")).isFalse();
        assertThat(JGitRemoteOps.isConflictMarker("text <<<<<<< inside")).isFalse();
        assertThat(JGitRemoteOps.isConflictMarker("")).isFalse();
    }
}
