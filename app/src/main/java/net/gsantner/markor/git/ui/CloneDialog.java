/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.git.GitCredentialStore;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitRepoInfo;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.util.MarkorContextUtils;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;

import java.io.File;

/**
 * Clones a remote repository into an empty folder (roadmap task 5.2): URL, target folder picked with
 * {@link MarkorFileBrowserFactory#showFolderDialog}, optional credentials, and a progress view with
 * a Cancel button while the clone runs.
 * <p>
 * The folder must be empty and must not be reached through the Storage Access Framework: JGit works
 * on {@code java.io.File} and cannot write into an SAF mount (decision D6), so such a folder is
 * refused with an explanation rather than failing halfway through the clone.
 * <p>
 * The clone itself belongs to {@link CloneRunner}, which survives this dialog. On success it
 * registers the repository, makes it the active one and stores the credentials; on failure or
 * cancellation the target folder is left exactly as {@code GitService.clone} left it (it removes a
 * partial clone itself) and the reason is shown. Only then does this dialog tell its listener and
 * close.
 * <p>
 * Usage:
 * <pre>
 * CloneDialog.newInstance()
 *         .setListener(repoRoot -&gt; openGitTabFor(repoRoot))
 *         .show(getParentFragmentManager(), CloneDialog.FRAGMENT_TAG);
 * </pre>
 */
public class CloneDialog extends DialogFragment {

    public static final String FRAGMENT_TAG = CloneDialog.class.getName();
    private static final String EXTRA_URL = "EXTRA_URL";
    private static final String EXTRA_FOLDER = "EXTRA_FOLDER";
    private static final String STATE_FOLDER = "STATE_FOLDER";
    private static final String STATE_RUN_ID = "STATE_RUN_ID";

    /** Told when a clone finished successfully; called on the main thread, just before the dialog closes. */
    public interface Listener {
        /** @param repoRoot working-tree root of the freshly cloned, now registered and active repository */
        void onCloned(File repoRoot);
    }

    private Listener _listener;
    private File _targetFolder;
    /** Id of the clone this dialog started, so a clone started elsewhere is none of its business. */
    private long _runId = -1;

    private View _form;
    private View _progressGroup;
    private EditText _urlEdit;
    private EditText _usernameEdit;
    private EditText _tokenEdit;
    private TextView _folderText;
    private TextView _progressTask;
    private ProgressBar _progressBar;
    private TextView _statusText;

    private final CloneRunner.Listener _runnerListener = new CloneRunner.Listener() {
        @Override
        public void onCloneProgress(final String task, final int percent) {
            showProgress(task, percent);
        }

        @Override
        public void onCloneFinished(final GitResult<GitRepoInfo> result, final File target) {
            onFinished(result, target);
        }
    };

    public static CloneDialog newInstance() {
        return newInstance(null, null);
    }

    /**
     * @param remoteUrl    pre-filled URL, or {@code null}
     * @param targetFolder pre-selected folder, or {@code null}
     */
    public static CloneDialog newInstance(final String remoteUrl, final File targetFolder) {
        final CloneDialog dialog = new CloneDialog();
        final Bundle args = new Bundle();
        args.putString(EXTRA_URL, remoteUrl);
        args.putSerializable(EXTRA_FOLDER, targetFolder);
        dialog.setArguments(args);
        return dialog;
    }

    /** @return this, so the call can be chained onto {@link #newInstance()} */
    public CloneDialog setListener(final Listener listener) {
        _listener = listener;
        return this;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final Context context = requireContext();
        final Bundle args = getArguments() == null ? new Bundle() : getArguments();

        final View root = LayoutInflater.from(context).inflate(R.layout.git_clone_dialog, null);
        _form = root.findViewById(R.id.git_clone_dialog__form);
        _progressGroup = root.findViewById(R.id.git_clone_dialog__progress_group);
        _urlEdit = root.findViewById(R.id.git_clone_dialog__url);
        _usernameEdit = root.findViewById(R.id.git_clone_dialog__username);
        _tokenEdit = root.findViewById(R.id.git_clone_dialog__token);
        _folderText = root.findViewById(R.id.git_clone_dialog__folder);
        _progressTask = root.findViewById(R.id.git_clone_dialog__progress_task);
        _progressBar = root.findViewById(R.id.git_clone_dialog__progress_bar);
        _statusText = root.findViewById(R.id.git_clone_dialog__status);

        if (savedInstanceState == null) {
            if (args.getString(EXTRA_URL) != null) {
                _urlEdit.setText(args.getString(EXTRA_URL));
            }
            _targetFolder = (File) args.getSerializable(EXTRA_FOLDER);
        } else {
            _targetFolder = (File) savedInstanceState.getSerializable(STATE_FOLDER);
            _runId = savedInstanceState.getLong(STATE_RUN_ID, -1);
        }
        showFolder();

        if (!GitCredentialStore.get(context).isPersistent()) {
            root.findViewById(R.id.git_clone_dialog__not_persisted).setVisibility(View.VISIBLE);
        }
        root.findViewById(R.id.git_clone_dialog__choose_folder).setOnClickListener(v -> chooseFolder());

        final AlertDialog dialog = new AlertDialog.Builder(context, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_clone__title)
                .setView(root)
                .setPositiveButton(R.string.git_clone__action, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        // Wired after show(): neither button may dismiss the dialog on its own — Clone has to be able
        // to reject bad input, and Cancel stops a running clone instead of closing over it.
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> startClone());
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (isOurCloneRunning()) {
                    CloneRunner.get().cancel();
                } else {
                    dismiss();
                }
            });
            applyRunningState();
        });
        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(STATE_FOLDER, _targetFolder);
        outState.putLong(STATE_RUN_ID, _runId);
    }

    @Override
    public void onStart() {
        super.onStart();
        // Re-attaches after a rotation: shows the running clone again, or its result if it finished meanwhile.
        CloneRunner.get().setListener(_runnerListener, _runId);
        applyRunningState();
    }

    @Override
    public void onStop() {
        CloneRunner.get().removeListener(_runnerListener);
        super.onStop();
    }

    // ---------------------------------------------------------------- folder

    private void chooseFolder() {
        final Context context = getContext();
        if (context == null || isOurCloneRunning()) {
            return;
        }
        MarkorFileBrowserFactory.showFolderDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
            @Override
            public void onFsViewerSelected(final String request, final File file, final Integer lineNumber) {
                _targetFolder = file;
                showStatus(null);
                showFolder();
            }

            @Override
            public void onFsViewerConfig(final GsFileBrowserOptions.Options dopt) {
                dopt.titleText = R.string.git_clone__target_folder;
            }
        }, getParentFragmentManager(), context);
    }

    private void showFolder() {
        if (_targetFolder == null) {
            _folderText.setText(R.string.git_clone__no_folder);
        } else {
            _folderText.setText(_targetFolder.getAbsolutePath());
        }
    }

    /** @return the folder when it can hold a clone, otherwise {@code null} after showing why it cannot */
    private File validatedFolder(final Context context) {
        if (_targetFolder == null) {
            showStatus(context.getString(R.string.git_error__folder_required));
            return null;
        }
        // D6: JGit needs a real java.io.File path; an SAF mount (SD card) can never be a repository.
        if (new MarkorContextUtils(context).isUnderStorageAccessFolder(context, _targetFolder, true)) {
            showStatus(context.getString(R.string.git_error__folder_storage_access));
            return null;
        }
        if (_targetFolder.exists()) {
            if (!_targetFolder.isDirectory()) {
                showStatus(context.getString(R.string.git_error__folder_not_empty));
                return null;
            }
            final String[] children = _targetFolder.list();
            if (children == null) {
                showStatus(context.getString(R.string.git_error__folder_not_writable));
                return null;
            }
            if (children.length > 0) {
                showStatus(context.getString(R.string.git_error__folder_not_empty));
                return null;
            }
            if (!_targetFolder.canWrite()) {
                showStatus(context.getString(R.string.git_error__folder_not_writable));
                return null;
            }
        }
        return _targetFolder;
    }

    // ---------------------------------------------------------------- clone

    private void startClone() {
        final Context context = getContext();
        if (context == null || isOurCloneRunning()) {
            return;
        }
        if (CloneRunner.get().isRunning()) {
            showStatus(context.getString(R.string.git_clone__already_running));
            return;
        }

        final GitRemoteUrlValidator.Result url = GitRemoteUrlValidator.validate(GitUiText.trimmedText(_urlEdit));
        if (!url.isValid()) {
            _urlEdit.setError(GitUiText.messageFor(context, url.getProblem()));
            _urlEdit.requestFocus();
            return;
        }
        _urlEdit.setError(null);

        final File target = validatedFolder(context);
        if (target == null) {
            return;
        }

        final char[] token = GitUiText.readSecret(_tokenEdit);
        final String username = GitUiText.trimmedText(_usernameEdit);
        if (token.length > 0 && username.isEmpty()) {
            GitUiText.wipe(token);
            _usernameEdit.setError(context.getString(R.string.git_error__username_required));
            _usernameEdit.requestFocus();
            return;
        }

        showStatus(null);
        final boolean started = CloneRunner.get().start(context, url.getUrl(), target, username, token);
        GitUiText.wipe(token);
        if (!started) {
            showStatus(context.getString(R.string.git_clone__already_running));
            return;
        }
        _runId = CloneRunner.get().getRunId();
        // The field is emptied so the token cannot be read back out of the view.
        _tokenEdit.setText("");
        applyRunningState();
    }

    private void onFinished(final GitResult<GitRepoInfo> result, final File target) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        applyRunningState();
        if (result == null) {
            showStatus(context.getString(R.string.git_error__generic));
            return;
        }
        if (!result.isOk()) {
            showStatus(GitUiText.messageFor(context, result));
            return;
        }
        final File repoRoot = result.getValue() != null ? result.getValue().getWorkTree() : target;
        Toast.makeText(context, context.getString(R.string.git_clone__done, repoRoot.getName()), Toast.LENGTH_SHORT).show();
        if (_listener != null) {
            _listener.onCloned(repoRoot);
        }
        dismissAllowingStateLoss();
    }

    // ---------------------------------------------------------------- view state

    /** @return {@code true} while the clone <i>this</i> dialog started is running */
    private boolean isOurCloneRunning() {
        return _runId >= 0 && CloneRunner.get().isRunning() && CloneRunner.get().getRunId() == _runId;
    }

    /** Swaps the form for the progress view (and back) according to what the runner is doing. */
    private void applyRunningState() {
        final boolean running = isOurCloneRunning();
        _form.setVisibility(running ? View.GONE : View.VISIBLE);
        _progressGroup.setVisibility(running ? View.VISIBLE : View.GONE);
        setCancelable(!running);

        final Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog) {
            final AlertDialog alert = (AlertDialog) dialog;
            final Button positive = alert.getButton(DialogInterface.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setEnabled(!running);
            }
        }
        if (running) {
            showProgress(CloneRunner.get().getTask(), CloneRunner.get().getPercent());
        }
    }

    private void showProgress(final String task, final int percent) {
        if (_progressTask == null) {
            return;
        }
        _progressTask.setText(task == null || task.trim().isEmpty()
                ? getString(R.string.git_clone__progress) : task);
        if (percent == GitProgress.UNKNOWN) {
            _progressBar.setIndeterminate(true);
        } else {
            _progressBar.setIndeterminate(false);
            _progressBar.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private void showStatus(final String status) {
        if (_statusText == null) {
            return;
        }
        _statusText.setText(status == null ? "" : status);
        _statusText.setVisibility(status == null || status.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
