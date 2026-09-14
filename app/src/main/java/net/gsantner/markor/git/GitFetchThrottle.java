/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides whether opening the Git tab should fetch (roadmap task 5.5). Opening a tab must never
 * become a network request per tap, so a repository is fetched at most once per
 * {@link #DEFAULT_INTERVAL_MILLIS}, only when its configuration asks for it and only when the
 * device has a network.
 * <p>
 * Two clocks are consulted: the repository's own last successful fetch (which survives process
 * death, it is stored in the repository) and the last <i>attempt</i> in this process. The second one
 * exists because a failed fetch does not move the first one; without it an offline-ish network would
 * retry on every tab switch.
 * <p>
 * Not thread safe: call it from the main thread, as the fragment does.
 */
public final class GitFetchThrottle {

    /** Roadmap task 5.5: "at most once per 10 minutes". */
    public static final long DEFAULT_INTERVAL_MILLIS = 10L * 60L * 1000L;

    private final long _intervalMillis;
    private final Map<String, Long> _lastAttemptMillis = new HashMap<>();

    public GitFetchThrottle() {
        this(DEFAULT_INTERVAL_MILLIS);
    }

    public GitFetchThrottle(final long intervalMillis) {
        _intervalMillis = intervalMillis;
    }

    public long getIntervalMillis() {
        return _intervalMillis;
    }

    /**
     * @param repoPath            key of the repository, normally {@link GitRepoConfig#getPath()}
     * @param fetchOnOpenEnabled  {@link GitRepoConfig#isFetchOnOpen()}
     * @param online              whether the device has a network right now
     * @param lastFetchMillis     {@link GitRepoInfo#getLastFetchEpochMillis()}, 0 when never fetched
     * @param nowMillis           current time
     * @return {@code true} when a background fetch should be started now
     */
    public boolean shouldFetch(final String repoPath, final boolean fetchOnOpenEnabled, final boolean online,
                               final long lastFetchMillis, final long nowMillis) {
        if (repoPath == null || repoPath.isEmpty() || !fetchOnOpenEnabled || !online) {
            return false;
        }
        if (isRecent(lastFetchMillis, nowMillis)) {
            return false;
        }
        final Long attempt = _lastAttemptMillis.get(repoPath);
        return attempt == null || !isRecent(attempt, nowMillis);
    }

    /** Records that a fetch was started for {@code repoPath}; call this right before submitting it. */
    public void recordAttempt(final String repoPath, final long nowMillis) {
        if (repoPath != null && !repoPath.isEmpty()) {
            _lastAttemptMillis.put(repoPath, nowMillis);
        }
    }

    /** Forgets the throttle for one repository, so that an explicit "Fetch" is never blocked afterwards. */
    public void reset(final String repoPath) {
        if (repoPath != null) {
            _lastAttemptMillis.remove(repoPath);
        }
    }

    /** A timestamp counts as recent while it is inside the interval; one in the future is ignored. */
    private boolean isRecent(final long timestampMillis, final long nowMillis) {
        return timestampMillis > 0 && timestampMillis <= nowMillis && nowMillis - timestampMillis < _intervalMillis;
    }
}
