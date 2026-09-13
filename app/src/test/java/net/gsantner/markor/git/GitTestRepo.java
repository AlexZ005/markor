/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A throw-away repository in a temp folder for the local-operation tests: create files, stage and
 * commit them with a fixed identity. The initial branch is always {@code main} so the developer's
 * global {@code init.defaultBranch} does not change the outcome.
 */
final class GitTestRepo implements AutoCloseable {

    static final String AUTHOR_NAME = "Test Author";
    static final String AUTHOR_EMAIL = "test@example.com";

    private final File _root;
    private final Git _git;

    GitTestRepo(final File root) throws Exception {
        _root = root;
        _git = Git.init().setDirectory(root).setInitialBranch("main").call();
    }

    File root() {
        return _root;
    }

    Git git() {
        return _git;
    }

    /** Writes (and creates parent folders for) a file in the working tree. */
    File write(final String relativePath, final String content) throws IOException {
        final File file = new File(_root, relativePath);
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Writes raw bytes, for the binary-file cases. */
    File writeBytes(final String relativePath, final byte[] content) throws IOException {
        final File file = new File(_root, relativePath);
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        Files.write(file.toPath(), content);
        return file;
    }

    void delete(final String relativePath) {
        final File file = new File(_root, relativePath);
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Cannot delete " + file);
        }
    }

    String read(final String relativePath) throws IOException {
        return new String(Files.readAllBytes(new File(_root, relativePath).toPath()), StandardCharsets.UTF_8);
    }

    /** {@code git add .} */
    void addAll() throws Exception {
        _git.add().addFilepattern(".").call();
    }

    /** {@code git add -A .} -- also records deletions. */
    void addAllIncludingDeletions() throws Exception {
        _git.add().addFilepattern(".").call();
        _git.add().addFilepattern(".").setUpdate(true).call();
    }

    RevCommit commitAll(final String message) throws Exception {
        addAllIncludingDeletions();
        return commitIndex(message);
    }

    RevCommit commitIndex(final String message) throws Exception {
        return _git.commit().setMessage(message)
                .setAuthor(AUTHOR_NAME, AUTHOR_EMAIL)
                .setCommitter(AUTHOR_NAME, AUTHOR_EMAIL)
                .call();
    }

    @Override
    public void close() {
        _git.close(); // Git.init() hands over ownership of the repository, so this closes it too
    }
}
