/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.File;

/**
 * Reads and writes the {@code [user]} section of a repository's {@code .git/config}.
 * <p>
 * {@link GitService#commit} takes the author per call and deliberately does not persist it, but a user
 * who later works on the same folder from a desktop expects {@code user.name} and {@code user.email} to
 * be there. The commit dialog therefore writes the identity it just asked for into the repository once,
 * and reads it back to pre-fill the identity prompt for a repository that a desktop git already
 * configured.
 * <p>
 * Never touches the global configuration, and never overwrites an identity the repository already has
 * unless asked to ({@link #write(File, GitAuthor, boolean)}).
 */
public final class GitAuthorConfig {

    private static final String SECTION_USER = "user";
    private static final String KEY_NAME = "name";
    private static final String KEY_EMAIL = "email";

    private GitAuthorConfig() {
    }

    /**
     * @param repoDir any path inside the working tree
     * @return the identity recorded in this repository's own configuration, or {@code null} when it has
     * none, when only one of the two values is set, or when the path is not a repository. Values
     * inherited from the user's global configuration are not reported, because Android has none.
     */
    public static GitAuthor read(final File repoDir) {
        try (Repository repo = JGitRepos.open(repoDir)) {
            final StoredConfig config = repo.getConfig();
            final String name = config.getString(SECTION_USER, null, KEY_NAME);
            final String email = config.getString(SECTION_USER, null, KEY_EMAIL);
            if (name == null || name.trim().isEmpty() || email == null || email.trim().isEmpty()) {
                return null;
            }
            return new GitAuthor(name, email);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Writes the identity into {@code .git/config} unless the repository already has one.
     *
     * @return {@code true} when the configuration was changed
     */
    public static boolean write(final File repoDir, final GitAuthor author) {
        return write(repoDir, author, false);
    }

    /**
     * @param repoDir   any path inside the working tree
     * @param author    identity to record; {@code null} does nothing
     * @param overwrite {@code true} to replace an identity the repository already has
     * @return {@code true} when the configuration was changed, {@code false} when there was nothing to
     * do or the write failed (this is a convenience, never a reason to fail a commit)
     */
    public static boolean write(final File repoDir, final GitAuthor author, final boolean overwrite) {
        if (author == null) {
            return false;
        }
        try (Repository repo = JGitRepos.open(repoDir)) {
            final StoredConfig config = repo.getConfig();
            final String name = config.getString(SECTION_USER, null, KEY_NAME);
            final String email = config.getString(SECTION_USER, null, KEY_EMAIL);
            final boolean hasIdentity = name != null && !name.trim().isEmpty()
                    && email != null && !email.trim().isEmpty();
            if (hasIdentity && !overwrite) {
                return false;
            }
            config.setString(SECTION_USER, null, KEY_NAME, author.getName());
            config.setString(SECTION_USER, null, KEY_EMAIL, author.getEmail());
            config.save();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
