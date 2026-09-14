/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class GitFixedCredentialsTest {

    private static char[] token() {
        return new char[]{'g', 'h', 'p', '_', 'x'};
    }

    @Test
    public void servesTheHostItWasMadeFor() {
        final GitFixedCredentials c = new GitFixedCredentials("github.com", "AlexZ005", token());
        assertThat(c.getUsername("github.com")).isEqualTo("AlexZ005");
        assertThat(c.getSecret("github.com")).containsExactly(token());
    }

    @Test
    public void matchesTheHostCaseInsensitively() {
        final GitFixedCredentials c = new GitFixedCredentials("GitHub.com", "me", token());
        assertThat(c.getSecret("github.com")).isNotNull();
        assertThat(c.getSecret(" GITHUB.COM ")).isNotNull();
    }

    @Test
    public void neverHandsTheSecretToAnotherHost() {
        final GitFixedCredentials c = new GitFixedCredentials("github.com", "me", token());
        assertThat(c.getSecret("evil.example.org")).isNull();
        assertThat(c.getUsername("evil.example.org")).isNull();
        assertThat(c.getSecret(null)).isNull();
    }

    @Test
    public void returnsAFreshCopyEachTime() {
        final GitFixedCredentials c = new GitFixedCredentials("github.com", "me", token());
        final char[] first = c.getSecret("github.com");
        java.util.Arrays.fill(first, '\0');
        assertThat(c.getSecret("github.com")).containsExactly(token());
    }

    @Test
    public void copiesTheCallersArraySoWipingItDoesNotMatter() {
        final char[] mine = token();
        final GitFixedCredentials c = new GitFixedCredentials("github.com", "me", mine);
        java.util.Arrays.fill(mine, '\0');
        assertThat(c.getSecret("github.com")).containsExactly(token());
    }

    @Test
    public void wipeStopsHandingOutTheSecret() {
        final GitFixedCredentials c = new GitFixedCredentials("github.com", "me", token());
        c.wipe();
        assertThat(c.getSecret("github.com")).isNull();
    }

    @Test
    public void anEmptySecretIsNoSecret() {
        final GitFixedCredentials c = new GitFixedCredentials("github.com", "me", new char[0]);
        assertThat(c.getSecret("github.com")).isNull();
        assertThat(c.getUsername("github.com")).isEqualTo("me");
    }

    @Test
    public void aNullHostServesEveryone() {
        final GitFixedCredentials c = new GitFixedCredentials(null, "me", token());
        assertThat(c.getSecret("anything.example.org")).isNotNull();
    }

    @Test
    public void toStringNeverLeaksTheSecret() {
        assertThat(new GitFixedCredentials("github.com", "me", token()).toString()).doesNotContain("ghp_");
    }
}
