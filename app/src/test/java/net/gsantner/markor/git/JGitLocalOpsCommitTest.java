/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Task 2.2: {@code commit} of {@link JGitLocalOps}. */
public class JGitLocalOpsCommitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();
    private static final GitAuthor AUTHOR = new GitAuthor("Alex Z", "alex@example.com");

    @Test
    public void commitOfEverythingInAnEmptyRepositoryCreatesTheRootCommit() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("first"))) {
            repo.write("note.md", "hello\n");
            repo.write("journal/entry.md", "today\n");

            final GitResult<GitCommitInfo> r = _git.commit(repo.root(), "Initial commit", null, AUTHOR, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            assertThat(r.getValue().getSubject()).isEqualTo("Initial commit");
            assertThat(r.getValue().getSha()).hasSize(40);

            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue()).isEmpty();
            assertThat(_git.log(repo.root(), 10, 0, GitProgress.NONE).getValue()).hasSize(1);
        }
    }

    @Test
    public void commitUsesTheGivenAuthorAsAuthorAndCommitter() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("identity"))) {
            repo.write("note.md", "hello\n");
            _git.commit(repo.root(), "Add a note\n\nWith a body.\n", null, AUTHOR, GitProgress.NONE).getValue();

            final RevCommit head = repo.git().log().setMaxCount(1).call().iterator().next();
            assertThat(head.getAuthorIdent().getName()).isEqualTo("Alex Z");
            assertThat(head.getAuthorIdent().getEmailAddress()).isEqualTo("alex@example.com");
            assertThat(head.getCommitterIdent().getName()).isEqualTo("Alex Z");
            assertThat(head.getCommitterIdent().getEmailAddress()).isEqualTo("alex@example.com");

            final GitCommitInfo info = _git.log(repo.root(), 1, 0, GitProgress.NONE).getValue().get(0);
            assertThat(info.getAuthorName()).isEqualTo("Alex Z");
            assertThat(info.getSubject()).isEqualTo("Add a note");
            assertThat(info.getBody()).isEqualTo("With a body.");
        }
    }

    @Test
    public void commitOfASubsetLeavesTheOtherFilesUncommitted() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("subset"))) {
            repo.write("tracked.md", "one\n");
            repo.commitAll("initial");

            repo.write("tracked.md", "one\ntwo\n");
            repo.write("untracked.md", "new\n");
            repo.write("journal/entry.md", "today\n");

            final GitResult<GitCommitInfo> r = _git.commit(repo.root(), "Only the tracked file",
                    Collections.singletonList("tracked.md"), AUTHOR, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();

            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue()).containsExactly(
                    new GitStatusEntry("journal/entry.md", GitStatusEntry.Kind.UNTRACKED),
                    new GitStatusEntry("untracked.md", GitStatusEntry.Kind.UNTRACKED));
            assertThat(_git.diffForCommit(repo.root(), r.getValue().getSha(), GitProgress.NONE).getValue().getFiles())
                    .hasSize(1);
        }
    }

    @Test
    public void commitStagesDeletionsAndAdditionsTogether() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("addrm"))) {
            repo.write("keep.md", "keep\n");
            repo.write("gone.md", "bye\n");
            repo.commitAll("initial");

            repo.delete("gone.md");
            repo.write("fresh.md", "new\n");
            repo.write("keep.md", "keep\nmore\n");

            final GitResult<GitCommitInfo> r = _git.commit(repo.root(), "Add, modify and delete",
                    Arrays.asList("gone.md", "fresh.md", "keep.md"), AUTHOR, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue()).isEmpty();

            final List<GitDiff.FileChange> files =
                    _git.diffForCommit(repo.root(), r.getValue().getSha(), GitProgress.NONE).getValue().getFiles();
            assertThat(files).hasSize(3);
            final StringBuilder summary = new StringBuilder();
            for (final GitDiff.FileChange change : files) {
                summary.append(change.getPath()).append('=').append(change.getKind()).append(' ');
            }
            assertThat(summary.toString()).contains("fresh.md=ADDED").contains("gone.md=DELETED").contains("keep.md=MODIFIED");
        }
    }

    @Test
    public void commitOfANestedPathWorksFromAnyPathInsideTheRepository() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("nested"))) {
            repo.write("journal/entry.md", "today\n");
            final GitResult<GitCommitInfo> r = _git.commit(new java.io.File(repo.root(), "journal"),
                    "Add today", Collections.singletonList("journal/entry.md"), AUTHOR, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            assertThat(_git.status(repo.root(), GitProgress.NONE).getValue()).isEmpty();
        }
    }

    @Test
    public void commitWithNothingToDoIsRefused() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("nothing"))) {
            repo.write("note.md", "hello\n");
            repo.commitAll("initial");

            final GitResult<GitCommitInfo> all = _git.commit(repo.root(), "Nothing changed", null, AUTHOR, GitProgress.NONE);
            assertThat(all.getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(all.getMessage()).contains("Nothing to commit");

            final GitResult<GitCommitInfo> unchanged = _git.commit(repo.root(), "Still nothing",
                    Collections.singletonList("note.md"), AUTHOR, GitProgress.NONE);
            assertThat(unchanged.getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(unchanged.getMessage()).contains("Nothing to commit");

            final GitResult<GitCommitInfo> bogus = _git.commit(repo.root(), "No such file",
                    Collections.singletonList("does-not-exist.md"), AUTHOR, GitProgress.NONE);
            assertThat(bogus.getKind()).isEqualTo(GitResult.Kind.FAILED);

            assertThat(_git.log(repo.root(), 10, 0, GitProgress.NONE).getValue()).hasSize(1);
        }
    }

    @Test
    public void commitWithABlankMessageIsRefused() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("nomessage"))) {
            repo.write("note.md", "hello\n");

            for (final String message : Arrays.asList(null, "", "   \n\t ")) {
                final GitResult<GitCommitInfo> r = _git.commit(repo.root(), message, null, AUTHOR, GitProgress.NONE);
                assertThat(r.getKind()).as("message=" + message).isEqualTo(GitResult.Kind.FAILED);
                assertThat(r.getMessage()).contains("message");
            }
            assertThat(_git.log(repo.root(), 10, 0, GitProgress.NONE).getValue()).isEmpty();
        }
    }

    @Test
    public void commitWithoutAnAuthorIsRefused() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("noauthor"))) {
            repo.write("note.md", "hello\n");
            assertThat(_git.commit(repo.root(), "Add a note", null, null, GitProgress.NONE).getKind())
                    .isEqualTo(GitResult.Kind.FAILED);
        }
    }

    @Test
    public void commitIgnoresFilesMatchedByGitignore() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("ignoring"))) {
            repo.write(".gitignore", "*.log\n");
            repo.write("debug.log", "noise\n");
            repo.write("note.md", "hello\n");

            final GitResult<GitCommitInfo> r = _git.commit(repo.root(), "Initial", null, AUTHOR, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();

            final GitDiff diff = _git.diffForCommit(repo.root(), r.getValue().getSha(), GitProgress.NONE).getValue();
            assertThat(diff.getUnified()).doesNotContain("debug.log");
            assertThat(diff.getFiles()).hasSize(2); // .gitignore and note.md
        }
    }

    @Test
    public void commitOutsideARepositoryIsNotARepo() throws Exception {
        assertThat(_git.commit(tmp.newFolder("plain"), "Nope", null, AUTHOR, GitProgress.NONE).getKind())
                .isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    @Test
    public void commitDuringAConflictingMergeIsRefused() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("merging"))) {
            repo.write("note.md", "base\n");
            repo.commitAll("base");

            repo.git().checkout().setCreateBranch(true).setName("side").call();
            repo.write("note.md", "from side\n");
            repo.commitAll("side change");

            repo.git().checkout().setName("main").call();
            repo.write("note.md", "from main\n");
            repo.commitAll("main change");

            repo.git().merge().include(repo.git().getRepository().resolve("side")).call();
            assertThat(_git.open(repo.root(), GitProgress.NONE).getValue().getState()).isEqualTo(GitRepoState.MERGING);

            final List<GitStatusEntry> status = _git.status(repo.root(), GitProgress.NONE).getValue();
            assertThat(status).containsExactly(new GitStatusEntry("note.md", GitStatusEntry.Kind.CONFLICT));

            final GitResult<GitCommitInfo> r = _git.commit(repo.root(), "Should not work", null, AUTHOR, GitProgress.NONE);
            assertThat(r.getKind()).isEqualTo(GitResult.Kind.FAILED);
            assertThat(r.getMessage()).contains("merge");
        }
    }
}
