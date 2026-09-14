/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * Test-flavour only. {@link JGitErrors} is package-private, and the SSH spike screen
 * ({@code net.gsantner.markor.git.spike.GitSshSpikeActivity}, roadmap task 8.1a) has to show which
 * {@link GitResult.Kind} a real SSH failure is classified as. This one-line bridge lives in the same
 * package but in the flavorAtest source set, so nothing in a shipping build sees it.
 */
public final class GitSshSpikeErrorBridge {

    private GitSshSpikeErrorBridge() {
    }

    /** @return what {@code JGitErrors.map} makes of {@code e}, with no progress and no repository */
    public static GitResult<Void> classify(final Exception e) {
        return JGitErrors.map(e, null, null);
    }
}
