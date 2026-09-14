/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static net.gsantner.markor.git.GitTestRepos.ALICE;
import static net.gsantner.markor.git.GitTestRepos.commitFile;
import static net.gsantner.markor.git.GitTestRepos.fileUrl;
import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * Security review (roadmap task 7.5): {@code .git/config} is untrusted input.
 * <p>
 * A repository lives in the notebook folder on shared external storage, so any app holding storage
 * permission can rewrite it, as can a desktop clone copied over or a file-sync app. The two dialogs
 * validate what the <i>user</i> types; these tests cover what the app reads back off disk right
 * before it hands the access token to JGit.
 * <p>
 * Nothing here touches the network: every case must be refused before a connection is attempted, so
 * the refusal is asserted on the message, not on a timeout.
 */
public class JGitRemoteConfigSecurityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final GitService _git = new JGitService();
    private File _bare;
    private Git _clone;
    private File _cloneDir;

    @Before
    public void setUp() throws Exception {
        _bare = GitTestRepos.newBareRemote(tmp, "origin.git");
        GitTestRepos.seedRemote(tmp, _bare);
        _cloneDir = tmp.newFolder("clone");
        _clone = GitTestRepos.cloneInto(_bare, _cloneDir);
    }

    @After
    public void tearDown() {
        if (_clone != null) {
            _clone.close();
        }
    }

    private void setConfig(final String section, final String subsection, final String name, final String value) throws Exception {
        final StoredConfig config = _clone.getRepository().getConfig();
        config.setString(section, subsection, name, value);
        config.save();
    }

    // ---------------------------------------------------------------- the baseline still works

    @Test
    public void anUntouchedFileRemoteStillFetchesAndPushes() throws Exception {
        assertThat(_git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE).isOk()).isTrue();
        commitFile(_clone, "notes.md", "# Notes\nmore\n", "More notes", ALICE);
        assertThat(_git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE).isOk()).isTrue();
    }

    // ---------------------------------------------------------------- cleartext http

    @Test
    public void aRemoteRewrittenToPlainHttpIsRefusedByEveryOperationThatSendsTheToken() throws Exception {
        setConfig("remote", "origin", "url", "http://github.com/me/notes.git");

        for (final GitResult<?> result : new GitResult<?>[]{
                _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE),
                _git.pull(_cloneDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, ALICE, GitProgress.NONE),
                _git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE)}) {
            assertThat(result.isOk()).isFalse();
            assertThat(result.getMessage()).contains("http://");
        }
    }

    @Test
    public void aRemoteRewrittenToAnUnsupportedSchemeIsRefused() throws Exception {
        setConfig("remote", "origin", "url", "ftp://example.org/notes.git");
        final GitResult<GitAheadBehind> result = _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("https://");
    }

    @Test
    public void aRemoteUrlCarryingATokenIsRefusedRatherThanUsed() throws Exception {
        setConfig("remote", "origin", "url", "https://ghp_secrettoken@github.com/me/notes.git");
        final GitResult<GitAheadBehind> result = _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).doesNotContain("ghp_secrettoken");
    }

    // ---------------------------------------------------------------- TLS turned off in the config

    @Test
    public void aRepositoryThatDisablesCertificateCheckingIsRefused() throws Exception {
        setConfig("http", null, "sslVerify", "false");

        for (final GitResult<?> result : new GitResult<?>[]{
                _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE),
                _git.pull(_cloneDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, ALICE, GitProgress.NONE),
                _git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE)}) {
            assertThat(result.isOk()).isFalse();
            assertThat(result.getMessage()).contains("sslVerify");
        }
    }

    /**
     * {@code HttpConfig.init} reads the bare {@code http.sslVerify} first and then overwrites it from
     * the {@code [http "<url>"]} subsection whose URL matches the remote, so a check that only looked
     * at the bare key was bypassable.
     */
    @Test
    public void aPerUrlSubsectionThatDisablesCertificateCheckingIsRefusedToo() throws Exception {
        setConfig("http", "https://github.com/", "sslVerify", "false");

        for (final GitResult<?> result : new GitResult<?>[]{
                _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE),
                _git.pull(_cloneDir, GitPullStrategy.FF_ONLY, GitCredentialsSource.NONE, ALICE, GitProgress.NONE),
                _git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE)}) {
            assertThat(result.isOk()).isFalse();
            assertThat(result.getMessage()).contains("sslVerify");
        }
    }

    @Test
    public void aPerUrlSubsectionCookieFileIsRefusedToo() throws Exception {
        setConfig("http", "https://github.com/", "cookieFile", "/data/data/net.gsantner.markor/cookies");
        final GitResult<GitAheadBehind> result = _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("cookieFile");
    }

    @Test
    public void anExplicitSslVerifyTrueIsFine() throws Exception {
        setConfig("http", null, "sslVerify", "true");
        assertThat(_git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE).isOk()).isTrue();
    }

    @Test
    public void aRepositoryPointingGitAtACookieFileIsRefused() throws Exception {
        setConfig("http", null, "cookieFile", tmp.newFile("cookies.txt").getAbsolutePath());
        final GitResult<GitAheadBehind> result = _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("cookieFile");
    }

    @Test
    public void aRepositoryAskingGitToSaveCookiesIsRefused() throws Exception {
        setConfig("http", null, "saveCookies", "true");
        final GitResult<GitAheadBehind> result = _git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("cookieFile");
    }

    // ---------------------------------------------------------------- pushurl

    /**
     * JGit's {@code PushCommand} prefers {@code remote.<name>.pushurl} over {@code remote.<name>.url},
     * but {@link GitRepoInfo} reads only {@code url} — so the header and the "confirm before push"
     * dialog would name a host the push does not use.
     */
    @Test
    public void aPushUrlPointingSomewhereElseIsRefused() throws Exception {
        commitFile(_clone, "notes.md", "# Notes\nmore\n", "More notes", ALICE);
        setConfig("remote", "origin", "pushurl", "https://attacker.example/notes.git");

        final GitResult<Void> result = _git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("pushurl");
        // Fetching is unaffected: the fetch URL is still the honest one.
        assertThat(_git.fetch(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE).isOk()).isTrue();
    }

    @Test
    public void aPushUrlEqualToTheFetchUrlIsHarmlessAndAllowed() throws Exception {
        commitFile(_clone, "notes.md", "# Notes\nmore\n", "More notes", ALICE);
        setConfig("remote", "origin", "pushurl", fileUrl(_bare));
        assertThat(_git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE).isOk()).isTrue();
    }

    @Test
    public void aPlainHttpPushUrlIsRefusedEvenWhenTheFetchUrlIsFine() throws Exception {
        commitFile(_clone, "notes.md", "# Notes\nmore\n", "More notes", ALICE);
        setConfig("remote", "origin", "pushurl", "http://github.com/me/notes.git");

        final GitResult<Void> result = _git.push(_cloneDir, GitCredentialsSource.NONE, GitProgress.NONE);
        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("http://");
    }
}
