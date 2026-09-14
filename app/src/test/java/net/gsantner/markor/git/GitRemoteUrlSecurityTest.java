/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.ssh.GitSshRemoteTrust;
import net.gsantner.markor.git.ui.GitRemoteUrlValidator;

import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import java.net.URISyntaxException;

/**
 * Security review (roadmap task 7.5): a personal access token must never reach {@code .git/config},
 * a displayed URL or an error message, no matter which of the three userinfo spellings it hides in.
 * <p>
 * {@code URIish} parses only {@code user:pass@host} the way one expects: it reports no password for
 * {@code https://token@host/x} (the token is the <i>user</i>) and fails to find a host at all for
 * {@code https://:token@host/x}, reporting neither user nor password. Both spellings are what the
 * GitHub documentation and most tutorials tell people to paste, so both must be refused rather than
 * written into the repository configuration.
 */
public class GitRemoteUrlSecurityTest {

    private static final String TOKEN = "ghp_0123456789abcdefghijklmnopqrstuvwxyz";

    // ---------------------------------------------------------------- the three userinfo spellings

    @Test
    public void sanitizeRemovesTheTokenFromEveryUserinfoSpelling() {
        assertThat(JGitRepos.sanitizeUrl("https://user:" + TOKEN + "@github.com/me/n.git"))
                .isEqualTo("https://github.com/me/n.git");
        // The token as the user name: what GitHub's own "clone with a PAT" instructions produce.
        assertThat(JGitRepos.sanitizeUrl("https://" + TOKEN + "@github.com/me/n.git"))
                .isEqualTo("https://github.com/me/n.git");
        // Empty user name: URIish finds no host here, so the URIish path alone leaves the token in.
        assertThat(JGitRepos.sanitizeUrl("https://:" + TOKEN + "@github.com/me/n.git"))
                .isEqualTo("https://github.com/me/n.git");
    }

    /**
     * An SSH remote's {@code git@} is the address, not a credential: hiding it would show the user
     * something they cannot paste back and cannot compare with what their forge displays. A password
     * behind it is still removed (roadmap task 8.1c).
     */
    @Test
    public void sanitizeKeepsTheSshLoginNameButNotAPasswordBehindIt() {
        assertThat(JGitRepos.sanitizeUrl("git@github.com:me/n.git")).isEqualTo("git@github.com:me/n.git");
        assertThat(JGitRepos.sanitizeUrl("ssh://git@github.com/me/n.git")).isEqualTo("ssh://git@github.com/me/n.git");
        assertThat(JGitRepos.sanitizeUrl("ssh://git@github.com:2222/me/n.git")).isEqualTo("ssh://git@github.com:2222/me/n.git");

        assertThat(JGitRepos.sanitizeUrl("ssh://git:" + TOKEN + "@github.com/me/n.git"))
                .isEqualTo("ssh://github.com/me/n.git");
        assertThat(JGitRepos.sanitizeUrl("git:" + TOKEN + "@github.com:me/n.git"))
                .isEqualTo("github.com:me/n.git");
    }

    @Test
    public void sanitizeLeavesCleanUrlsAlone() {
        assertThat(JGitRepos.sanitizeUrl("https://github.com/me/n.git")).isEqualTo("https://github.com/me/n.git");
        assertThat(JGitRepos.sanitizeUrl("https://github.com:8443/me/n.git")).isEqualTo("https://github.com:8443/me/n.git");
        assertThat(JGitRepos.sanitizeUrl("file:///tmp/x")).isEqualTo("file:///tmp/x");
        // An @ in the path is not userinfo.
        assertThat(JGitRepos.sanitizeUrl("https://github.com/me/a@b.git")).isEqualTo("https://github.com/me/a@b.git");
        assertThat(JGitRepos.sanitizeUrl(null)).isNull();
    }

    @Test
    public void everyUserinfoSpellingCountsAsCredentialsInTheUrl() {
        assertThat(JGitRepos.hasUserinfo("https://user:" + TOKEN + "@github.com/n.git")).isTrue();
        assertThat(JGitRepos.hasUserinfo("https://" + TOKEN + "@github.com/n.git")).isTrue();
        assertThat(JGitRepos.hasUserinfo("https://:" + TOKEN + "@github.com/n.git")).isTrue();
        assertThat(JGitRepos.hasUserinfo("git@github.com:me/n.git")).isTrue();
        assertThat(JGitRepos.hasUserinfo("https://github.com/me/n.git")).isFalse();
        assertThat(JGitRepos.hasUserinfo("https://github.com/me/a@b.git")).isFalse();
        assertThat(JGitRepos.hasUserinfo(null)).isFalse();
    }

    @Test
    public void theDialogsRefuseEveryUserinfoSpelling() {
        assertThat(GitRemoteUrlValidator.validate("https://user:" + TOKEN + "@github.com/n.git").getProblem())
                .isEqualTo(GitRemoteUrlValidator.Problem.CONTAINS_PASSWORD);
        assertThat(GitRemoteUrlValidator.validate("https://" + TOKEN + "@github.com/n.git").getProblem())
                .isEqualTo(GitRemoteUrlValidator.Problem.CONTAINS_PASSWORD);
        assertThat(GitRemoteUrlValidator.validate("https://:" + TOKEN + "@github.com/n.git").getProblem())
                .isEqualTo(GitRemoteUrlValidator.Problem.CONTAINS_PASSWORD);
    }

    // ---------------------------------------------------------------- scheme policy

    @Test
    public void onlyHttpsSshAndLocalPathsMayCarryRemoteOperations() {
        assertThat(GitRemoteUrlPolicy.refusalFor("https://github.com/me/n.git")).isNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("file:///tmp/bare.git")).isNull();
        // The one-slash form File.toURI() produces: a scheme URIish sees but "://" does not.
        assertThat(GitRemoteUrlPolicy.refusalFor("file:/tmp/bare.git")).isNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("/tmp/bare.git")).isNull();
        // Roadmap task 8.1c: SSH joined https, on its own rules.
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://git@github.com/me/n.git")).isNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("git@github.com:me/n.git")).isNull();

        assertThat(GitRemoteUrlPolicy.refusalFor("http://github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("ftp://github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("git://github.com/me/n.git")).isNotNull();
    }

    @Test
    public void theTransportIsNamedSoTheCallerKnowsWhichCredentialMayBeUsed() {
        assertThat(GitRemoteUrlPolicy.decide("https://github.com/me/n.git").getTransport())
                .isEqualTo(GitRemoteUrlPolicy.Transport.HTTPS);
        assertThat(GitRemoteUrlPolicy.decide("ssh://git@github.com/me/n.git").getTransport())
                .isEqualTo(GitRemoteUrlPolicy.Transport.SSH);
        assertThat(GitRemoteUrlPolicy.decide("git@github.com:me/n.git").getTransport())
                .isEqualTo(GitRemoteUrlPolicy.Transport.SSH);
        assertThat(GitRemoteUrlPolicy.decide("file:///tmp/bare.git").getTransport())
                .isEqualTo(GitRemoteUrlPolicy.Transport.LOCAL);
        assertThat(GitRemoteUrlPolicy.decide("http://github.com/me/n.git").getTransport()).isNull();
    }

    /**
     * The userinfo rules are opposite for the two transports and must stay that way: for https the
     * userinfo <i>is</i> the token in the spelling GitHub's instructions produce, for SSH it is the
     * login name the server needs and there is no other place to put it.
     */
    @Test
    public void sshKeepsItsLoginNameWhileHttpsRefusesAnyUserinfo() {
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://git@github.com/me/n.git")).isNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("https://git@github.com/me/n.git")).isNotNull();
    }

    /** No {@code ~/.ssh/config} and no login name on Android, so the name cannot be defaulted. */
    @Test
    public void anSshRemoteWithoutALoginNameIsRefused() {
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://github.com/me/n.git")).contains("user name");
        assertThat(GitRemoteUrlPolicy.refusalFor("github.com:me/n.git")).contains("user name");
    }

    @Test
    public void anSshRemoteWithAPasswordOrAnOddUserNameIsRefused() {
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://git:" + TOKEN + "@github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://git:" + TOKEN + "@github.com/me/n.git")).doesNotContain(TOKEN);
        assertThat(GitRemoteUrlPolicy.refusalFor("git:" + TOKEN + "@github.com:me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://@github.com/me/n.git")).isNotNull();
        // A second @ cannot be smuggled into the login name.
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://a@b@github.com/me/n.git")).isNotNull();
    }

    // ------------------------------------------- the token and the key never swap transports

    /**
     * The two locks of roadmap task 8.1c, from both sides. {@code JGitCredentials} is the last gate in
     * front of the token and hands it to https and nothing else, so an SSH remote — however it came to
     * be one — never sees it.
     */
    @Test
    public void theAccessTokenIsNeverOfferedToAnSshRemote() throws URISyntaxException {
        assertThat(JGitCredentials.isTlsUri(new URIish("https://github.com/me/n.git"))).isTrue();

        assertThat(JGitCredentials.isTlsUri(new URIish("ssh://git@github.com/me/n.git"))).isFalse();
        assertThat(JGitCredentials.isTlsUri(new URIish("git@github.com:me/n.git"))).isFalse();
        assertThat(JGitCredentials.isTlsUri(new URIish("git+ssh://git@github.com/me/n.git"))).isFalse();
        assertThat(JGitCredentials.isTlsUri(new URIish("ssh+git://git@github.com/me/n.git"))).isFalse();
    }

    /**
     * And the other direction: the SSH key is offered only where the policy says SSH. A repository
     * whose remote is https gets no key, which is what keeps the key out of a transport that cannot
     * use it and out of a host the key was never meant for.
     */
    @Test
    public void theSshKeyIsNeverOfferedToAnHttpsRemote() {
        assertThat(GitRemoteUrlPolicy.decide("https://github.com/me/n.git").isSsh()).isFalse();
        assertThat(GitRemoteUrlPolicy.decide("http://github.com/me/n.git").isSsh()).isFalse();
        assertThat(GitRemoteUrlPolicy.decide("file:///tmp/bare.git").isSsh()).isFalse();
        assertThat(GitSshRemoteTrust.refusalFor("https://github.com/me/n.git", "https://github.com/me/n.git"))
                .isNotNull();
    }

    // ------------------------------------------- .git/config cannot turn a remote into an SSH one

    /**
     * The attack the ADR names: an app with storage permission rewrites {@code .git/config} from the
     * https remote the user set up to {@code git@attacker.example:…}. Trust-on-first-use would only
     * ask the user to confirm a fingerprint they cannot judge, so the key is offered only when the
     * app's own record already says this repository is that SSH remote.
     */
    @Test
    public void anSshRemoteIsOnlyTrustedWhenTheAppItselfStoredIt() {
        final String mine = "git@github.com:me/notes.git";
        assertThat(GitSshRemoteTrust.refusalFor(mine, mine)).isNull();
        // The same remote written the other way round is the same remote.
        assertThat(GitSshRemoteTrust.refusalFor(mine, "ssh://git@github.com/me/notes.git")).isNull();
        assertThat(GitSshRemoteTrust.refusalFor("ssh://git@github.com/me/notes.git", mine)).isNull();

        assertThat(GitSshRemoteTrust.refusalFor(mine, "git@attacker.example:me/notes.git")).isNotNull();
        assertThat(GitSshRemoteTrust.refusalFor("https://github.com/me/notes.git", mine)).isNotNull();
        assertThat(GitSshRemoteTrust.refusalFor(null, mine)).isNotNull();
        assertThat(GitSshRemoteTrust.refusalFor("", mine)).isNotNull();
        // Neither the path nor the port is normalised away.
        assertThat(GitSshRemoteTrust.refusalFor(mine, "git@github.com:me/other.git")).isNotNull();
        assertThat(GitSshRemoteTrust.refusalFor("ssh://git@github.com:22/me/notes.git",
                "ssh://git@github.com:2222/me/notes.git")).isNotNull();
        // Nor the login name: another account on the same host is another remote.
        assertThat(GitSshRemoteTrust.refusalFor(mine, "root@github.com:me/notes.git")).isNotNull();
    }

    @Test
    public void aTrustRefusalNeverRepeatsTheUrl() {
        final String refusal = GitSshRemoteTrust.refusalFor("git@github.com:me/notes.git",
                "git@attacker.example:me/notes.git");
        assertThat(refusal).isNotNull();
        assertThat(refusal).doesNotContain("attacker.example").doesNotContain("github.com");
    }

    /** {@code http://} outranks the userinfo too: the scheme is the reason the URL cannot be used. */
    @Test
    public void aCleartextRemoteIsToldAboutHttpEvenWhenItAlsoCarriesAToken() {
        assertThat(GitRemoteUrlPolicy.refusalFor("http://" + TOKEN + "@github.com/me/n.git")).contains("http://");
    }

    @Test
    public void aRefusalNeverRepeatsTheToken() {
        final String refusal = GitRemoteUrlPolicy.refusalFor("http://" + TOKEN + "@github.com/me/n.git");
        assertThat(refusal).isNotNull();
        assertThat(refusal).doesNotContain(TOKEN);
    }

    @Test
    public void aRemoteWithCredentialsInItIsRefusedOutright() {
        assertThat(GitRemoteUrlPolicy.refusalFor("https://" + TOKEN + "@github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("https://:" + TOKEN + "@github.com/me/n.git")).isNotNull();
    }
}
