/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class JGitCredentialsTest {

    private static final class Source implements GitCredentialsSource {
        final List<String> asked = new ArrayList<>();
        String user;
        String secret;

        @Override
        public String getUsername(final String host) {
            asked.add("user:" + host);
            return user;
        }

        @Override
        public char[] getSecret(final String host) {
            asked.add("secret:" + host);
            return secret == null ? null : secret.toCharArray();
        }
    }

    @Test
    public void fillsUsernameAndPasswordByLowerCaseHost() throws Exception {
        final Source source = new Source();
        source.user = "alice";
        source.secret = "ghp_token";
        final JGitCredentials cp = JGitCredentials.forSource(source);
        final CredentialItem.Username u = new CredentialItem.Username();
        final CredentialItem.Password p = new CredentialItem.Password();
        assertThat(cp.isInteractive()).isFalse();
        assertThat(cp.supports(u, p)).isTrue();
        assertThat(cp.get(new URIish("https://GitHub.com/a/b.git"), u, p)).isTrue();
        assertThat(u.getValue()).isEqualTo("alice");
        assertThat(new String(p.getValue())).isEqualTo("ghp_token");
        assertThat(source.asked).containsExactly("user:github.com", "secret:github.com");
    }

    @Test
    public void usernameFromUrlWhenSourceHasNone() throws Exception {
        final Source source = new Source();
        source.secret = "t";
        final CredentialItem.Username u = new CredentialItem.Username();
        final CredentialItem.Password p = new CredentialItem.Password();
        assertThat(JGitCredentials.forSource(source).get(new URIish("https://bob@example.org/x.git"), u, p)).isTrue();
        assertThat(u.getValue()).isEqualTo("bob");
    }

    @Test
    public void missingCredentialsMakeGetReturnFalse() throws Exception {
        final CredentialItem.Username u = new CredentialItem.Username();
        final CredentialItem.Password p = new CredentialItem.Password();
        assertThat(JGitCredentials.forSource(GitCredentialsSource.NONE).get(new URIish("https://h/x"), u, p)).isFalse();
        assertThat(JGitCredentials.forSource(null).get(new URIish("https://h/x"), u, p)).isFalse();
        final Source noSecret = new Source();
        noSecret.user = "alice";
        assertThat(JGitCredentials.forSource(noSecret).get(new URIish("https://h/x"), u, p)).isFalse();
    }

    @Test
    public void refusesInteractiveItems() throws Exception {
        final JGitCredentials cp = JGitCredentials.forSource(GitCredentialsSource.NONE);
        final CredentialItem.YesNoType yesNo = new CredentialItem.YesNoType("Trust this certificate?");
        assertThat(cp.supports(yesNo)).isFalse();
        // On https, where JGit's "trust this certificate anyway" prompt actually appears, the item is
        // still refused loudly, so the app cannot be talked into accepting a bad certificate.
        assertThatThrownBy(() -> cp.get(new URIish("https://h/x"), yesNo)).isInstanceOf(UnsupportedCredentialItem.class);
        assertThat(JGitCredentials.hostOf(new URIish("file:///tmp/x"))).isEmpty();
        assertThat(JGitCredentials.hostOf(null)).isEmpty();
    }

    /**
     * The second lock of roadmap task 7.5: {@code GitRemoteUrlPolicy} refuses a non-https remote
     * before a transport is opened, and this refuses to hand the token over even if something ever
     * gets past it. Asserted with a source that does hold a secret, so a {@code false} can only come
     * from the scheme.
     */
    @Test
    public void handsTheTokenToHttpsAndToNothingElse() throws Exception {
        final GitCredentialsSource source = new GitCredentialsSource() {
            @Override
            public String getUsername(final String host) {
                return "alice";
            }

            @Override
            public char[] getSecret(final String host) {
                return "ghp_secret".toCharArray();
            }
        };
        final JGitCredentials cp = JGitCredentials.forSource(source);

        assertThat(cp.get(new URIish("https://h/x"), new CredentialItem.Username(), new CredentialItem.Password())).isTrue();
        for (final String url : new String[]{"http://h/x", "ftp://h/x", "git://h/x", "ssh://git@h/x", "file:///tmp/x"}) {
            final CredentialItem.Password password = new CredentialItem.Password();
            assertThat(cp.get(new URIish(url), new CredentialItem.Username(), password)).as(url).isFalse();
            assertThat(password.getValue()).as(url).isNull();
        }
        // isTlsUri lower-cases defensively, but URIish will not parse an upper-case scheme at all,
        // so the only thing worth asserting here is that a missing URI is refused.
        assertThat(JGitCredentials.isTlsUri(null)).isFalse();
    }
}
