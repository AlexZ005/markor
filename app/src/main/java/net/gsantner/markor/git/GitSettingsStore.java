/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import net.gsantner.markor.ApplicationObject;
import net.gsantner.markor.model.AppSettings;

/**
 * The app's {@link GitRepoRegistry.Store}: the two git settings keys in {@link AppSettings}.
 * Keeping this adapter separate is what lets {@link GitRepoRegistry} stay free of Android imports.
 */
public class GitSettingsStore implements GitRepoRegistry.Store {

    private final AppSettings _appSettings;

    public GitSettingsStore(final AppSettings appSettings) {
        _appSettings = appSettings;
    }

    /**
     * @return a registry backed by the app-wide settings
     */
    public static GitRepoRegistry newRegistry() {
        return new GitRepoRegistry(new GitSettingsStore(ApplicationObject.settings()));
    }

    @Override
    public String getGitRepositoriesJson() {
        return _appSettings.getGitRepositoriesJson();
    }

    @Override
    public void setGitRepositoriesJson(final String json) {
        _appSettings.setGitRepositoriesJson(json);
    }

    @Override
    public String getGitActiveRepoPath() {
        return _appSettings.getGitActiveRepoPath();
    }

    @Override
    public void setGitActiveRepoPath(final String path) {
        _appSettings.setGitActiveRepoPath(path);
    }
}
