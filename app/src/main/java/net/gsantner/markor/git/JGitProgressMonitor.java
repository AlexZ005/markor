/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * Adapts JGit's {@link ProgressMonitor} to {@link GitProgress}: forwards task begin/end (always
 * balanced, even where JGit skips {@code endTask}), throttles updates to once per percent change
 * (JGit calls {@code update} per object), and forwards cancellation so transfers stop.
 * Package-private; one instance per operation.
 */
final class JGitProgressMonitor implements ProgressMonitor {
    private final GitProgress _progress;
    private String _task;
    private int _total;
    private int _completed;
    private int _lastPercent = Integer.MIN_VALUE;

    JGitProgressMonitor(final GitProgress progress) {
        _progress = progress == null ? GitProgress.NONE : progress;
    }

    GitProgress getProgress() {
        return _progress;
    }

    @Override
    public void start(final int totalTasks) {
    }

    @Override
    public void beginTask(final String title, final int totalWork) {
        if (_task != null) {
            // JGit does not always pair beginTask with endTask; keep the UI's begin/end balanced.
            endTask();
        }
        _task = title == null ? "" : title;
        _total = totalWork == ProgressMonitor.UNKNOWN ? GitProgress.UNKNOWN : totalWork;
        _completed = 0;
        _lastPercent = Integer.MIN_VALUE;
        _progress.onTaskBegin(_task, _total);
    }

    @Override
    public void update(final int completed) {
        if (_task == null) {
            return;
        }
        _completed += completed;
        final int percent = _total > 0 ? (int) Math.min(100L, (100L * _completed) / _total) : GitProgress.UNKNOWN;
        if (percent != _lastPercent || (_total <= 0 && completed > 0)) {
            _lastPercent = percent;
            _progress.onTaskProgress(_task, _completed, _total, percent);
        }
    }

    @Override
    public void endTask() {
        if (_task == null) {
            return;
        }
        if (_total > 0 && _lastPercent != 100) {
            _progress.onTaskProgress(_task, _completed, _total, _lastPercent < 0 ? 0 : Math.min(100, _lastPercent));
        }
        _progress.onTaskEnd(_task);
        _task = null;
    }

    @Override
    public boolean isCancelled() {
        return _progress.isCancelled();
    }
}
