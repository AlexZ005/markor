/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.transport.URIish;
import com.jcraft.jsch.JSchHostKeyExceptions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

import javax.net.ssl.SSLHandshakeException;

/** Exception to result-kind mapping, with synthetic exceptions shaped like the ones JGit throws. */
public class JGitErrorsTest {

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

    @Test
    public void http401And403BecomeAuthFailedWithoutUserinfo() {
        // TransportHttp: new TransportException(uri, JGitText.get().notAuthorized) -> "<uri>: not authorized"
        final GitResult<Void> r = JGitErrors.map(new TransportException("https://alice:tok3n@github.com/a/b.git: not authorized"), GitProgress.NONE, null);
        assertThat(r.getKind()).isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(r.getMessage()).contains("not authorized").doesNotContain("tok3n").doesNotContain("alice");

        assertThat(JGitErrors.map(new TransportException("git-receive-pack not permitted on 'https://x/y'"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(JGitErrors.map(new TransportException("https://x/y: authentication not supported"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(JGitErrors.map(new TransportException("Authentication is required but no CredentialsProvider has been registered"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        // internal (errors package) flavour, wrapped the way FetchCommand does it
        assertThat(JGitErrors.map(new JGitInternalException("x", new org.eclipse.jgit.errors.TransportException("https://h/x: not authorized")), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
    }

    @Test
    public void sshPublickeyFailuresBecomeAuthFailed() {
        // JSch's wording, as seen on the api26 emulator against GitHub with a key it does not know
        // (doc/adr/0002-ssh-on-android.md, row "wrong-key"). JschConfigSessionFactory wraps the
        // JSchException in an errors.TransportException, which LsRemote/Fetch/Push re-wrap.
        assertThat(JGitErrors.map(new TransportException(
                "git@github.com:me/notes.git: Auth fail for methods 'publickey'"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(JGitErrors.map(new JGitInternalException("x", new org.eclipse.jgit.errors.TransportException(
                "ssh://git@github.com/me/notes.git: Auth fail for methods 'publickey'")), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(JGitErrors.map(new TransportException("git@h:x.git: Auth cancel"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(JGitErrors.map(new TransportException("git@h:x.git: USERAUTH fail"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        assertThat(JGitErrors.map(new TransportException("git@h:x.git: Permission denied (publickey)."), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.AUTH_FAILED);
        // A host key problem is not an authentication problem: it must not be answered with
        // "check your key". Recognised by the exception type rather than by the prose, see below.
        assertThat(JGitErrors.map(new TransportException("git@h:x.git: HostKey has been changed: h"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.FAILED);
        assertThat(JGitErrors.map(new TransportException("git@h:x.git: reject HostKey: h"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.FAILED);
    }

    /**
     * Roadmap task 8.1c. JSch raises two distinct exceptions for the two host-key outcomes and JGit
     * wraps both in a TransportException, where the prose is all that is left — and the prose of an
     * unknown host key contains the word "key", close enough to the auth rules above to be read as an
     * authentication failure. The exception types are checked first, and before everything else.
     */
    @Test
    public void hostKeyProblemsAreNamedAsThemselves() {
        final Exception changed = new TransportException("git@github.com:me/n.git",
                JSchHostKeyExceptions.changed("HostKey has been changed: github.com"));
        final GitResult<?> mismatch = JGitErrors.map(changed, GitProgress.NONE, null);
        assertThat(mismatch.getKind()).isEqualTo(GitResult.Kind.HOST_KEY_MISMATCH);
        assertThat(mismatch.getMessage()).contains("host key changed").contains("Settings");

        final Exception unknown = new TransportException("git@github.com:me/n.git",
                JSchHostKeyExceptions.unknown("UnknownHostKey: github.com. RSA key fingerprint is SHA256:abc"));
        final GitResult<?> declined = JGitErrors.map(unknown, GitProgress.NONE, null);
        assertThat(declined.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(declined.getMessage()).contains("fingerprint");
        // Not AUTH_FAILED: the key was never offered, so "check your key" would be wrong advice.
        assertThat(declined.getKind()).isNotEqualTo(GitResult.Kind.AUTH_FAILED);
    }

    @Test
    public void networkFailuresBecomeNetwork() {
        assertThat(JGitErrors.map(new TransportException("https://h/x: cannot open git-upload-pack", new UnknownHostException("h")), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NETWORK);
        assertThat(JGitErrors.map(new TransportException("x", new ConnectException("Connection refused")), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NETWORK);
        assertThat(JGitErrors.map(new TransportException("x", new SocketTimeoutException("Read timed out")), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NETWORK);
        assertThat(JGitErrors.map(new TransportException("x", new SSLHandshakeException("bad cert")), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NETWORK);
        assertThat(JGitErrors.map(new TransportException("https://h/x: Connection time out: h"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NETWORK);
        assertThat(JGitErrors.map(new TransportException("https://h/x: remote hung up unexpectedly"), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NETWORK);
    }

    @Test
    public void repositoryAndRemoteProblems() throws Exception {
        final GitResult<Void> notRepo = JGitErrors.map(new RepositoryNotFoundException(new File("/tmp/x")), GitProgress.NONE, new File("/tmp/x"));
        assertThat(notRepo.getKind()).isEqualTo(GitResult.Kind.NOT_A_REPO);
        assertThat(notRepo.getMessage()).contains("/tmp/x");
        assertThat(JGitErrors.map(new JGitInternalException("wrapped", new RepositoryNotFoundException(new File("/y"))), GitProgress.NONE, null).getKind())
                .isEqualTo(GitResult.Kind.NOT_A_REPO);

        final GitResult<Void> noRemote = JGitErrors.map(new NoRemoteRepositoryException(new URIish("https://u:p@h/x.git"), "not found"), GitProgress.NONE, null);
        assertThat(noRemote.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(noRemote.getMessage()).contains("not found").doesNotContain(":p@");

        assertThat(JGitErrors.map(new InvalidRemoteException("Invalid remote: origin"), GitProgress.NONE, null).getKind()).isEqualTo(GitResult.Kind.FAILED);
    }

    @Test
    public void checkoutConflictIsDirtyWorkTree() {
        final CheckoutConflictException e = new CheckoutConflictException(Arrays.asList("todo.txt", "a/b.md"),
                new org.eclipse.jgit.errors.CheckoutConflictException("todo.txt"));
        final GitResult<Void> r = JGitErrors.map(e, GitProgress.NONE, null);
        assertThat(r.getKind()).isEqualTo(GitResult.Kind.DIRTY_WORK_TREE);
        assertThat(r.getFiles()).containsExactly("todo.txt", "a/b.md");
    }

    @Test
    public void cancellationWinsOverEverything() {
        assertThat(JGitErrors.map(new TransportException("Download cancelled"), CANCELLED, null).getKind()).isEqualTo(GitResult.Kind.CANCELLED);
        assertThat(JGitErrors.map(new IOException("x"), CANCELLED, null).getKind()).isEqualTo(GitResult.Kind.CANCELLED);
    }

    @Test
    public void unknownErrorsAreFailedWithSanitizedMessage() {
        final GitResult<Void> r = JGitErrors.map(new IllegalStateException("cannot lock https://me:pw@h/x"), GitProgress.NONE, null);
        assertThat(r.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(r.getMessage()).isEqualTo("cannot lock https://h/x");
        assertThat(JGitErrors.map(new IllegalStateException(), GitProgress.NONE, null).getMessage()).isEqualTo("IllegalStateException");
    }
}
