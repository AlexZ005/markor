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

public class GitFetchThrottleTest {

    private static final String REPO = "/storage/emulated/0/Documents/notes";
    private static final String OTHER = "/storage/emulated/0/Documents/other";
    private static final long NOW = 1_757_000_000_000L;
    private static final long TEN_MINUTES = GitFetchThrottle.DEFAULT_INTERVAL_MILLIS;

    private GitFetchThrottle _throttle;

    @Before
    public void setUp() {
        _throttle = new GitFetchThrottle();
    }

    @Test
    public void fetchesWhenNothingIsKnownYet() {
        assertThat(_throttle.shouldFetch(REPO, true, true, 0, NOW)).isTrue();
    }

    @Test
    public void neverFetchesWhenTheSettingIsOff() {
        assertThat(_throttle.shouldFetch(REPO, false, true, 0, NOW)).isFalse();
    }

    @Test
    public void neverFetchesWithoutANetwork() {
        assertThat(_throttle.shouldFetch(REPO, true, false, 0, NOW)).isFalse();
    }

    @Test
    public void neverFetchesWithoutARepositoryPath() {
        assertThat(_throttle.shouldFetch(null, true, true, 0, NOW)).isFalse();
        assertThat(_throttle.shouldFetch("", true, true, 0, NOW)).isFalse();
    }

    @Test
    public void aRecentSuccessfulFetchBlocksTheNextOne() {
        assertThat(_throttle.shouldFetch(REPO, true, true, NOW - TEN_MINUTES + 1000, NOW)).isFalse();
    }

    @Test
    public void anOldFetchDoesNotBlock() {
        assertThat(_throttle.shouldFetch(REPO, true, true, NOW - TEN_MINUTES - 1, NOW)).isTrue();
    }

    @Test
    public void aFetchInTheFutureDoesNotBlockForever() {
        // A clock that jumped backwards would otherwise disable fetch-on-open until the interval passes.
        assertThat(_throttle.shouldFetch(REPO, true, true, NOW + TEN_MINUTES, NOW)).isTrue();
    }

    @Test
    public void anAttemptBlocksEvenWhenItDidNotSucceed() {
        _throttle.recordAttempt(REPO, NOW);
        // lastFetchMillis stays 0: the fetch failed, so the repository's own timestamp did not move.
        assertThat(_throttle.shouldFetch(REPO, true, true, 0, NOW + 1000)).isFalse();
        assertThat(_throttle.shouldFetch(REPO, true, true, 0, NOW + TEN_MINUTES)).isTrue();
    }

    @Test
    public void attemptsAreCountedPerRepository() {
        _throttle.recordAttempt(REPO, NOW);
        assertThat(_throttle.shouldFetch(OTHER, true, true, 0, NOW)).isTrue();
    }

    @Test
    public void resettingLetsAnExplicitFetchThrough() {
        _throttle.recordAttempt(REPO, NOW);
        _throttle.reset(REPO);
        assertThat(_throttle.shouldFetch(REPO, true, true, 0, NOW)).isTrue();
    }

    @Test
    public void theIntervalIsConfigurable() {
        final GitFetchThrottle fast = new GitFetchThrottle(1000);
        assertThat(fast.getIntervalMillis()).isEqualTo(1000);
        fast.recordAttempt(REPO, NOW);
        assertThat(fast.shouldFetch(REPO, true, true, 0, NOW + 1001)).isTrue();
    }
}
