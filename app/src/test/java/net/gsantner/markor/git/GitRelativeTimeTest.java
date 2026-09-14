/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.GitRelativeTime.Label;
import net.gsantner.markor.git.GitRelativeTime.Unit;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class GitRelativeTimeTest {

    private static final long NOW = 1_757_000_000_000L;

    private static Label ago(final long amount, final TimeUnit unit) {
        return GitRelativeTime.of(NOW - unit.toMillis(amount), NOW);
    }

    @Test
    public void noTimestampIsNever() {
        assertThat(GitRelativeTime.of(0, NOW).getUnit()).isEqualTo(Unit.NEVER);
        assertThat(GitRelativeTime.of(-1, NOW).getUnit()).isEqualTo(Unit.NEVER);
    }

    @Test
    public void aTimestampInTheFutureIsNeverRatherThanANegativeAge() {
        assertThat(GitRelativeTime.of(NOW + 5000, NOW).getUnit()).isEqualTo(Unit.NEVER);
    }

    @Test
    public void freshlyFetchedReadsAsJustNow() {
        assertThat(GitRelativeTime.of(NOW, NOW).getUnit()).isEqualTo(Unit.JUST_NOW);
        assertThat(ago(59, TimeUnit.SECONDS).getUnit()).isEqualTo(Unit.JUST_NOW);
    }

    @Test
    public void minutesStartAtOneMinute() {
        final Label label = ago(1, TimeUnit.MINUTES);
        assertThat(label.getUnit()).isEqualTo(Unit.MINUTES);
        assertThat(label.getValue()).isEqualTo(1);
    }

    @Test
    public void minutesRunUpToFiftyNine() {
        assertThat(ago(59, TimeUnit.MINUTES).getUnit()).isEqualTo(Unit.MINUTES);
        assertThat(ago(59, TimeUnit.MINUTES).getValue()).isEqualTo(59);
    }

    @Test
    public void anHourBecomesHours() {
        final Label label = ago(90, TimeUnit.MINUTES);
        assertThat(label.getUnit()).isEqualTo(Unit.HOURS);
        assertThat(label.getValue()).isEqualTo(1);
    }

    @Test
    public void aDayBecomesDays() {
        final Label label = ago(50, TimeUnit.HOURS);
        assertThat(label.getUnit()).isEqualTo(Unit.DAYS);
        assertThat(label.getValue()).isEqualTo(2);
    }

    @Test
    public void twentyThreeHoursIsStillHours() {
        assertThat(ago(23, TimeUnit.HOURS).getUnit()).isEqualTo(Unit.HOURS);
        assertThat(ago(23, TimeUnit.HOURS).getValue()).isEqualTo(23);
    }
}
