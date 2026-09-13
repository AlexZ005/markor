/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.ui.GitRemoteUrlValidator.Problem;
import net.gsantner.markor.git.ui.GitRemoteUrlValidator.Result;

import org.junit.Test;

public class GitRemoteUrlValidatorTest {

    private static Result v(final String url) {
        return GitRemoteUrlValidator.validate(url);
    }

    // ---------------------------------------------------------------- accepted

    @Test
    public void acceptsPlainHttpsUrl() {
        final Result r = v("https://github.com/AlexZ005/markor");
        assertThat(r.isValid()).isTrue();
        assertThat(r.getProblem()).isEqualTo(Problem.NONE);
        assertThat(r.getUrl()).isEqualTo("https://github.com/AlexZ005/markor");
        assertThat(r.getHost()).isEqualTo("github.com");
    }

    @Test
    public void acceptsDotGitSuffix() {
        assertThat(v("https://github.com/AlexZ005/markor.git").isValid()).isTrue();
    }

    @Test
    public void acceptsUsernameInUrlWithoutPassword() {
        final Result r = v("https://AlexZ005@github.com/AlexZ005/markor.git");
        assertThat(r.isValid()).isTrue();
        assertThat(r.getHost()).isEqualTo("github.com");
    }

    @Test
    public void acceptsPortAndDeepPath() {
        final Result r = v("https://git.example.org:8443/team/group/notes.git");
        assertThat(r.isValid()).isTrue();
        assertThat(r.getHost()).isEqualTo("git.example.org");
    }

    @Test
    public void acceptsUppercaseSchemeAndLowercasesTheHost() {
        final Result r = v("HTTPS://GitHub.com/AlexZ005/markor.git");
        assertThat(r.isValid()).isTrue();
        assertThat(r.getHost()).isEqualTo("github.com");
    }

    @Test
    public void stripsSurroundingWhitespace() {
        final Result r = v("  \t https://codeberg.org/me/notes.git \n ");
        assertThat(r.isValid()).isTrue();
        assertThat(r.getUrl()).isEqualTo("https://codeberg.org/me/notes.git");
    }

    // ---------------------------------------------------------------- empty

    @Test
    public void rejectsNullAndBlank() {
        assertThat(v(null).getProblem()).isEqualTo(Problem.EMPTY);
        assertThat(v("").getProblem()).isEqualTo(Problem.EMPTY);
        assertThat(v("   \t\n ").getProblem()).isEqualTo(Problem.EMPTY);
        assertThat(v(null).getUrl()).isEmpty();
    }

    // ---------------------------------------------------------------- ssh

    @Test
    public void rejectsSshScheme() {
        assertThat(v("ssh://git@github.com/AlexZ005/markor.git").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
        assertThat(v("SSH://git@github.com/AlexZ005/markor.git").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
        assertThat(v("git+ssh://git@github.com/x/y.git").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
        assertThat(v("ssh+git://git@github.com/x/y.git").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
    }

    @Test
    public void rejectsScpLikeForm() {
        assertThat(v("git@github.com:AlexZ005/markor.git").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
        assertThat(v("github.com:AlexZ005/markor.git").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
        assertThat(v("  git@codeberg.org:me/notes  ").getProblem()).isEqualTo(Problem.SSH_NOT_SUPPORTED);
    }

    // ---------------------------------------------------------------- cleartext http

    @Test
    public void rejectsPlainHttp() {
        assertThat(v("http://github.com/AlexZ005/markor.git").getProblem()).isEqualTo(Problem.CLEARTEXT_HTTP);
        assertThat(v("HTTP://192.168.1.4:3000/me/notes.git").getProblem()).isEqualTo(Problem.CLEARTEXT_HTTP);
    }

    // ---------------------------------------------------------------- other schemes

    @Test
    public void rejectsOtherSchemes() {
        assertThat(v("git://github.com/AlexZ005/markor.git").getProblem()).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(v("file:///sdcard/Documents/notes").getProblem()).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(v("ftp://example.org/x.git").getProblem()).isEqualTo(Problem.UNSUPPORTED_SCHEME);
    }

    @Test
    public void rejectsInputWithoutAnyScheme() {
        assertThat(v("github.com/AlexZ005/markor").getProblem()).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(v("/sdcard/Documents/notes").getProblem()).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(v("./notes").getProblem()).isEqualTo(Problem.UNSUPPORTED_SCHEME);
    }

    // ---------------------------------------------------------------- password in the url

    @Test
    public void rejectsPasswordInUrl() {
        assertThat(v("https://user:ghp_secret@github.com/x/y.git").getProblem()).isEqualTo(Problem.CONTAINS_PASSWORD);
        assertThat(v("https://:ghp_secret@github.com/x/y.git").getProblem()).isEqualTo(Problem.CONTAINS_PASSWORD);
    }

    @Test
    public void resultToStringNeverLeaksTheUrl() {
        assertThat(v("https://user:ghp_secret@github.com/x/y.git").toString()).doesNotContain("ghp_secret").doesNotContain("github.com");
    }

    // ---------------------------------------------------------------- garbage

    @Test
    public void rejectsGarbage() {
        assertThat(v("not a url").getProblem()).isEqualTo(Problem.MALFORMED);
        assertThat(v("https://").getProblem()).isEqualTo(Problem.MALFORMED);
        assertThat(v("https:///no/host.git").getProblem()).isEqualTo(Problem.MALFORMED);
        assertThat(v("://github.com/x").getProblem()).isEqualTo(Problem.MALFORMED);
        assertThat(v("https://git hub.com/x").getProblem()).isEqualTo(Problem.MALFORMED);
    }

    @Test
    public void neverReturnsNull() {
        for (final String s : new String[]{null, "", "x", "https://a.b/c", "ssh://a/b", "::::"}) {
            assertThat(GitRemoteUrlValidator.validate(s)).isNotNull();
        }
    }
}
