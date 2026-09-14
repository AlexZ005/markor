/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bookkeeping for the History list of the Git tab: which commits are loaded, whether another page
 * can follow, and whether a load is already in flight. {@link GitService#log(java.io.File, int, int,
 * GitProgress)} does the reading; this only decides when to ask it for more.
 * <p>
 * Plain Java and unit tested, because "load more on scroll" is the kind of logic that silently
 * loads the same page twice or stops one page early.
 * <p>
 * Not thread safe: the fragment drives it from the main thread.
 */
public final class GitHistoryPager {

    /** Commits per {@code log} call. Big enough to fill a phone screen twice. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * How close to the end of the loaded list the user must scroll before the next page is
     * requested, so the list grows before it runs out under the finger.
     */
    public static final int PREFETCH_DISTANCE = 10;

    private final int _pageSize;
    private final List<GitCommitInfo> _commits = new ArrayList<>();

    private boolean _loading;
    private boolean _hasMore = true;

    public GitHistoryPager() {
        this(DEFAULT_PAGE_SIZE);
    }

    public GitHistoryPager(final int pageSize) {
        _pageSize = Math.max(1, pageSize);
    }

    public int getPageSize() {
        return _pageSize;
    }

    /** @return the commits loaded so far, newest first; unmodifiable */
    public List<GitCommitInfo> getCommits() {
        return Collections.unmodifiableList(_commits);
    }

    public int size() {
        return _commits.size();
    }

    public boolean isEmpty() {
        return _commits.isEmpty();
    }

    public GitCommitInfo get(final int index) {
        return _commits.get(index);
    }

    public boolean isLoading() {
        return _loading;
    }

    /** @return {@code true} while the last page was full, so another one may exist */
    public boolean hasMore() {
        return _hasMore;
    }

    /** @return the {@code skip} argument for the next {@code log} call */
    public int nextSkip() {
        return _commits.size();
    }

    /** Back to "nothing loaded", for a repository switch or a pull that changed the history. */
    public void reset() {
        _commits.clear();
        _loading = false;
        _hasMore = true;
    }

    /**
     * Claims the right to load the next page.
     *
     * @return {@code true} when the caller should now run {@code log(pageSize, nextSkip())};
     * {@code false} when a load is already running or the end of the history was reached
     */
    public boolean beginLoad() {
        if (_loading || !_hasMore) {
            return false;
        }
        _loading = true;
        return true;
    }

    /**
     * Appends a loaded page. A short page (fewer than {@link #getPageSize()} commits) means the end
     * of the history has been reached.
     *
     * @param page the commits {@code log} returned, may be empty
     */
    public void onPageLoaded(final List<GitCommitInfo> page) {
        _loading = false;
        if (page == null || page.isEmpty()) {
            _hasMore = false;
            return;
        }
        _commits.addAll(page);
        _hasMore = page.size() >= _pageSize;
    }

    /**
     * A page failed or was cancelled: keep what is loaded and allow another attempt, so a cancelled
     * refresh does not permanently truncate the list.
     */
    public void onPageFailed() {
        _loading = false;
    }

    /**
     * @param lastVisiblePosition index of the last row the user can see, or -1 for "nothing visible"
     * @return {@code true} when that scroll position should trigger the next page
     */
    public boolean shouldLoadMore(final int lastVisiblePosition) {
        return _hasMore && !_loading && lastVisiblePosition >= _commits.size() - PREFETCH_DISTANCE;
    }
}
