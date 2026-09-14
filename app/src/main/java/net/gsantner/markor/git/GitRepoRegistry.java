/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The repositories the user added to the app, and which one is currently active.
 * <p>
 * State lives in a {@link Store} (in the app: {@link GitSettingsStore} over
 * {@code AppSettings}); the list itself is one JSON string, see {@link GitRepoRegistryCodec}.
 * Nothing here is cached, so two instances over the same store always agree and the settings
 * screen can write the keys directly.
 * <p>
 * Plain Java, no Android imports, so all behaviour below is covered by JVM unit tests.
 * Not thread safe: call it from the UI thread (it only touches settings, never the file system).
 */
public class GitRepoRegistry {

    /**
     * The two settings keys this registry owns. Implemented by {@link GitSettingsStore} on top of
     * {@code AppSettings}; tests use an in-memory implementation.
     */
    public interface Store {
        /**
         * @return the stored JSON, or null / empty when nothing was ever saved
         */
        String getGitRepositoriesJson();

        void setGitRepositoriesJson(String json);

        /**
         * @return the active repository path, or null / empty when there is none
         */
        String getGitActiveRepoPath();

        /**
         * @param path the new active path; null or empty clears it
         */
        void setGitActiveRepoPath(String path);
    }

    private final Store _store;

    public GitRepoRegistry(final Store store) {
        _store = store;
    }

    /**
     * @return all repositories in the order they were added, as an unmodifiable list.
     * Never null; empty if nothing is stored or the stored string is unreadable.
     */
    public List<GitRepoConfig> list() {
        return Collections.unmodifiableList(load());
    }

    public int size() {
        return load().size();
    }

    public boolean isEmpty() {
        return load().isEmpty();
    }

    /**
     * @return the repository registered for {@code path}, or null if there is none.
     * The path is normalized first, so a trailing separator does not matter.
     */
    public GitRepoConfig get(final String path) {
        final String key = GitPaths.normalize(path);
        if (key.isEmpty()) {
            return null;
        }
        for (final GitRepoConfig repo : load()) {
            if (repo.getPath().equals(key)) {
                return repo;
            }
        }
        return null;
    }

    public boolean contains(final String path) {
        return get(path) != null;
    }

    /**
     * Add a repository, or replace the existing entry for the same folder. The first repository
     * added to an empty registry becomes the active one.
     *
     * @return the stored config, or null if {@code repo} was null or had no path
     */
    public GitRepoConfig add(final GitRepoConfig repo) {
        if (repo == null) {
            return null;
        }
        final GitRepoConfig stored = new GitRepoConfig(repo).normalize();
        if (!stored.isValid()) {
            return null;
        }
        final List<GitRepoConfig> repos = load();
        final int existing = indexOf(repos, stored.getPath());
        if (existing >= 0) {
            repos.set(existing, stored);
        } else {
            repos.add(stored);
        }
        save(repos);
        if (getActivePath().isEmpty()) {
            setActive(stored.getPath());
        }
        return stored;
    }

    /**
     * Replace the settings of an already registered repository. Does not add a missing one.
     *
     * @return true if a repository for that path existed and was updated
     */
    public boolean update(final GitRepoConfig repo) {
        if (repo == null) {
            return false;
        }
        final GitRepoConfig stored = new GitRepoConfig(repo).normalize();
        if (!stored.isValid()) {
            return false;
        }
        final List<GitRepoConfig> repos = load();
        final int existing = indexOf(repos, stored.getPath());
        if (existing < 0) {
            return false;
        }
        repos.set(existing, stored);
        save(repos);
        return true;
    }

    /**
     * Forget a repository. Only the app's registration is removed, never anything on disk.
     * Removing the active repository clears the active path; removing any other leaves it alone.
     *
     * @return true if a repository for that path was registered
     */
    public boolean remove(final String path) {
        final String key = GitPaths.normalize(path);
        if (key.isEmpty()) {
            return false;
        }
        final List<GitRepoConfig> repos = load();
        final int existing = indexOf(repos, key);
        if (existing < 0) {
            return false;
        }
        repos.remove(existing);
        save(repos);
        if (key.equals(getActivePath())) {
            _store.setGitActiveRepoPath("");
        }
        return true;
    }

    /**
     * Forget every repository and clear the active one.
     */
    public void clear() {
        save(new ArrayList<>());
        _store.setGitActiveRepoPath("");
    }

    /**
     * @return the active repository, or null when none is set or the stored active path is not (or
     * no longer) registered
     */
    public GitRepoConfig getActive() {
        return get(getActivePath());
    }

    /**
     * @return the stored active path, normalized; empty when there is none. This can name a
     * repository that is no longer registered, use {@link #getActive()} to get a usable config.
     */
    public String getActivePath() {
        return GitPaths.normalize(_store.getGitActiveRepoPath());
    }

    /**
     * Select the active repository.
     *
     * @param path a registered repository path; null or empty clears the selection
     * @return true if the selection was applied, false if no repository is registered for the path
     * (in which case the previous selection is kept)
     */
    public boolean setActive(final String path) {
        final String key = GitPaths.normalize(path);
        if (key.isEmpty()) {
            _store.setGitActiveRepoPath("");
            return true;
        }
        if (!contains(key)) {
            return false;
        }
        _store.setGitActiveRepoPath(key);
        return true;
    }

    /**
     * Normalize a working folder path the way the registry and {@link GitTaskRunner} key on it.
     */
    public static String normalizePath(final String path) {
        return GitPaths.normalize(path);
    }

    private List<GitRepoConfig> load() {
        return GitRepoRegistryCodec.fromJson(_store.getGitRepositoriesJson());
    }

    private void save(final List<GitRepoConfig> repos) {
        _store.setGitRepositoriesJson(GitRepoRegistryCodec.toJson(repos));
    }

    private static int indexOf(final List<GitRepoConfig> repos, final String normalizedPath) {
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).getPath().equals(normalizedPath)) {
                return i;
            }
        }
        return -1;
    }
}
