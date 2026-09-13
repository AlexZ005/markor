/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.URIish;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Fixtures built with JGit's own API: bare file:// remotes, seeded clones, commits. */
final class GitTestRepos {
    static final GitAuthor ALICE = new GitAuthor("Alice", "alice@example.com");
    static final GitAuthor BOB = new GitAuthor("Bob", "bob@example.com");
    static final String BRANCH = "main";

    private GitTestRepos() {
    }

    static File newBareRemote(final TemporaryFolder tmp, final String name) throws Exception {
        final File dir = tmp.newFolder(name);
        Git.init().setBare(true).setDirectory(dir).setInitialBranch(BRANCH).call().close();
        return dir;
    }

    /** {@code file:///abs/path} (three slashes, the form JGit normalizes to and stores in the config). */
    static String fileUrl(final File dir) {
        String path = dir.getAbsolutePath().replace(File.separatorChar, '/');
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "file://" + (path.startsWith("/") ? "" : "/") + path;
    }

    /** Creates a working repository, commits {@code todo.txt} and {@code notes.md}, pushes {@code main} to the bare remote. */
    static File seedRemote(final TemporaryFolder tmp, final File bare) throws Exception {
        final File seed = tmp.newFolder("seed");
        try (Git git = Git.init().setDirectory(seed).setInitialBranch(BRANCH).call()) {
            git.remoteAdd().setName("origin").setUri(new URIish(fileUrl(bare))).call();
            write(seed, "todo.txt", "- buy milk\n");
            write(seed, "notes.md", "# Notes\n");
            git.add().addFilepattern(".").call();
            commit(git, "Initial notes", ALICE);
            git.push().setRemote("origin").call();
        }
        return seed;
    }

    static Git cloneInto(final File bare, final File dir) throws Exception {
        return Git.cloneRepository().setURI(fileUrl(bare)).setDirectory(dir).call();
    }

    static RevCommit commitFile(final Git git, final String path, final String content, final String message, final GitAuthor author) throws Exception {
        write(git.getRepository().getWorkTree(), path, content);
        git.add().addFilepattern(path).call();
        return commit(git, message, author);
    }

    static RevCommit commit(final Git git, final String message, final GitAuthor author) throws Exception {
        return git.commit().setMessage(message).setAuthor(author.getName(), author.getEmail())
                .setCommitter(author.getName(), author.getEmail()).call();
    }

    static void write(final File workTree, final String path, final String content) throws IOException {
        final File file = new File(workTree, path);
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    static String read(final File workTree, final String path) throws IOException {
        return new String(Files.readAllBytes(new File(workTree, path).toPath()), StandardCharsets.UTF_8);
    }

    static ObjectId head(final Git git) throws IOException {
        return git.getRepository().resolve("HEAD");
    }

    static List<RevCommit> log(final Git git) throws Exception {
        final List<RevCommit> commits = new ArrayList<>();
        for (final RevCommit c : git.log().call()) {
            commits.add(c);
        }
        return commits;
    }

    /** Progress that records task names and can be told to cancel after the first callback. */
    static final class RecordingProgress implements GitProgress {
        final List<String> tasks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int progressCalls;
        int ends;
        volatile boolean cancelled;
        boolean cancelOnFirstCallback;

        @Override
        public void onTaskBegin(final String task, final int totalWork) {
            tasks.add(task);
            events.add("begin:" + task);
            if (cancelOnFirstCallback) {
                cancelled = true;
            }
        }

        @Override
        public void onTaskProgress(final String task, final int completedWork, final int totalWork, final int percent) {
            progressCalls++;
            if (cancelOnFirstCallback) {
                cancelled = true;
            }
        }

        @Override
        public void onTaskEnd(final String task) {
            ends++;
            events.add("end:" + task);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
