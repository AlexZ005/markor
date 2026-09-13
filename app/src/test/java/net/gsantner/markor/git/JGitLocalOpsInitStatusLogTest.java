/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Task 2.2: {@code init}, {@code status} and {@code log} of {@link JGitLocalOps}. */
public class JGitLocalOpsInitStatusLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();

    // ---------------------------------------------------------------- init

    @Test
    public void initCreatesRepositoryAndIsRefusedTheSecondTime() throws Exception {
        final File dir = tmp.newFolder("notes");

        final GitResult<GitRepoInfo> first = _git.init(dir, GitProgress.NONE);
        assertThat(first.isOk()).as(first.toString()).isTrue();
        assertThat(first.getValue().getWorkTree().getCanonicalFile()).isEqualTo(dir.getCanonicalFile());
        assertThat(first.getValue().isEmpty()).isTrue();
        assertThat(first.getValue().getState()).isEqualTo(GitRepoState.NORMAL);
        assertThat(new File(dir, ".git")).isDirectory();
        assertThat(_git.isRepository(dir)).isTrue();

        final GitResult<GitRepoInfo> second = _git.init(dir, GitProgress.NONE);
        assertThat(second.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(second.getMessage()).contains("Already a git repository");
    }

    @Test
    public void initCreatesTheFolderWhenItDoesNotExist() throws Exception {
        final File dir = new File(tmp.getRoot(), "fresh/notes");
        assertThat(dir).doesNotExist();

        final GitResult<GitRepoInfo> r = _git.init(dir, GitProgress.NONE);
        assertThat(r.isOk()).as(r.toString()).isTrue();
        assertThat(_git.isRepository(dir)).isTrue();
    }

    /**
     * {@code git init} inside a subfolder of an existing repository creates a real nested repository,
     * exactly as the command line does; only the folder itself carrying {@code .git} is refused.
     */
    @Test
    public void initInsideAnExistingRepositoryCreatesANestedRepository() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("outer"))) {
            repo.write("a.md", "a\n");
            repo.commitAll("initial");

            final File sub = new File(repo.root(), "sub");
            assertThat(sub.mkdirs()).isTrue();
            final GitResult<GitRepoInfo> r = _git.init(sub, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            assertThat(r.getValue().getWorkTree().getCanonicalFile()).isEqualTo(sub.getCanonicalFile());
        }
    }

    /**
     * A linked worktree or submodule has {@code .git} as a *file* pointing elsewhere. Those are out of
     * scope for now and must be reported as "not a repository" rather than half-work.
     */
    @Test
    public void aLinkedWorktreeIsReportedAsNotARepository() throws Exception {
        final File main = tmp.newFolder("main");
        try (GitTestRepo repo = new GitTestRepo(main)) {
            repo.write("a.md", "a\n");
            repo.commitAll("initial");
        }
        final File linked = new File(tmp.getRoot(), "linked");
        Assume.assumeTrue("needs the git command line to create a linked worktree",
                runGit(main, "worktree", "add", "-b", "side", linked.getAbsolutePath()));
        assertThat(new File(linked, ".git")).isFile();

        assertThat(_git.isRepository(linked)).isFalse();
        assertThat(_git.findRepositoryRoot(linked)).isNull();
        assertThat(_git.status(linked, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(_git.log(linked, 10, 0, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        // init must not silently take over such a folder either
        assertThat(_git.init(linked, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
    }

    @Test
    public void initOnAFileIsRefused() throws Exception {
        final File file = tmp.newFile("not-a-folder.md");
        assertThat(_git.init(file, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
    }

    // ---------------------------------------------------------------- status

    @Test
    public void statusOutsideARepositoryIsNotARepo() throws Exception {
        final GitResult<List<GitStatusEntry>> r = _git.status(tmp.newFolder("plain"), GitProgress.NONE);
        assertThat(r.getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    @Test
    public void statusOfACleanRepositoryIsEmpty() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("clean"))) {
            repo.write("a.md", "a\n");
            repo.commitAll("initial");
            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue()).isEmpty();
        }
    }

    @Test
    public void statusClassifiesModifiedAddedDeletedAndUntracked() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("kinds"))) {
            repo.write("modified.md", "one\n");
            repo.write("deleted.md", "gone\n");
            repo.write("keep.md", "keep\n");
            repo.commitAll("initial");

            repo.write("modified.md", "one\ntwo\n");
            repo.delete("deleted.md");
            repo.write("added.md", "new and staged\n");
            repo.git().add().addFilepattern("added.md").call();
            repo.write("untracked.md", "new and unstaged\n");

            final List<GitStatusEntry> entries = _git.status(repo.root(), GitProgress.NONE).getValue();
            assertThat(entries).containsExactly(
                    new GitStatusEntry("added.md", GitStatusEntry.Kind.ADDED),
                    new GitStatusEntry("deleted.md", GitStatusEntry.Kind.DELETED),
                    new GitStatusEntry("modified.md", GitStatusEntry.Kind.MODIFIED),
                    new GitStatusEntry("untracked.md", GitStatusEntry.Kind.UNTRACKED));
        }
    }

    @Test
    public void statusReportsEachPathOnlyOnceWhenStagedAndChangedAgain() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("twice"))) {
            repo.write("note.md", "one\n");
            repo.commitAll("initial");

            repo.write("note.md", "two\n");
            repo.git().add().addFilepattern("note.md").call(); // staged
            repo.write("note.md", "three\n");                  // and changed again afterwards

            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue())
                    .containsExactly(new GitStatusEntry("note.md", GitStatusEntry.Kind.MODIFIED));
        }
    }

    @Test
    public void statusHonoursGitignoreAndListsNestedPathsWithForwardSlashes() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("ignored"))) {
            repo.write(".gitignore", "*.log\n.app/\n");
            repo.commitAll("initial");

            repo.write("debug.log", "noise\n");
            repo.write(".app/snippets.json", "{}\n");
            repo.write("journal/2026-09-13.md", "today\n");

            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue())
                    .containsExactly(new GitStatusEntry("journal/2026-09-13.md", GitStatusEntry.Kind.UNTRACKED));
        }
    }

    @Test
    public void statusWorksFromANestedPathInsideTheRepository() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("nested"))) {
            final File file = repo.write("journal/entry.md", "hi\n");
            assertThat(_git.status(file, GitProgress.NONE).getValue())
                    .containsExactly(new GitStatusEntry("journal/entry.md", GitStatusEntry.Kind.UNTRACKED));
        }
    }

    @Test
    public void statusOfAnEmptyRepositoryListsUntrackedFiles() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("empty"))) {
            repo.write("first.md", "hello\n");
            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue())
                    .containsExactly(new GitStatusEntry("first.md", GitStatusEntry.Kind.UNTRACKED));
        }
    }

    // ---------------------------------------------------------------- log

    @Test
    public void logOfAnEmptyRepositoryIsEmptyAndNotAnError() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("nocommits"))) {
            final GitResult<List<GitCommitInfo>> r = _git.log(repo.root(), 10, 0, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            assertThat(r.getValue()).isEmpty();
        }
    }

    @Test
    public void logOutsideARepositoryIsNotARepo() throws Exception {
        assertThat(_git.log(tmp.newFolder("plain"), 10, 0, GitProgress.NONE).getKind())
                .isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    @Test
    public void logPagesNewestFirst() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("history"))) {
            for (int i = 1; i <= 5; i++) {
                repo.write("note.md", "revision " + i + "\n");
                repo.commitAll("commit " + i);
            }

            assertThat(subjects(_git.log(repo.root(), 10, 0, GitProgress.NONE)))
                    .containsExactly("commit 5", "commit 4", "commit 3", "commit 2", "commit 1");
            assertThat(subjects(_git.log(repo.root(), 2, 0, GitProgress.NONE)))
                    .containsExactly("commit 5", "commit 4");
            assertThat(subjects(_git.log(repo.root(), 2, 2, GitProgress.NONE)))
                    .containsExactly("commit 3", "commit 2");
            assertThat(subjects(_git.log(repo.root(), 2, 4, GitProgress.NONE)))
                    .containsExactly("commit 1");
            assertThat(subjects(_git.log(repo.root(), 2, 99, GitProgress.NONE))).isEmpty();
        }
    }

    @Test
    public void logMapsShaSubjectBodyAuthorAndTime() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("mapping"))) {
            repo.write("note.md", "hi\n");
            final long before = System.currentTimeMillis() / 1000L - 1;
            repo.commitAll("Add a note\n\nThe body explains why.\nSecond body line.\n");

            final GitCommitInfo info = _git.log(repo.root(), 1, 0, GitProgress.NONE).getValue().get(0);
            assertThat(info.getSha()).hasSize(40).matches("[0-9a-f]{40}");
            assertThat(info.getShortSha()).isEqualTo(info.getSha().substring(0, GitCommitInfo.SHORT_SHA_LENGTH));
            assertThat(info.getSubject()).isEqualTo("Add a note");
            assertThat(info.getBody()).isEqualTo("The body explains why.\nSecond body line.");
            assertThat(info.getAuthorName()).isEqualTo(GitTestRepo.AUTHOR_NAME);
            assertThat(info.getAuthorEmail()).isEqualTo(GitTestRepo.AUTHOR_EMAIL);
            assertThat(info.getEpochSeconds()).isGreaterThanOrEqualTo(before);
        }
    }

    @Test
    public void logOfASingleLineMessageHasAnEmptyBody() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("subjectonly"))) {
            repo.write("note.md", "hi\n");
            repo.commitAll("Only a subject");
            assertThat(_git.log(repo.root(), 1, 0, GitProgress.NONE).getValue().get(0).getBody()).isEmpty();
        }
    }

    @Test
    public void logRejectsNonSensiblePaging() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("paging"))) {
            repo.write("note.md", "hi\n");
            repo.commitAll("initial");
            assertThat(_git.log(repo.root(), 0, 0, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(_git.log(repo.root(), 5, -1, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        }
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    public void cancelledProgressStopsLocalOperations() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("cancel"))) {
            repo.write("note.md", "hi\n");
            repo.commitAll("initial");

            assertThat(_git.status(repo.root(), CANCELLED).getKind()).isEqualTo(GitResult.Kind.CANCELLED);
            assertThat(_git.log(repo.root(), 10, 0, CANCELLED).getKind()).isEqualTo(GitResult.Kind.CANCELLED);
            assertThat(_git.init(new File(tmp.getRoot(), "never"), CANCELLED).getKind()).isEqualTo(GitResult.Kind.CANCELLED);
            assertThat(new File(tmp.getRoot(), "never")).doesNotExist();
        }
    }

    private static boolean runGit(final File cwd, final String... args) {
        final List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            final Process process = new ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> subjects(final GitResult<List<GitCommitInfo>> result) {
        assertThat(result.isOk()).as(result.toString()).isTrue();
        final List<String> out = new ArrayList<>();
        for (final GitCommitInfo c : result.getValue()) {
            out.add(c.getSubject());
        }
        return out;
    }

    /** A progress that is cancelled from the start. */
    private static final GitProgress CANCELLED = new GitProgress() {
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
            return true;
        }
    };
}
