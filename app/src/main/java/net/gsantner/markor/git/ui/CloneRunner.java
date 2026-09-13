/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.content.Context;

import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitCredentialStore;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitRepoConfig;
import net.gsantner.markor.git.GitRepoInfo;
import net.gsantner.markor.git.GitRepoRegistry;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitSettingsStore;
import net.gsantner.markor.git.GitTaskResult;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.JGitService;

import java.io.File;

/**
 * Owns the one clone that may be running, so that it outlives the dialog that started it.
 * <p>
 * A clone takes long enough that the user will rotate the device or leave the app during it. The
 * {@link CloneDialog} therefore only starts and observes the clone; the work, and everything that
 * must happen when it succeeds — registering the repository, making it the active one and storing
 * the credentials — belongs here, where no view lifecycle can interrupt it. A dialog created after a
 * rotation {@link #setListener attaches} again and sees the current progress, or the result when the
 * clone finished while no one was listening.
 * <p>
 * Everything happens on the main thread: {@link GitTaskRunner} delivers its callback there and
 * {@link GitUiProgress} posts there, so the fields need no locking. Only one clone at a time —
 * {@link #start} refuses while another is running.
 */
final class CloneRunner {

    /** Called on the main thread. */
    interface Listener {
        /** @param percent 0..100, or {@link GitProgress#UNKNOWN} */
        void onCloneProgress(String task, int percent);

        /**
         * @param result the clone's outcome; {@code null} when the worker itself failed unexpectedly
         * @param target the folder that was cloned into
         */
        void onCloneFinished(GitResult<GitRepoInfo> result, File target);
    }

    private static CloneRunner sInstance;

    private Context _appContext;
    private File _target;
    private String _url;
    private String _username;
    private char[] _token;
    private GitCancelToken _cancelToken;
    private boolean _running;

    private String _task;
    private int _percent = GitProgress.UNKNOWN;

    private Listener _listener;
    private GitResult<GitRepoInfo> _undelivered;
    private boolean _hasUndelivered;

    static synchronized CloneRunner get() {
        if (sInstance == null) {
            sInstance = new CloneRunner();
        }
        return sInstance;
    }

    private CloneRunner() {
    }

    boolean isRunning() {
        return _running;
    }

    /** @return the folder of the running clone, or {@code null} */
    File getTarget() {
        return _running ? _target : null;
    }

    String getTask() {
        return _task;
    }

    int getPercent() {
        return _percent;
    }

    /**
     * Starts a clone and takes over the bookkeeping that follows a successful one.
     *
     * @param username may be empty for a public repository
     * @param token    copied; the caller wipes its own array. Empty means "no credentials".
     * @return {@code false} when a clone is already running; nothing was started then
     */
    boolean start(final Context context, final String url, final File target, final String username, final char[] token) {
        if (_running) {
            return false;
        }
        _appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        _url = url;
        _target = target;
        _username = username;
        _token = token == null ? new char[0] : token.clone();
        _task = null;
        _percent = GitProgress.UNKNOWN;
        _hasUndelivered = false;
        _undelivered = null;
        _running = true;

        final GitFixedCredentials credentials = new GitFixedCredentials(
                GitCredentialStore.hostKey(url), username, _token);
        _cancelToken = GitTaskRunner.get().submit(target.getAbsolutePath(),
                cancelToken -> new JGitService().clone(url, target, credentials,
                        new GitUiProgress(cancelToken, this::onProgress)),
                () -> true,
                result -> {
                    credentials.wipe();
                    onFinished(result);
                });
        return true;
    }

    /** Flips the cancel token of the running clone; the result arrives as CANCELLED. */
    void cancel() {
        if (_cancelToken != null) {
            _cancelToken.cancel();
        }
    }

    /**
     * Attaches the observer. A result that arrived while nobody was listening (the dialog was being
     * recreated) is delivered right away, so a clone can never finish unnoticed.
     *
     * @param listener {@code null} to detach
     */
    void setListener(final Listener listener) {
        _listener = listener;
        if (listener != null && _hasUndelivered) {
            _hasUndelivered = false;
            final GitResult<GitRepoInfo> result = _undelivered;
            final File target = _target;
            _undelivered = null;
            listener.onCloneFinished(result, target);
        }
    }

    private void onProgress(final String task, final int percent) {
        _task = task;
        _percent = percent;
        if (_listener != null) {
            _listener.onCloneProgress(task, percent);
        }
    }

    private void onFinished(final GitTaskResult<GitResult<GitRepoInfo>> taskResult) {
        _running = false;
        _cancelToken = null;

        final GitResult<GitRepoInfo> result = taskResult.isSuccess() ? taskResult.getValue() : null;
        if (result != null && result.isOk()) {
            register(result.getValue());
        }
        GitUiText.wipe(_token);
        _token = new char[0];

        if (_listener != null) {
            _listener.onCloneFinished(result, _target);
        } else {
            _undelivered = result;
            _hasUndelivered = true;
        }
    }

    /** Registers the fresh repository, makes it active and remembers the credentials for its host. */
    private void register(final GitRepoInfo info) {
        final File workTree = info != null ? info.getWorkTree() : _target;
        final GitRepoRegistry registry = GitSettingsStore.newRegistry();
        final GitRepoConfig config = new GitRepoConfig(workTree.getAbsolutePath())
                .setRemoteUrl(_url)
                .setAddedEpoch(System.currentTimeMillis());
        if (info != null && info.getBranch() != null) {
            config.setDefaultBranch(info.getBranch());
        }
        registry.add(config);
        registry.setActive(config.getPath());
        if (_token.length > 0 && _username != null && !_username.isEmpty()) {
            GitCredentialStore.get(_appContext).save(_url, _username, _token);
        }
    }
}
