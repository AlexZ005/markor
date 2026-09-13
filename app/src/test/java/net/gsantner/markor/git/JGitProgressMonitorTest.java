/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.lib.ProgressMonitor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class JGitProgressMonitorTest {

    private static final class Capture implements GitProgress {
        final List<String> begins = new ArrayList<>();
        final List<Integer> percents = new ArrayList<>();
        final List<Integer> totals = new ArrayList<>();
        int ends;
        boolean cancel;

        @Override
        public void onTaskBegin(final String task, final int totalWork) {
            begins.add(task + "/" + totalWork);
        }

        @Override
        public void onTaskProgress(final String task, final int completedWork, final int totalWork, final int percent) {
            percents.add(percent);
            totals.add(totalWork);
        }

        @Override
        public void onTaskEnd(final String task) {
            ends++;
        }

        @Override
        public boolean isCancelled() {
            return cancel;
        }
    }

    @Test
    public void throttlesToOncePerPercentAndFinishesAt100() {
        final Capture c = new Capture();
        final JGitProgressMonitor m = new JGitProgressMonitor(c);
        m.start(1);
        m.beginTask("Receiving objects", 1000);
        for (int i = 0; i < 1000; i++) {
            m.update(1);
        }
        m.endTask();
        assertThat(c.begins).containsExactly("Receiving objects/1000");
        assertThat(c.percents.size()).isBetween(90, 101);
        assertThat(c.percents.get(c.percents.size() - 1)).isEqualTo(100);
        assertThat(c.percents).isSorted();
        assertThat(c.ends).isEqualTo(1);
    }

    @Test
    public void unknownTotalReportsUnknownPercent() {
        final Capture c = new Capture();
        final JGitProgressMonitor m = new JGitProgressMonitor(c);
        m.beginTask("Resolving deltas", ProgressMonitor.UNKNOWN);
        m.update(5);
        m.update(5);
        m.endTask();
        assertThat(c.begins).containsExactly("Resolving deltas/" + GitProgress.UNKNOWN);
        assertThat(c.percents).isNotEmpty().containsOnly(GitProgress.UNKNOWN);
        assertThat(c.totals).containsOnly(GitProgress.UNKNOWN);
        assertThat(c.ends).isEqualTo(1);
    }

    @Test
    public void forwardsCancellationAndToleratesNullProgress() {
        final Capture c = new Capture();
        final JGitProgressMonitor m = new JGitProgressMonitor(c);
        assertThat(m.isCancelled()).isFalse();
        c.cancel = true;
        assertThat(m.isCancelled()).isTrue();

        final JGitProgressMonitor none = new JGitProgressMonitor(null);
        none.beginTask("x", 3);
        none.update(3);
        none.endTask();
        none.update(1); // after endTask: ignored
        assertThat(none.isCancelled()).isFalse();
    }
}
