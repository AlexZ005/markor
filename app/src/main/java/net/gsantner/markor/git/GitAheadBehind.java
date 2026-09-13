/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.Objects;

/**
 * How far the current branch is from its upstream ("↑2 ↓0" in the header). Computed from the
 * local copy of the remote branch, so it is only as fresh as the last fetch. Immutable.
 */
public final class GitAheadBehind {
    private static final GitAheadBehind NONE = new GitAheadBehind(null, 0, 0);

    private final String _upstream;
    private final int _ahead;
    private final int _behind;

    /**
     * @param upstream short name of the compared remote branch, e.g. {@code origin/main}; {@code null} when there is none
     * @param ahead    local commits not on the upstream
     * @param behind   upstream commits not on the local branch
     */
    public GitAheadBehind(final String upstream, final int ahead, final int behind) {
        _upstream = upstream;
        _ahead = ahead;
        _behind = behind;
    }

    /** @return value for a branch without any upstream: {@link #hasUpstream()} is false, counts are 0 */
    public static GitAheadBehind none() {
        return NONE;
    }

    /** @return {@code false} when the branch has no upstream yet (e.g. never pushed); counts are then meaningless */
    public boolean hasUpstream() {
        return _upstream != null;
    }

    /** @return e.g. {@code origin/main}, or {@code null} */
    public String getUpstream() {
        return _upstream;
    }

    public int getAhead() {
        return _ahead;
    }

    public int getBehind() {
        return _behind;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitAheadBehind)) return false;
        final GitAheadBehind that = (GitAheadBehind) o;
        return Objects.equals(_upstream, that._upstream) && _ahead == that._ahead && _behind == that._behind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(_upstream, _ahead, _behind);
    }

    @Override
    public String toString() {
        return _upstream == null ? "no upstream" : _upstream + " ahead " + _ahead + " behind " + _behind;
    }
}
