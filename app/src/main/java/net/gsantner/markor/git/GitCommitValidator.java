/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * The rules the commit dialog enforces before it lets a commit through, as plain Java so they can be
 * unit tested without an Activity: the message must carry text and at least one file must be selected.
 * <p>
 * {@link #normalizeMessage(String)} is what actually reaches {@link GitService#commit}: the raw text of
 * the message field with trailing whitespace per line and surrounding blank lines removed, so that a
 * message typed with a stray newline still produces a clean subject line in the history.
 */
public final class GitCommitValidator {

    /** What is wrong with the dialog's current input; the UI maps each to a different reaction. */
    public enum Problem {
        /** Nothing is wrong, the commit may run. */
        NONE,
        /** The message field is empty or whitespace only: show an inline error on the field. */
        EMPTY_MESSAGE,
        /** No file is checked: keep the commit buttons disabled, no error text needed. */
        NOTHING_SELECTED
    }

    /**
     * The outcome of validating message and selection together. Both problems are reported
     * independently because the dialog reacts differently to each.
     */
    public static final class Result {
        private final boolean _emptyMessage;
        private final boolean _nothingSelected;

        private Result(final boolean emptyMessage, final boolean nothingSelected) {
            _emptyMessage = emptyMessage;
            _nothingSelected = nothingSelected;
        }

        /** @return {@code true} when the message is blank */
        public boolean isEmptyMessage() {
            return _emptyMessage;
        }

        /** @return {@code true} when not a single file is checked */
        public boolean isNothingSelected() {
            return _nothingSelected;
        }

        /** @return {@code true} when the commit may run */
        public boolean isValid() {
            return !_emptyMessage && !_nothingSelected;
        }

        /**
         * @return the problem to react to first: a missing selection outranks a missing message,
         * because with nothing selected the buttons are disabled anyway and an error on the
         * untouched message field would only be noise.
         */
        public Problem firstProblem() {
            if (_nothingSelected) {
                return Problem.NOTHING_SELECTED;
            }
            return _emptyMessage ? Problem.EMPTY_MESSAGE : Problem.NONE;
        }

        @Override
        public String toString() {
            return "GitCommitValidator.Result{" + firstProblem() + '}';
        }
    }

    private GitCommitValidator() {
    }

    /**
     * @param message       raw content of the message field, {@code null} allowed
     * @param selectedCount number of checked files
     * @return what, if anything, keeps the commit from running
     */
    public static Result validate(final String message, final int selectedCount) {
        return new Result(!isMessageValid(message), selectedCount <= 0);
    }

    /** @return {@code true} when {@code message} carries at least one non-whitespace character */
    public static boolean isMessageValid(final String message) {
        return !normalizeMessage(message).isEmpty();
    }

    /**
     * Cleans a message for the commit: strips trailing whitespace from every line, drops blank lines at
     * the beginning and the end, and normalizes line endings to {@code \n}. A blank message normalizes
     * to the empty string, which is how {@link #isMessageValid(String)} recognizes it.
     *
     * @param message raw content of the message field, {@code null} allowed
     * @return the cleaned message, never {@code null}
     */
    public static String normalizeMessage(final String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        final String[] lines = message.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int first = 0;
        int last = lines.length - 1;
        for (int i = 0; i < lines.length; i++) {
            lines[i] = trimTrailing(lines[i]);
        }
        while (first <= last && lines[first].trim().isEmpty()) {
            first++;
        }
        while (last >= first && lines[last].trim().isEmpty()) {
            last--;
        }
        if (first > last) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (i > first) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String trimTrailing(final String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }
}
