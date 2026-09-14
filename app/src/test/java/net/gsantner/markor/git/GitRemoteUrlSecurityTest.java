/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.ui.GitRemoteUrlValidator;

import org.junit.Test;

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
    public void onlyHttpsAndLocalPathsMayCarryRemoteOperations() {
        assertThat(GitRemoteUrlPolicy.refusalFor("https://github.com/me/n.git")).isNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("file:///tmp/bare.git")).isNull();
        // The one-slash form File.toURI() produces: a scheme URIish sees but "://" does not.
        assertThat(GitRemoteUrlPolicy.refusalFor("file:/tmp/bare.git")).isNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("/tmp/bare.git")).isNull();

        assertThat(GitRemoteUrlPolicy.refusalFor("http://github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("ftp://github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://git@github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("git://github.com/me/n.git")).isNotNull();
        assertThat(GitRemoteUrlPolicy.refusalFor("git@github.com:me/n.git")).isNotNull();
    }

    /**
     * The scheme is judged before the userinfo, so an SSH remote is told SSH is unsupported instead of
     * being told to remove the "git@" — advice that would not help and that the user would follow
     * before finding out.
     */
    @Test
    public void anSshRemoteIsToldAboutSshRatherThanAboutItsUsername() {
        assertThat(GitRemoteUrlPolicy.refusalFor("ssh://git@github.com/me/n.git")).contains("SSH");
        assertThat(GitRemoteUrlPolicy.refusalFor("git+ssh://git@github.com/me/n.git")).contains("SSH");
        assertThat(GitRemoteUrlPolicy.refusalFor("git@github.com:me/n.git")).contains("SSH");
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
