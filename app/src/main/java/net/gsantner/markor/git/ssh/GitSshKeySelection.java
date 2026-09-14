/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import net.gsantner.markor.git.GitRepoConfig;

/**
 * The one place that decides which SSH key a repository authenticates with (roadmap task 8.1b):
 * the key the repository names, otherwise the app-wide default.
 * <p>
 * It is deliberately the only answer to that question, so that the transport (task 8.1c), the
 * remote dialog and the Git tab cannot drift apart about which identity a repository uses.
 */
public final class GitSshKeySelection {

    private GitSshKeySelection() {
    }

    /**
     * @param repo  the repository, may be null
     * @param store the key store, may be null
     * @return the key to use, or {@code null} when there is none to use
     */
    public static GitSshKey resolve(final GitRepoConfig repo, final GitSshKeyStore store) {
        if (store == null) {
            return null;
        }
        final String pinned = repo == null ? null : repo.getSshKeyId();
        if (pinned != null) {
            // A repository that names a key and no longer has it is NOT quietly given the default
            // one: which key is offered to a remote host is the user's decision, and substituting
            // another identity behind their back is exactly what the 7.5 security review refuses to
            // do elsewhere. The caller reports "the selected key is gone" and the user picks again.
            return store.get(pinned);
        }
        return store.getDefault();
    }

    /**
     * @param repo  the repository, may be null
     * @param store the key store, may be null
     * @return {@code true} when the repository names a key that the store does not have any more
     */
    public static boolean isSelectedKeyMissing(final GitRepoConfig repo, final GitSshKeyStore store) {
        final String pinned = repo == null ? null : repo.getSshKeyId();
        return pinned != null && (store == null || store.get(pinned) == null);
    }
}
