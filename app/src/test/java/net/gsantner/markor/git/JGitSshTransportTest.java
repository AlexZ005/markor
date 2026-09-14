/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.ssh.GitSshSessionFactory;

import org.eclipse.jgit.api.Git;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The SSH transport is attached to exactly the operations that run over SSH, and to no others
 * (roadmap task 8.1c). Nothing here opens a socket: the fake source either refuses, or hands out an
 * identity for an address that is refused at connect, which is enough to see what the operation did
 * with the key.
 */
public class JGitSshTransportTest {

    @Rule
    public TemporaryFolder _tmp = new TemporaryFolder();

    /** Records what the ops layer asked for, and answers whatever the test told it to. */
    private static final class FakeSsh implements GitSshAuthSource {
        final List<String> resolvedUrls = new ArrayList<>();
        final List<File> resolvedRepos = new ArrayList<>();
        GitSshSessionFactory.Identity identity;
        File knownHosts;
        String refusal = "no key here";

        @Override
        public Resolution resolve(final File repoDir, final String url) {
            resolvedRepos.add(repoDir);
            resolvedUrls.add(url);
            return identity == null ? Resolution.refused(refusal) : Resolution.of(identity, knownHosts);
        }

        @Override
        public boolean acceptNewHostKey(final String host, final String keyType, final String fingerprint) {
            return false;
        }
    }

    private static final class FakeCredentials implements GitCredentialsSource {
        final FakeSsh ssh = new FakeSsh();
        int secretsHandedOut;

        @Override
        public String getUsername(final String host) {
            return "someone";
        }

        @Override
        public char[] getSecret(final String host) {
            secretsHandedOut++;
            return "token".toCharArray();
        }

        @Override
        public GitSshAuthSource ssh() {
            return ssh;
        }
    }

    // ---------------------------------------------------------------- the key only goes to SSH

    @Test
    public void aLocalRemoteNeverAsksForAnSshKey() throws Exception {
        final File bare = GitTestRepos.newBareRemote(_tmp, "bare.git");
        GitTestRepos.seedRemote(_tmp, bare);
        final File work = _tmp.newFolder("work");
        GitTestRepos.cloneInto(bare, work).close();

        final FakeCredentials credentials = new FakeCredentials();
        final GitResult<GitAheadBehind> result = new JGitService().fetch(work, credentials, GitProgress.NONE);

        assertThat(result.isOk()).isTrue();
        assertThat(credentials.ssh.resolvedUrls).isEmpty();
    }

    @Test
    public void anHttpsRemoteNeverAsksForAnSshKey() throws Exception {
        final File work = _tmp.newFolder("https-work");
        try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
            GitTestRepos.commitFile(git, "a.md", "a", "first", GitTestRepos.ALICE);
            setRemote(git, "https://github.com/me/notes.git");
        }
        final FakeCredentials credentials = new FakeCredentials();
        // Fails at the network, which is the point: it got that far without asking for a key.
        new JGitService().fetch(work, credentials, GitProgress.NONE);
        assertThat(credentials.ssh.resolvedUrls).isEmpty();
    }

    // ---------------------------------------------------------------- and the token only to https

    @Test
    public void anSshRemoteIsRefusedWithTheSourcesOwnWordsAndGetsNoToken() throws Exception {
        final File work = _tmp.newFolder("ssh-work");
        try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
            GitTestRepos.commitFile(git, "a.md", "a", "first", GitTestRepos.ALICE);
            setRemote(git, "git@github.com:me/notes.git");
        }
        final FakeCredentials credentials = new FakeCredentials();
        credentials.ssh.refusal = "The SSH key this repository uses is gone";

        final GitResult<GitAheadBehind> result = new JGitService().fetch(work, credentials, GitProgress.NONE);

        assertThat(result.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(result.getMessage()).isEqualTo("The SSH key this repository uses is gone");
        assertThat(credentials.ssh.resolvedUrls).containsExactly("git@github.com:me/notes.git");
        assertThat(credentials.ssh.resolvedRepos).containsExactly(work);
        // The refusal happened before a transport was opened, so no token was ever asked for either.
        assertThat(credentials.secretsHandedOut).isZero();
    }

    @Test
    public void pushAndPullAskForTheKeyToo() throws Exception {
        final File work = _tmp.newFolder("ssh-push");
        try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
            GitTestRepos.commitFile(git, "a.md", "a", "first", GitTestRepos.ALICE);
            setRemote(git, "ssh://git@github.com/me/notes.git");
        }
        final FakeCredentials credentials = new FakeCredentials();
        assertThat(new JGitService().push(work, credentials, GitProgress.NONE).getKind())
                .isEqualTo(GitResult.Kind.FAILED);
        assertThat(new JGitService().pull(work, GitPullStrategy.FF_ONLY, credentials,
                GitTestRepos.ALICE, GitProgress.NONE).getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(credentials.ssh.resolvedUrls)
                .containsExactly("ssh://git@github.com/me/notes.git", "ssh://git@github.com/me/notes.git");
    }

    /** Clone and "Test connection" have no repository yet: the key came from the dialog. */
    @Test
    public void cloneAndLsRemoteAskWithoutARepository() throws Exception {
        final FakeCredentials credentials = new FakeCredentials();
        final File target = new File(_tmp.getRoot(), "fresh");

        assertThat(new JGitService().clone("git@github.com:me/notes.git", target, credentials, GitProgress.NONE).isOk())
                .isFalse();
        assertThat(new JGitService().lsRemote("git@github.com:me/notes.git", credentials, GitProgress.NONE).isOk())
                .isFalse();

        assertThat(credentials.ssh.resolvedRepos).containsExactly(null, null);
        assertThat(target).doesNotExist();
    }

    // ---------------------------------------------------------------- the key does not outlive the call

    /**
     * The identity holds the decrypted private key, so the operation wipes it in a {@code finally}
     * whatever happened. Port 1 on the loopback interface refuses the connection immediately, so the
     * operation really does go through the transport and really does fail.
     */
    @Test
    public void theDecryptedKeyIsWipedWhenTheOperationEnds() throws Exception {
        final byte[] privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nnot a real key\n".getBytes(StandardCharsets.UTF_8);
        final byte[] passphrase = "hunter2".getBytes(StandardCharsets.UTF_8);
        final FakeCredentials credentials = new FakeCredentials();
        credentials.ssh.identity = new GitSshSessionFactory.Identity("test", privateKey, null, passphrase);
        credentials.ssh.knownHosts = new File(_tmp.newFolder("hosts"), "known_hosts");

        final GitResult<List<String>> result = new JGitService()
                .lsRemote("ssh://git@127.0.0.1:1/me/notes.git", credentials, GitProgress.NONE);

        assertThat(result.isOk()).isFalse();
        assertThat(privateKey).containsOnly((byte) 0);
        assertThat(passphrase).containsOnly((byte) 0);
    }

    // ---------------------------------------------------------------- a source that knows nothing

    @Test
    public void withoutAnSshSourceAnSshRemoteIsRefusedRatherThanAttempted() throws Exception {
        final File work = _tmp.newFolder("no-source");
        try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
            GitTestRepos.commitFile(git, "a.md", "a", "first", GitTestRepos.ALICE);
            setRemote(git, "git@github.com:me/notes.git");
        }
        final GitResult<GitAheadBehind> result =
                new JGitService().fetch(work, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.getKind()).isEqualTo(GitResult.Kind.FAILED);
        assertThat(result.getMessage()).contains("Settings");
    }

    private static void setRemote(final Git git, final String url) throws Exception {
        git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(url)).call();
    }
}
