/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitProgress;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class GitUiProgressTest {

    private static final Executor DIRECT = Runnable::run;

    private List<String> _seen;
    private GitUiProgress.Listener _listener;

    @Before
    public void setUp() {
        _seen = new ArrayList<>();
        _listener = (task, percent) -> _seen.add(task + "@" + percent);
    }

    @Test
    public void forwardsTheStartOfATaskWithAKnownTotal() {
        final GitUiProgress p = new GitUiProgress(null, _listener, DIRECT);
        p.onTaskBegin("Receiving objects", 500);
        assertThat(_seen).containsExactly("Receiving objects@0");
    }

    @Test
    public void forwardsAnIndeterminateTaskAsUnknown() {
        final GitUiProgress p = new GitUiProgress(null, _listener, DIRECT);
        p.onTaskBegin("Counting objects", GitProgress.UNKNOWN);
        assertThat(_seen).containsExactly("Counting objects@" + GitProgress.UNKNOWN);
    }

    @Test
    public void collapsesRepeatsOfTheSameTaskAndPercent() {
        final GitUiProgress p = new GitUiProgress(null, _listener, DIRECT);
        p.onTaskBegin("Receiving objects", 1000);
        for (int i = 0; i < 300; i++) {
            p.onTaskProgress("Receiving objects", i, 1000, i / 10);
        }
        // 0 comes from onTaskBegin, then one update per whole percent: 1..29.
        assertThat(_seen).hasSize(30);
        assertThat(_seen.get(0)).isEqualTo("Receiving objects@0");
        assertThat(_seen.get(29)).isEqualTo("Receiving objects@29");
    }

    @Test
    public void forwardsTheSamePercentAgainWhenTheTaskChanged() {
        final GitUiProgress p = new GitUiProgress(null, _listener, DIRECT);
        p.onTaskProgress("Receiving objects", 50, 100, 50);
        p.onTaskProgress("Resolving deltas", 50, 100, 50);
        assertThat(_seen).containsExactly("Receiving objects@50", "Resolving deltas@50");
    }

    @Test
    public void aNullListenerCostsNothing() {
        final GitUiProgress p = new GitUiProgress(null, null, DIRECT);
        p.onTaskBegin("Receiving objects", 100);
        p.onTaskProgress("Receiving objects", 1, 100, 1);
        p.onTaskEnd("Receiving objects");
        assertThat(_seen).isEmpty();
    }

    @Test
    public void reportsCancellationFromTheToken() {
        final GitCancelToken token = new GitCancelToken();
        final GitUiProgress p = new GitUiProgress(token, _listener, DIRECT);
        assertThat(p.isCancelled()).isFalse();
        token.cancel();
        assertThat(p.isCancelled()).isTrue();
    }

    @Test
    public void isNeverCancelledWithoutAToken() {
        assertThat(new GitUiProgress(null, _listener, DIRECT).isCancelled()).isFalse();
    }
}
