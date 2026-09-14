/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


/** Task 2.2: {@code diffWorkingTree} and {@code diffForCommit} of {@link JGitLocalOps}. */
public class JGitLocalOpsDiffTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();

    /** A PNG header followed by a NUL byte: what JGit classifies as binary. */
    private static final byte[] BINARY = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 1, 2, 3};

    // ---------------------------------------------------------------- working tree

    @Test
    public void diffOfACleanRepositoryIsEmpty() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("clean"))) {
            repo.write("note.md", "hello\n");
            repo.commitAll("initial");

            final GitResult<GitDiff> r = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            assertThat(r.getValue().isEmpty()).isTrue();
            assertThat(r.getValue()).isEqualTo(GitDiff.EMPTY);
        }
    }

    @Test
    public void diffOfAModifiedFileContainsAddedAndRemovedLines() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("modified"))) {
            repo.write("note.md", "one\ntwo\nthree\n");
            repo.commitAll("initial");
            repo.write("note.md", "one\nTWO\nthree\n");

            final GitDiff diff = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE).getValue();
            assertThat(diff.getUnified())
                    .contains("diff --git a/note.md b/note.md")
                    .contains("@@")
                    .contains("\n-two\n")
                    .contains("\n+TWO\n");
            assertThat(diff.getFiles()).hasSize(1);
            final GitDiff.FileChange change = diff.getFiles().get(0);
            assertThat(change.getPath()).isEqualTo("note.md");
            assertThat(change.getOldPath()).isNull();
            assertThat(change.getKind()).isEqualTo(GitDiff.ChangeKind.MODIFIED);
            assertThat(change.isBinary()).isFalse();
            assertThat(change.getLinesAdded()).isEqualTo(1);
            assertThat(change.getLinesDeleted()).isEqualTo(1);
        }
    }

    @Test
    public void diffCombinesStagedAndUnstagedChangesAndShowsUntrackedFilesAsAdditions() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("mixed"))) {
            repo.write("tracked.md", "one\n");
            repo.write("removed.md", "bye\n");
            repo.commitAll("initial");

            repo.write("tracked.md", "one\ntwo\n");
            repo.git().add().addFilepattern("tracked.md").call(); // staged
            repo.write("tracked.md", "one\ntwo\nthree\n");        // and changed again, unstaged
            repo.delete("removed.md");
            repo.write("untracked.md", "brand new\n");

            final GitDiff diff = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE).getValue();
            assertThat(kinds(diff)).containsExactlyInAnyOrder(
                    "removed.md=DELETED", "tracked.md=MODIFIED", "untracked.md=ADDED");
            assertThat(diff.getUnified()).contains("\n+two\n").contains("\n+three\n").contains("\n+brand new\n").contains("\n-bye\n");
        }
    }

    @Test
    public void diffCanBeRestrictedToOnePath() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("onepath"))) {
            repo.write("a.md", "a\n");
            repo.write("journal/b.md", "b\n");
            repo.commitAll("initial");
            repo.write("a.md", "a changed\n");
            repo.write("journal/b.md", "b changed\n");

            final GitDiff all = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE).getValue();
            assertThat(kinds(all)).containsExactlyInAnyOrder("a.md=MODIFIED", "journal/b.md=MODIFIED");

            final GitDiff one = _git.diffWorkingTree(repo.root(), "journal/b.md", GitProgress.NONE).getValue();
            assertThat(kinds(one)).containsExactly("journal/b.md=MODIFIED");
            assertThat(one.getUnified()).doesNotContain("a.md");

            // leading slashes and backslashes are tolerated
            assertThat(kinds(_git.diffWorkingTree(repo.root(), "/journal\\b.md", GitProgress.NONE).getValue()))
                    .containsExactly("journal/b.md=MODIFIED");
        }
    }

    @Test
    public void diffOfABinaryFileIsSummarisedNotDumped() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("binary"))) {
            repo.writeBytes("image.png", BINARY);
            repo.write("note.md", "text\n");
            repo.commitAll("initial");

            final byte[] changed = BINARY.clone();
            changed[changed.length - 1] = 42;
            repo.writeBytes("image.png", changed);

            final GitDiff diff = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE).getValue();
            assertThat(kinds(diff)).containsExactly("image.png=MODIFIED");
            final GitDiff.FileChange change = diff.getFiles().get(0);
            assertThat(change.isBinary()).isTrue();
            assertThat(change.getLinesAdded()).isZero();
            assertThat(change.getLinesDeleted()).isZero();
            assertThat(diff.getUnified()).contains("Binary files differ").doesNotContain("PNG");
        }
    }

    @Test
    public void diffIgnoresFilesMatchedByGitignore() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("ignored"))) {
            repo.write(".gitignore", "*.log\n.app/\n");
            repo.commitAll("initial");

            repo.write("debug.log", "noise\n");
            repo.write(".app/snippets.json", "{}\n");
            repo.write("journal/entry.md", "today\n");

            final GitDiff diff = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE).getValue();
            assertThat(kinds(diff)).containsExactly("journal/entry.md=ADDED");
            assertThat(diff.getUnified()).doesNotContain("debug.log").doesNotContain("snippets.json");
        }
    }

    @Test
    public void diffOfARepositoryWithoutCommitsShowsEverythingAsAdded() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("nocommits"))) {
            repo.write("first.md", "hello\n");

            final GitDiff diff = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE).getValue();
            assertThat(kinds(diff)).containsExactly("first.md=ADDED");
            assertThat(diff.getUnified()).contains("\n+hello\n");
        }
    }

    @Test
    public void diffWorkingTreeOutsideARepositoryIsNotARepo() throws Exception {
        assertThat(_git.diffWorkingTree(tmp.newFolder("plain"), null, GitProgress.NONE).getKind())
                .isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    // ---------------------------------------------------------------- commits

    @Test
    public void diffForCommitComparesWithTheFirstParent() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("commitdiff"))) {
            repo.write("note.md", "one\ntwo\n");
            repo.write("stale.md", "bye\n");
            repo.commitAll("initial");

            repo.write("note.md", "one\nTWO\n");
            repo.delete("stale.md");
            repo.write("new.md", "fresh\n");
            final String sha = repo.commitAll("second").name();

            final GitResult<GitDiff> r = _git.diffForCommit(repo.root(), sha, GitProgress.NONE);
            assertThat(r.isOk()).as(r.toString()).isTrue();
            final GitDiff diff = r.getValue();
            assertThat(kinds(diff)).containsExactlyInAnyOrder("new.md=ADDED", "note.md=MODIFIED", "stale.md=DELETED");
            assertThat(diff.getUnified()).contains("\n-two\n").contains("\n+TWO\n").contains("\n+fresh\n").contains("\n-bye\n");
        }
    }

    @Test
    public void diffForRootCommitComparesWithTheEmptyTree() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("rootcommit"))) {
            repo.write("note.md", "hello\n");
            final String sha = repo.commitAll("initial").name();

            final GitDiff diff = _git.diffForCommit(repo.root(), sha, GitProgress.NONE).getValue();
            assertThat(kinds(diff)).containsExactly("note.md=ADDED");
            assertThat(diff.getUnified()).contains("new file mode").contains("\n+hello\n");
        }
    }

    @Test
    public void diffForCommitAcceptsAnAbbreviatedShaAndHeadAndSummarisesBinaries() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("abbrev"))) {
            repo.writeBytes("image.png", BINARY);
            final String sha = repo.commitAll("add image").name();

            assertThat(kinds(_git.diffForCommit(repo.root(), sha.substring(0, 8), GitProgress.NONE).getValue()))
                    .containsExactly("image.png=ADDED");
            final GitDiff head = _git.diffForCommit(repo.root(), "HEAD", GitProgress.NONE).getValue();
            assertThat(head.getFiles().get(0).isBinary()).isTrue();
            assertThat(head.getUnified()).contains("Binary files differ");
        }
    }

    @Test
    public void diffForCommitDetectsRenames() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("rename"))) {
            final StringBuilder content = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                content.append("line ").append(i).append('\n');
            }
            repo.write("old-name.md", content.toString());
            repo.commitAll("initial");

            repo.delete("old-name.md");
            repo.write("new-name.md", content.toString());
            final String sha = repo.commitAll("rename").name();

            final GitDiff diff = _git.diffForCommit(repo.root(), sha, GitProgress.NONE).getValue();
            assertThat(diff.getFiles()).hasSize(1);
            assertThat(diff.getFiles().get(0).getKind()).isEqualTo(GitDiff.ChangeKind.RENAMED);
            assertThat(diff.getFiles().get(0).getPath()).isEqualTo("new-name.md");
            assertThat(diff.getFiles().get(0).getOldPath()).isEqualTo("old-name.md");
        }
    }

    @Test
    public void diffForAnUnknownOrNonCommitIdFails() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("badsha"))) {
            repo.write("note.md", "hello\n");
            repo.commitAll("initial");

            assertThat(_git.diffForCommit(repo.root(), "0123456789012345678901234567890123456789", GitProgress.NONE).getKind())
                    .isEqualTo(GitResult.Kind.FAILED);
            assertThat(_git.diffForCommit(repo.root(), "no-such-ref", GitProgress.NONE).getKind())
                    .isEqualTo(GitResult.Kind.FAILED);
            assertThat(_git.diffForCommit(repo.root(), "  ", GitProgress.NONE).getKind())
                    .isEqualTo(GitResult.Kind.FAILED);
            // a tree id resolves, but is not a commit
            assertThat(_git.diffForCommit(repo.root(), "HEAD^{tree}", GitProgress.NONE).getKind())
                    .isEqualTo(GitResult.Kind.FAILED);
        }
    }

    @Test
    public void diffForCommitOutsideARepositoryIsNotARepo() throws Exception {
        assertThat(_git.diffForCommit(tmp.newFolder("plain2"), "HEAD", GitProgress.NONE).getKind())
                .isEqualTo(GitResult.Kind.NOT_A_REPO);
    }

    /** "path=KIND" for every file of the diff, so assertions read like a status listing. */
    private static java.util.List<String> kinds(final GitDiff diff) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final GitDiff.FileChange change : diff.getFiles()) {
            out.add(change.getPath() + "=" + change.getKind());
        }
        return out;
    }
}
