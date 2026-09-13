/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.util.Locale;

/**
 * One git repository known to the app: its working folder plus the per-repository settings.
 * <p>
 * Plain Java (no Android imports) so it can be unit tested on the JVM. Persisted as JSON by
 * {@link GitRepoRegistryCodec}; the JSON names are pinned with {@link SerializedName} so that
 * R8 field renaming in release builds cannot change the stored format.
 * <p>
 * Setters are fluent so a config can be built in one expression.
 */
@SuppressWarnings("unused")
public class GitRepoConfig {

    /**
     * What a pull does when the local branch and its upstream have diverged.
     */
    public enum PullStrategy {
        /**
         * Only fast-forward; a diverged branch is reported instead of being merged.
         */
        FF_ONLY,
        /**
         * Replay local commits on top of the fetched upstream commits.
         */
        REBASE,
        /**
         * Create a merge commit.
         */
        MERGE;

        /**
         * @return the strategy named by {@code value} (case-insensitive), or {@code def} if the
         * value is null, empty or unknown. Used for migration-safe reads.
         */
        public static PullStrategy fromString(final String value, final PullStrategy def) {
            if (value != null) {
                for (final PullStrategy s : values()) {
                    if (s.name().equalsIgnoreCase(value.trim())) {
                        return s;
                    }
                }
            }
            return def;
        }
    }

    public static final PullStrategy DEFAULT_PULL_STRATEGY = PullStrategy.FF_ONLY;
    public static final boolean DEFAULT_FETCH_ON_OPEN = true;

    @SerializedName("path")
    private String _path = "";

    @SerializedName("displayName")
    private String _displayName = "";

    @SerializedName("remoteUrl")
    private String _remoteUrl = null;

    @SerializedName("defaultBranch")
    private String _defaultBranch = null;

    @SerializedName("pullStrategy")
    private PullStrategy _pullStrategy = DEFAULT_PULL_STRATEGY;

    @SerializedName("fetchOnOpen")
    private boolean _fetchOnOpen = DEFAULT_FETCH_ON_OPEN;

    @SerializedName("addedEpoch")
    private long _addedEpoch = 0L;

    /**
     * Required by Gson; field initializers above are the defaults for fields missing from JSON.
     */
    public GitRepoConfig() {
    }

    public GitRepoConfig(final String path) {
        setPath(path);
    }

    public GitRepoConfig(final GitRepoConfig other) {
        if (other != null) {
            _path = other._path;
            _displayName = other._displayName;
            _remoteUrl = other._remoteUrl;
            _defaultBranch = other._defaultBranch;
            _pullStrategy = other._pullStrategy;
            _fetchOnOpen = other._fetchOnOpen;
            _addedEpoch = other._addedEpoch;
        }
    }

    /**
     * The absolute path of the working folder. Never null; empty means "not set" (invalid).
     */
    public String getPath() {
        return _path == null ? "" : _path;
    }

    public GitRepoConfig setPath(final String path) {
        _path = GitPaths.normalize(path);
        return this;
    }

    public File getFile() {
        return new File(getPath());
    }

    /**
     * The name shown in the UI. Falls back to the folder name when none was set.
     */
    public String getDisplayName() {
        if (_displayName != null && !_displayName.trim().isEmpty()) {
            return _displayName.trim();
        }
        final String name = new File(getPath()).getName();
        return name.isEmpty() ? getPath() : name;
    }

    /**
     * @return the display name exactly as stored, without the folder-name fallback. May be empty.
     */
    public String getRawDisplayName() {
        return _displayName == null ? "" : _displayName;
    }

    public GitRepoConfig setDisplayName(final String displayName) {
        _displayName = displayName == null ? "" : displayName.trim();
        return this;
    }

    /**
     * HTTPS remote URL, or null when the repository has no remote configured in the app.
     */
    public String getRemoteUrl() {
        return _remoteUrl;
    }

    public GitRepoConfig setRemoteUrl(final String remoteUrl) {
        _remoteUrl = emptyToNull(remoteUrl);
        return this;
    }

    /**
     * Branch checked out after clone / used as the default upstream, or null for "whatever git says".
     */
    public String getDefaultBranch() {
        return _defaultBranch;
    }

    public GitRepoConfig setDefaultBranch(final String defaultBranch) {
        _defaultBranch = emptyToNull(defaultBranch);
        return this;
    }

    public PullStrategy getPullStrategy() {
        return _pullStrategy == null ? DEFAULT_PULL_STRATEGY : _pullStrategy;
    }

    public GitRepoConfig setPullStrategy(final PullStrategy pullStrategy) {
        _pullStrategy = pullStrategy == null ? DEFAULT_PULL_STRATEGY : pullStrategy;
        return this;
    }

    public boolean isFetchOnOpen() {
        return _fetchOnOpen;
    }

    public GitRepoConfig setFetchOnOpen(final boolean fetchOnOpen) {
        _fetchOnOpen = fetchOnOpen;
        return this;
    }

    /**
     * When the repository was added to the app, in milliseconds since epoch. 0 if unknown.
     */
    public long getAddedEpoch() {
        return _addedEpoch;
    }

    public GitRepoConfig setAddedEpoch(final long addedEpoch) {
        _addedEpoch = addedEpoch;
        return this;
    }

    /**
     * @return true if this config can be used, i.e. it has a working folder path.
     */
    public boolean isValid() {
        return !getPath().isEmpty();
    }

    /**
     * Repair a config that came out of JSON written by another (older or newer) version:
     * normalize the path and replace nulls with the documented defaults.
     */
    GitRepoConfig normalize() {
        _path = GitPaths.normalize(_path);
        _displayName = _displayName == null ? "" : _displayName.trim();
        _remoteUrl = emptyToNull(_remoteUrl);
        _defaultBranch = emptyToNull(_defaultBranch);
        if (_pullStrategy == null) {
            _pullStrategy = DEFAULT_PULL_STRATEGY;
        }
        if (_addedEpoch < 0) {
            _addedEpoch = 0L;
        }
        return this;
    }

    private static String emptyToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Identity is the working folder; two configs for the same folder are the same repository.
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GitRepoConfig)) {
            return false;
        }
        return getPath().equals(((GitRepoConfig) o).getPath());
    }

    @Override
    public int hashCode() {
        return getPath().hashCode();
    }

    /**
     * Never contains the remote URL: it can carry credentials and this may end up in a log.
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "GitRepoConfig{path='%s', pullStrategy=%s, fetchOnOpen=%s}",
                getPath(), getPullStrategy(), isFetchOnOpen());
    }
}
