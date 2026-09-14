/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.concurrent.TimeUnit;

/**
 * Turns "when did this happen" into the coarse bucket the Git tab header shows
 * ("synced just now", "synced 5 min ago", "synced 2 h ago", "synced 3 d ago").
 * <p>
 * Only the arithmetic lives here; the wording is picked by the fragment from {@code strings.xml},
 * so this class stays plain Java and unit testable. The history list does not use it — commit rows
 * use {@code DateUtils.getRelativeTimeSpanString}, which is localized by the platform.
 */
public final class GitRelativeTime {

    /** Below this a moment is "just now"; a fetch that just finished should not read "0 min ago". */
    public static final long JUST_NOW_MILLIS = TimeUnit.MINUTES.toMillis(1);

    public enum Unit {
        /** No timestamp at all (never fetched), or a timestamp in the future. */
        NEVER,
        /** Less than {@link #JUST_NOW_MILLIS} ago; {@link Label#getValue()} is 0. */
        JUST_NOW,
        MINUTES,
        HOURS,
        DAYS
    }

    /** A bucket and the number that goes with it, e.g. {@code MINUTES} and {@code 5}. */
    public static final class Label {
        private final Unit _unit;
        private final int _value;

        Label(final Unit unit, final int value) {
            _unit = unit;
            _value = value;
        }

        public Unit getUnit() {
            return _unit;
        }

        /** @return the count for {@link Unit#MINUTES}, {@link Unit#HOURS} and {@link Unit#DAYS}, otherwise 0 */
        public int getValue() {
            return _value;
        }

        @Override
        public String toString() {
            return _unit + (_value == 0 ? "" : "(" + _value + ")");
        }
    }

    private static final Label NEVER = new Label(Unit.NEVER, 0);
    private static final Label JUST_NOW = new Label(Unit.JUST_NOW, 0);

    /**
     * @param thenMillis when it happened, epoch milliseconds; {@code <= 0} means "never"
     * @param nowMillis  the current time, epoch milliseconds
     * @return the bucket to render. A timestamp in the future is reported as {@link Unit#NEVER}
     * rather than as a negative age, because a clock that jumped is not something to display.
     */
    public static Label of(final long thenMillis, final long nowMillis) {
        if (thenMillis <= 0 || thenMillis > nowMillis) {
            return NEVER;
        }
        final long age = nowMillis - thenMillis;
        if (age < JUST_NOW_MILLIS) {
            return JUST_NOW;
        }
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(age);
        if (minutes < 60) {
            return new Label(Unit.MINUTES, (int) minutes);
        }
        final long hours = TimeUnit.MILLISECONDS.toHours(age);
        if (hours < 24) {
            return new Label(Unit.HOURS, (int) hours);
        }
        final long days = TimeUnit.MILLISECONDS.toDays(age);
        return new Label(Unit.DAYS, (int) Math.min(days, Integer.MAX_VALUE));
    }

    private GitRelativeTime() {
    }
}
