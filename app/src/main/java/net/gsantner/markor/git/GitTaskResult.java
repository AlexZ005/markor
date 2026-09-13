/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.Locale;

/**
 * What a task submitted to {@link GitTaskRunner} produced: a value, a cancellation, or a throwable.
 * Exactly one of the three; a cancelled task is never reported as successful.
 *
 * @param <T> the task's result type
 */
public final class GitTaskResult<T> {

    public enum State {
        SUCCESS, CANCELLED, ERROR
    }

    private final State _state;
    private final T _value;
    private final Throwable _error;

    private GitTaskResult(final State state, final T value, final Throwable error) {
        _state = state;
        _value = value;
        _error = error;
    }

    public static <T> GitTaskResult<T> success(final T value) {
        return new GitTaskResult<>(State.SUCCESS, value, null);
    }

    public static <T> GitTaskResult<T> cancelled() {
        return new GitTaskResult<>(State.CANCELLED, null, null);
    }

    public static <T> GitTaskResult<T> error(final Throwable error) {
        return new GitTaskResult<>(State.ERROR, null, error);
    }

    public State getState() {
        return _state;
    }

    public boolean isSuccess() {
        return _state == State.SUCCESS;
    }

    public boolean isCancelled() {
        return _state == State.CANCELLED;
    }

    public boolean isError() {
        return _state == State.ERROR;
    }

    /**
     * @return the task's value, or null when the task was cancelled or failed
     */
    public T getValue() {
        return _value;
    }

    public T getValueOr(final T fallback) {
        return isSuccess() ? _value : fallback;
    }

    /**
     * @return what the task threw, or null unless {@link #isError()}
     */
    public Throwable getError() {
        return _error;
    }

    /**
     * Never prints the value or an exception message: either can contain a remote URL with a token.
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "GitTaskResult{%s%s}", _state,
                _error == null ? "" : ", " + _error.getClass().getName());
    }
}
