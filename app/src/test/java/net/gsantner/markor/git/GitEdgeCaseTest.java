/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Roadmap task 7.2: the states a repository can be in that are not the happy path. Every one of them
 * must come back as a {@link GitResult} carrying a message — never an exception out of the service,
 * and never a result the Git tab would render as a crash.
 * <p>
 * The cases that need a device or a screen (a token that did not survive process death, the header of
 * a detached HEAD) are verified on the emulator and recorded in the lane handover instead.
 */
public class GitEdgeCaseTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();
    private static final GitAuthor AUTHOR = new GitAuthor("Alex Z", "alex@example.com");

    // ------------------------------------------------------------ folder deleted or moved

    @Test
    public void everyOperationOnADeletedRepositoryFolderReportsNotARepository() throws Exception {
        final File root = tmp.newFolder("gone");
        try (GitTestRepo repo = new GitTestRepo(root)) {
            repo.write("note.md", "hello\n");
            repo.commitAll("Initial commit");
        }
        deleteRecursively(root);
        assertThat(root.exists()).isFalse();

        assertKindWithMessage(_git.open(root, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
        assertKindWithMessage(_git.status(root, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
        assertKindWithMessage(_git.log(root, 20, 0, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
        assertKindWithMessage(_git.aheadBehind(root, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
        assertKindWithMessage(_git.diffWorkingTree(root, null, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
        assertKindWithMessage(_git.commit(root, "message", null, AUTHOR, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);

        assertThat(_git.isRepository(root)).isFalse();
        assertThat(_git.findRepositoryRoot(root)).isNull();
    }

    @Test
    public void aFolderWhoseDotGitWasDeletedIsNoLongerARepository() throws Exception {
        final File root = tmp.newFolder("degitted");
        try (GitTestRepo repo = new GitTestRepo(root)) {
            repo.write("note.md", "hello\n");
            repo.commitAll("Initial commit");
        }
        deleteRecursively(new File(root, ".git"));

        assertThat(root.isDirectory()).isTrue();
        assertThat(new File(root, "note.md").isFile()).isTrue();
        assertKindWithMessage(_git.open(root, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
        assertKindWithMessage(_git.status(root, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);
    }

    @Test
    public void aRepositoryThatMovedIsReadAtItsNewPath() throws Exception {
        final File from = tmp.newFolder("before");
        try (GitTestRepo repo = new GitTestRepo(from)) {
            repo.write("note.md", "hello\n");
            repo.commitAll("Initial commit");
        }
        final File to = new File(tmp.getRoot(), "after");
        assertThat(from.renameTo(to)).isTrue();

        assertKindWithMessage(_git.open(from, GitProgress.NONE), GitResult.Kind.NOT_A_REPO);

        final GitResult<GitRepoInfo> moved = _git.open(to, GitProgress.NONE);
        assertThat(moved.isOk()).as(moved.toString()).isTrue();
        assertThat(moved.getValue().getWorkTree().getCanonicalFile()).isEqualTo(to.getCanonicalFile());
        assertThat(_git.log(to, 10, 0, GitProgress.NONE).getValue()).hasSize(1);
    }

    // ------------------------------------------------------------ detached HEAD

    @Test
    public void aDetachedHeadIsDescribedByItsShortShaAndHasNoUpstream() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("detached"))) {
            repo.write("note.md", "one\n");
            final ObjectId first = repo.commitAll("First").getId();
            repo.write("note.md", "two\n");
            repo.commitAll("Second");
            repo.git().checkout().setName(first.name()).call();

            final GitResult<GitRepoInfo> info = _git.open(repo.root(), GitProgress.NONE);
            assertThat(info.isOk()).as(info.toString()).isTrue();
            assertThat(info.getValue().isDetached()).isTrue();
            // What the header prints; GitRepoInfo abbreviates the commit id into getBranch().
            assertThat(info.getValue().getBranch()).isEqualTo(first.name().substring(0, GitCommitInfo.SHORT_SHA_LENGTH));
            assertThat(info.getValue().getUpstream()).isNull();
            assertThat(info.getValue().getHeadSha()).isEqualTo(first.name());

            // Status, history and diff keep working; only the branch-shaped operations do not, and
            // they say why. This is what the tab renders as greyed-out Pull and Push plus a hint.
            assertThat(_git.status(repo.root(), GitProgress.NONE).isOk()).isTrue();
            assertThat(_git.log(repo.root(), 10, 0, GitProgress.NONE).getValue()).hasSize(1);
            assertThat(_git.diffWorkingTree(repo.root(), null, GitProgress.NONE).isOk()).isTrue();
            assertHasMessage(_git.aheadBehind(repo.root(), GitProgress.NONE));
            assertHasMessage(_git.push(repo.root(), GitCredentialsSource.NONE, GitProgress.NONE));
            assertHasMessage(_git.pull(repo.root(), GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE,
                    AUTHOR, GitProgress.NONE));
        }
    }

    // ------------------------------------------------------------ empty repository

    @Test
    public void anEmptyRepositoryHasNoHistoryAndStillCommits() throws Exception {
        final File root = tmp.newFolder("empty");
        final GitResult<GitRepoInfo> init = _git.init(root, GitProgress.NONE);
        assertThat(init.isOk()).as(init.toString()).isTrue();
        assertThat(init.getValue().isEmpty()).isTrue();
        assertThat(init.getValue().getHeadSha()).isNull();
        assertThat(init.getValue().isDetached()).isFalse();

        // The History list's empty state, not an error.
        final GitResult<List<GitCommitInfo>> log = _git.log(root, 50, 0, GitProgress.NONE);
        assertThat(log.isOk()).as(log.toString()).isTrue();
        assertThat(log.getValue()).isEmpty();

        final GitResult<GitAheadBehind> ahead = _git.aheadBehind(root, GitProgress.NONE);
        assertThat(ahead.isOk()).as(ahead.toString()).isTrue();
        assertThat(ahead.getValue().hasUpstream()).isFalse();

        assertThat(_git.status(root, GitProgress.NONE).getValue()).isEmpty();

        // An untracked file shows up, and committing it works in a repository without a HEAD.
        writeFile(new File(root, "first.md"), "hello\n");
        assertThat(_git.status(root, GitProgress.NONE).getValue()).hasSize(1);
        final GitResult<GitCommitInfo> commit = _git.commit(root, "First commit", null, AUTHOR, GitProgress.NONE);
        assertThat(commit.isOk()).as(commit.toString()).isTrue();
        assertThat(_git.log(root, 50, 0, GitProgress.NONE).getValue()).hasSize(1);
        assertThat(_git.diffForCommit(root, commit.getValue().getSha(), GitProgress.NONE).isOk()).isTrue();
    }

    @Test
    public void anEmptyRepositoryWithoutARemoteFailsToFetchAndPushWithAMessage() throws Exception {
        final File root = tmp.newFolder("empty-no-remote");
        assertThat(_git.init(root, GitProgress.NONE).isOk()).isTrue();

        assertHasMessage(_git.fetch(root, GitCredentialsSource.NONE, GitProgress.NONE));
        assertHasMessage(_git.push(root, GitCredentialsSource.NONE, GitProgress.NONE));
    }

    // ------------------------------------------------------------ very large history

    /**
     * The History list pages with {@code log(limit, skip)} and must walk past the roadmap's 1000-commit
     * mark without repeating or dropping a commit. 1200 commits keep this test around a few seconds.
     */
    @Test
    public void historyPagesThroughMoreThanAThousandCommits() throws Exception {
        final int commits = 1200;
        final int pageSize = 50;
        final File root = tmp.newFolder("long-history");
        try (GitTestRepo repo = new GitTestRepo(root)) {
            repo.write("log.md", "0\n");
            repo.commitAll("Commit 0");
            for (int i = 1; i < commits; i++) {
                repo.write("log.md", i + "\n");
                repo.addAll();
                repo.commitIndex("Commit " + i);
            }
        }

        final List<String> subjects = new ArrayList<>();
        final Set<String> shas = new HashSet<>();
        for (int skip = 0; ; skip += pageSize) {
            final GitResult<List<GitCommitInfo>> page = _git.log(root, pageSize, skip, GitProgress.NONE);
            assertThat(page.isOk()).as(page.toString()).isTrue();
            if (page.getValue().isEmpty()) {
                break;
            }
            for (final GitCommitInfo commit : page.getValue()) {
                assertThat(shas.add(commit.getSha())).as("duplicate commit " + commit.getSha()).isTrue();
                subjects.add(commit.getSubject());
            }
            assertThat(skip).as("paging did not terminate").isLessThan(commits * 2);
        }

        assertThat(subjects).hasSize(commits);
        assertThat(subjects.get(0)).isEqualTo("Commit " + (commits - 1));            // newest first
        assertThat(subjects.get(subjects.size() - 1)).isEqualTo("Commit 0");
        // The page that straddles commit 1000 is a normal page, not the end of the walk.
        assertThat(_git.log(root, pageSize, 1000, GitProgress.NONE).getValue()).hasSize(pageSize);
        // A skip beyond the end is an empty page, not an error.
        assertThat(_git.log(root, pageSize, commits + 10, GitProgress.NONE).getValue()).isEmpty();
    }

    // ------------------------------------------------------------ binary files

    @Test
    public void aBinaryFileInTheWorkingTreeDiffIsSummarisedAndNeverDumped() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("binary"))) {
            repo.write("note.md", "text\n");
            repo.writeBytes("image.png", pngLikeBytes(0x00));
            repo.commitAll("Initial commit");

            repo.write("note.md", "text\nmore\n");
            repo.writeBytes("image.png", pngLikeBytes(0x7f));

            final GitResult<GitDiff> diff = _git.diffWorkingTree(repo.root(), null, GitProgress.NONE);
            assertThat(diff.isOk()).as(diff.toString()).isTrue();

            final GitDiff.FileChange binary = fileChange(diff.getValue(), "image.png");
            assertThat(binary.isBinary()).isTrue();
            assertThat(binary.getLinesAdded()).isZero();
            assertThat(binary.getLinesDeleted()).isZero();

            final GitDiff.FileChange text = fileChange(diff.getValue(), "note.md");
            assertThat(text.isBinary()).isFalse();
            assertThat(text.getLinesAdded()).isEqualTo(1);

            // The unified text says the file differs and contains none of its bytes.
            assertThat(diff.getValue().getUnified()).contains("image.png").contains("Binary files differ");
            assertThat(diff.getValue().getUnified()).doesNotContain(" ");
            assertThat(diff.getValue().getUnified().length()).isLessThan(4096);
        }
    }

    @Test
    public void aBinaryFileInACommitDiffIsSummarisedToo() throws Exception {
        try (GitTestRepo repo = new GitTestRepo(tmp.newFolder("binary-commit"))) {
            repo.write("note.md", "text\n");
            repo.commitAll("Initial commit");
            repo.writeBytes("blob.bin", pngLikeBytes(0x41));
            final String sha = repo.commitAll("Add a binary file").getId().name();

            final GitResult<GitDiff> diff = _git.diffForCommit(repo.root(), sha, GitProgress.NONE);
            assertThat(diff.isOk()).as(diff.toString()).isTrue();
            assertThat(fileChange(diff.getValue(), "blob.bin").isBinary()).isTrue();
            assertThat(diff.getValue().getUnified()).contains("Binary files differ");
            assertThat(diff.getValue().getUnified().length()).isLessThan(4096);
        }
    }

    // ------------------------------------------------------------ read-only repository

    /**
     * A repository on a path the app cannot write: reading it must keep working so the user can see
     * what is there, and the commit must come back as a failure with a message.
     */
    @Test
    public void aReadOnlyRepositoryStillReadsAndReportsWhyTheCommitFailed() throws Exception {
        final File root = tmp.newFolder("read-only");
        try (GitTestRepo repo = new GitTestRepo(root)) {
            repo.write("note.md", "hello\n");
            repo.commitAll("Initial commit");
            repo.write("note.md", "hello\nchanged\n");
        }
        setReadOnlyRecursively(root);
        // Running as root ignores the permission bits; there is nothing to assert then.
        assumeTrue("the repository is not actually read-only", isEffectivelyReadOnly(root));
        try {
            assertThat(_git.open(root, GitProgress.NONE).isOk()).isTrue();
            assertThat(_git.status(root, GitProgress.NONE).getValue()).isNotEmpty();
            assertThat(_git.log(root, 10, 0, GitProgress.NONE).getValue()).hasSize(1);
            assertThat(_git.diffWorkingTree(root, null, GitProgress.NONE).isOk()).isTrue();

            final GitResult<GitCommitInfo> commit =
                    _git.commit(root, "Cannot be written", null, AUTHOR, GitProgress.NONE);
            assertThat(commit.isOk()).as(commit.toString()).isFalse();
            assertHasMessage(commit);
        } finally {
            setWritableRecursively(root); // so the TemporaryFolder rule can clean up
        }
    }

    // ------------------------------------------------------------ remote removed behind the app's back

    @Test
    public void aRemoteRemovedFromTheConfigLeavesTheRepositoryReadableAndSaysSo() throws Exception {
        final File bare = tmp.newFolder("origin.git");
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close();

        final File root = tmp.newFolder("clone-source");
        try (GitTestRepo repo = new GitTestRepo(root)) {
            repo.write("note.md", "hello\n");
            repo.commitAll("Initial commit");
            final StoredConfig config = repo.git().getRepository().getConfig();
            config.setString("remote", "origin", "url", bare.toURI().toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
            assertThat(_git.push(root, GitCredentialsSource.NONE, GitProgress.NONE).isOk()).isTrue();
        }
        assertThat(_git.open(root, GitProgress.NONE).getValue().hasRemote()).isTrue();

        // Something else edits .git/config -- a desktop git, a restored backup, a sync tool.
        try (Git git = Git.open(root)) {
            final StoredConfig config = git.getRepository().getConfig();
            config.unsetSection("remote", "origin");
            config.save();
        }

        final GitResult<GitRepoInfo> info = _git.open(root, GitProgress.NONE);
        assertThat(info.isOk()).as(info.toString()).isTrue();
        assertThat(info.getValue().hasRemote()).isFalse();
        assertThat(info.getValue().getRemoteUrl()).isNull();

        // The local side is untouched; the network side fails with something to show the user.
        assertThat(_git.status(root, GitProgress.NONE).isOk()).isTrue();
        assertThat(_git.log(root, 10, 0, GitProgress.NONE).getValue()).hasSize(1);
        assertHasMessage(_git.fetch(root, GitCredentialsSource.NONE, GitProgress.NONE));
        assertHasMessage(_git.push(root, GitCredentialsSource.NONE, GitProgress.NONE));
        assertHasMessage(_git.pull(root, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, AUTHOR, GitProgress.NONE));
    }

    // ------------------------------------------------------------ helpers

    private static void assertKindWithMessage(final GitResult<?> result, final GitResult.Kind expected) {
        assertThat(result.getKind()).as(result.toString()).isEqualTo(expected);
        assertHasMessage(result);
    }

    /** Every failure the tab shows needs words; an empty message would render as a blank dialog. */
    private static void assertHasMessage(final GitResult<?> result) {
        assertThat(result.isOk()).as(result.toString()).isFalse();
        assertThat(result.getMessage()).as(result.toString()).isNotEmpty();
    }

    private static GitDiff.FileChange fileChange(final GitDiff diff, final String path) {
        for (final GitDiff.FileChange change : diff.getFiles()) {
            if (path.equals(change.getPath())) {
                return change;
            }
        }
        throw new AssertionError(path + " is not in " + diff.getFiles());
    }

    /** Bytes with a NUL early on, which is what git itself uses to call a file binary. */
    private static byte[] pngLikeBytes(final int fill) {
        final byte[] bytes = new byte[2048];
        Arrays.fill(bytes, (byte) fill);
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = 0;
        bytes[5] = 0;
        return bytes;
    }

    private static void writeFile(final File file, final String content) throws Exception {
        java.nio.file.Files.write(file.toPath(), content.getBytes("UTF-8"));
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        assertThat(file.delete()).as("cannot delete " + file).isTrue();
    }

    private static void setReadOnlyRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                setReadOnlyRecursively(child);
            }
        }
        file.setWritable(false, false);
    }

    private static void setWritableRecursively(final File file) {
        file.setWritable(true, true);
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                setWritableRecursively(child);
            }
        }
    }

    private static boolean isEffectivelyReadOnly(final File root) {
        final File probe = new File(root, "probe-" + System.nanoTime());
        try {
            if (probe.createNewFile()) {
                probe.delete();
                return false;
            }
        } catch (final Exception expected) {
            return true;
        }
        return true;
    }
}
