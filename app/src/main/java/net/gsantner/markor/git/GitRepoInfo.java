/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;
import java.util.Objects;

/**
 * Snapshot of a repository for the Git tab header: where it is, which branch is checked out,
 * whether a merge or rebase is in progress, which remote it talks to. Cheap to compute; does not
 * touch the network or walk the working tree. Immutable.
 */
public final class GitRepoInfo {
    private final File _workTree;
    private final String _branch;
    private final String _headSha;
    private final boolean _detached;
    private final GitRepoState _state;
    private final String _remoteName;
    private final String _remoteUrl;
    private final String _upstream;
    private final long _lastFetchEpochMillis;

    /**
     * @param workTree             root folder of the working tree (the folder containing {@code .git})
     * @param branch               short branch name, e.g. {@code main}; for a detached HEAD the abbreviated commit id
     * @param headSha              full id of the HEAD commit, or {@code null} when the branch has no commits yet
     * @param detached             {@code true} when HEAD does not point to a branch
     * @param state                merge/rebase state
     * @param remoteName           the remote pull/push use, normally {@code origin}; {@code null} when none is configured
     * @param remoteUrl            URL of that remote with any userinfo removed; {@code null} when none
     * @param upstream             short name of the tracked remote branch, e.g. {@code origin/main}; {@code null} when none
     * @param lastFetchEpochMillis time of the last fetch/pull/clone in milliseconds since the epoch, 0 when never
     */
    public GitRepoInfo(final File workTree, final String branch, final String headSha, final boolean detached,
                       final GitRepoState state, final String remoteName, final String remoteUrl,
                       final String upstream, final long lastFetchEpochMillis) {
        _workTree = Objects.requireNonNull(workTree, "workTree");
        _branch = branch;
        _headSha = headSha;
        _detached = detached;
        _state = Objects.requireNonNull(state, "state");
        _remoteName = remoteName;
        _remoteUrl = remoteUrl;
        _upstream = upstream;
        _lastFetchEpochMillis = lastFetchEpochMillis;
    }

    /** @return the folder containing {@code .git}; pass this (or any path inside it) to the other service methods */
    public File getWorkTree() {
        return _workTree;
    }

    /** @return display name of the repository: the work tree folder name */
    public String getName() {
        return _workTree.getName();
    }

    /** @return short branch name, or the abbreviated commit id when {@link #isDetached()} */
    public String getBranch() {
        return _branch;
    }

    /** @return full HEAD commit id, or {@code null} for a repository without commits */
    public String getHeadSha() {
        return _headSha;
    }

    /** @return {@code true} when the repository has no commits yet (fresh {@code init}) */
    public boolean isEmpty() {
        return _headSha == null;
    }

    public boolean isDetached() {
        return _detached;
    }

    public GitRepoState getState() {
        return _state;
    }

    /** @return e.g. {@code origin}, or {@code null} when no remote is configured */
    public String getRemoteName() {
        return _remoteName;
    }

    /** @return remote URL without username or password, or {@code null}. Safe to display and log. */
    public String getRemoteUrl() {
        return _remoteUrl;
    }

    public boolean hasRemote() {
        return _remoteUrl != null;
    }

    /** @return e.g. {@code origin/main}, or {@code null} when the branch tracks nothing */
    public String getUpstream() {
        return _upstream;
    }

    /** @return epoch millis of the last fetch, pull or clone; 0 when unknown */
    public long getLastFetchEpochMillis() {
        return _lastFetchEpochMillis;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitRepoInfo)) return false;
        final GitRepoInfo that = (GitRepoInfo) o;
        return _workTree.equals(that._workTree) && Objects.equals(_branch, that._branch)
                && Objects.equals(_headSha, that._headSha) && _detached == that._detached && _state == that._state
                && Objects.equals(_remoteName, that._remoteName) && Objects.equals(_remoteUrl, that._remoteUrl)
                && Objects.equals(_upstream, that._upstream) && _lastFetchEpochMillis == that._lastFetchEpochMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(_workTree, _branch, _headSha, _detached, _state, _remoteName, _remoteUrl, _upstream, _lastFetchEpochMillis);
    }

    @Override
    public String toString() {
        return "GitRepoInfo{" + _workTree.getName() + " (" + _branch + (_detached ? ", detached" : "") + "), " + _state
                + (_remoteUrl != null ? ", " + _remoteName + "=" + _remoteUrl : ", no remote")
                + (_upstream != null ? ", tracks " + _upstream : "") + '}';
    }
}
