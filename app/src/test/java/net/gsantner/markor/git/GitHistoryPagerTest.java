/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GitHistoryPagerTest {

    private static final int PAGE = 5;

    private GitHistoryPager _pager;

    @Before
    public void setUp() {
        _pager = new GitHistoryPager(PAGE);
    }

    private static List<GitCommitInfo> page(final int count) {
        final List<GitCommitInfo> commits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            commits.add(new GitCommitInfo("sha" + i, "subject " + i, "", "Tester", "t@example.com", 1_757_000L + i));
        }
        return commits;
    }

    @Test
    public void startsEmptyAndExpectsMore() {
        assertThat(_pager.isEmpty()).isTrue();
        assertThat(_pager.hasMore()).isTrue();
        assertThat(_pager.isLoading()).isFalse();
        assertThat(_pager.nextSkip()).isZero();
    }

    @Test
    public void aFullPageLeavesRoomForAnother() {
        assertThat(_pager.beginLoad()).isTrue();
        _pager.onPageLoaded(page(PAGE));
        assertThat(_pager.size()).isEqualTo(PAGE);
        assertThat(_pager.nextSkip()).isEqualTo(PAGE);
        assertThat(_pager.hasMore()).isTrue();
        assertThat(_pager.isLoading()).isFalse();
    }

    @Test
    public void aShortPageIsTheEndOfTheHistory() {
        _pager.beginLoad();
        _pager.onPageLoaded(page(PAGE - 1));
        assertThat(_pager.hasMore()).isFalse();
        assertThat(_pager.beginLoad()).isFalse();
    }

    @Test
    public void anEmptyPageIsTheEndOfTheHistory() {
        _pager.beginLoad();
        _pager.onPageLoaded(Collections.emptyList());
        assertThat(_pager.isEmpty()).isTrue();
        assertThat(_pager.hasMore()).isFalse();
    }

    @Test
    public void aSecondLoadIsRefusedWhileOneIsRunning() {
        assertThat(_pager.beginLoad()).isTrue();
        assertThat(_pager.beginLoad()).isFalse();
    }

    @Test
    public void pagesAreAppendedNewestFirst() {
        _pager.beginLoad();
        _pager.onPageLoaded(page(PAGE));
        _pager.beginLoad();
        final List<GitCommitInfo> second = page(PAGE);
        _pager.onPageLoaded(second);
        assertThat(_pager.size()).isEqualTo(2 * PAGE);
        assertThat(_pager.get(PAGE).getSha()).isEqualTo(second.get(0).getSha());
    }

    @Test
    public void aFailedPageKeepsWhatIsLoadedAndAllowsARetry() {
        _pager.beginLoad();
        _pager.onPageLoaded(page(PAGE));
        _pager.beginLoad();
        _pager.onPageFailed();
        assertThat(_pager.size()).isEqualTo(PAGE);
        assertThat(_pager.isLoading()).isFalse();
        assertThat(_pager.beginLoad()).isTrue();
    }

    @Test
    public void resetForgetsEverything() {
        _pager.beginLoad();
        _pager.onPageLoaded(page(PAGE - 1));
        _pager.reset();
        assertThat(_pager.isEmpty()).isTrue();
        assertThat(_pager.hasMore()).isTrue();
        assertThat(_pager.nextSkip()).isZero();
    }

    @Test
    public void scrollingNearTheEndAsksForMore() {
        final GitHistoryPager pager = new GitHistoryPager(50);
        pager.beginLoad();
        pager.onPageLoaded(page(50));
        assertThat(pager.shouldLoadMore(10)).isFalse();
        assertThat(pager.shouldLoadMore(50 - GitHistoryPager.PREFETCH_DISTANCE)).isTrue();
    }

    @Test
    public void scrollingDoesNotAskForMoreAtTheEndOfTheHistory() {
        _pager.beginLoad();
        _pager.onPageLoaded(page(2));
        assertThat(_pager.shouldLoadMore(1)).isFalse();
    }

    @Test
    public void scrollingDoesNotStartASecondLoad() {
        _pager.beginLoad();
        _pager.onPageLoaded(page(PAGE));
        _pager.beginLoad();
        assertThat(_pager.shouldLoadMore(PAGE - 1)).isFalse();
    }

    @Test
    public void theDefaultPageSizeIsUsedWhenNoneIsGiven() {
        assertThat(new GitHistoryPager().getPageSize()).isEqualTo(GitHistoryPager.DEFAULT_PAGE_SIZE);
    }
}
