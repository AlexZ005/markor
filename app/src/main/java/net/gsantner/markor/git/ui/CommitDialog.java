/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;
import net.gsantner.markor.git.GitAuthor;
import net.gsantner.markor.git.GitAuthorConfig;
import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitCommitInfo;
import net.gsantner.markor.git.GitCommitSelection;
import net.gsantner.markor.git.GitCommitValidator;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitRepoInfo;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.markor.git.GitStatusEntry;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.JGitService;
import net.gsantner.opoc.wrapper.GsTextWatcherAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a commit: a checklist of everything {@link GitService#status} reports, a message field, and the
 * two ways out — <i>Commit</i> and <i>Commit and push</i>.
 * <p>
 * <b>Host contract.</b> Show it with
 * {@code CommitDialog.newInstance(repoRoot, listener).show(getChildFragmentManager(), CommitDialog.FRAGMENT_TAG)}
 * and re-attach the listener after a configuration change the way {@code MainActivity} does for
 * {@code NewFileDialog}:
 * <pre>
 * final CommitDialog cd = (CommitDialog) manager.findFragmentByTag(CommitDialog.FRAGMENT_TAG);
 * if (cd != null) {
 *     cd.setListener(this::onCommitted);
 * }
 * </pre>
 * Without the re-attachment a commit finished after a rotation is simply not reported; the commit itself
 * is never lost, because it is the repository that holds it.
 * <p>
 * <b>Pushing is not done here.</b> <i>Commit and push</i> writes the commit and reports
 * {@code pushRequested == true} to the {@link Listener}; running the push (with its credential prompt,
 * non-fast-forward handling and progress) belongs to the Git fragment.
 * <p>
 * Everything touching the repository runs on {@link GitTaskRunner}'s per-repository worker thread. A
 * failure keeps the dialog open with the message in place so the user can fix something and try again;
 * only a successful commit dismisses it.
 */
public class CommitDialog extends DialogFragment {

    public static final String FRAGMENT_TAG = CommitDialog.class.getName();

    private static final String EXTRA_REPO_PATH = "EXTRA_REPO_PATH";
    private static final String STATE_SELECTED_PATHS = "STATE_SELECTED_PATHS";

    /** How much of the screen the file list may take before it starts scrolling instead of growing. */
    private static final int LIST_MAX_HEIGHT_DP = 220;

    /** What the host learns when the commit succeeded. */
    public interface Listener {
        /**
         * @param sha           full id of the new commit
         * @param pushRequested {@code true} when the user pressed <i>Commit and push</i>; the host runs
         *                      the push, the dialog is already gone
         */
        void onCommitted(String sha, boolean pushRequested);
    }

    private final GitService _git = new JGitService();

    private Listener _listener;
    private File _repoRoot;
    private GitCommitSelection _selection = new GitCommitSelection(null);
    private FileAdapter _adapter;
    private boolean _busy;
    private List<String> _restoreSelection;

    private EditText _messageEdit;
    private TextView _subtitle;
    private TextView _errorView;
    private TextView _emptyView;
    private CheckBox _selectAll;
    private ProgressBar _progress;
    private RecyclerView _list;

    /**
     * @param repoRoot working-tree root of the repository to commit in
     * @param listener told about a successful commit; may be {@code null} and set later with
     *                 {@link #setListener(Listener)}
     */
    public static CommitDialog newInstance(final File repoRoot, final Listener listener) {
        final CommitDialog dialog = new CommitDialog();
        final Bundle args = new Bundle();
        args.putString(EXTRA_REPO_PATH, repoRoot == null ? "" : repoRoot.getAbsolutePath());
        dialog.setArguments(args);
        dialog.setListener(listener);
        return dialog;
    }

    /** Re-attaches the host's callback, which a configuration change drops. */
    public void setListener(final Listener listener) {
        _listener = listener;
    }

    /** @return the repository this dialog was opened for, for a host that shows several */
    public File getRepoRoot() {
        return _repoRoot;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final Activity activity = requireActivity();
        _repoRoot = new File(getArguments() == null ? "" : getArguments().getString(EXTRA_REPO_PATH, ""));
        if (savedInstanceState != null) {
            _restoreSelection = savedInstanceState.getStringArrayList(STATE_SELECTED_PATHS);
        }

        final LayoutInflater inflater = LayoutInflater.from(activity);
        final View root = inflater.inflate(R.layout.git__commit_dialog, null);
        bindViews(root);

        final AlertDialog dialog = new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setView(root)
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .setNeutralButton(R.string.git_commit_and_push, null)
                .setPositiveButton(R.string.git_commit, null)
                .create();

        // The buttons are wired after the dialog exists so that a failed commit can keep it open;
        // the listeners the builder installs always dismiss.
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onCommitPressed(false));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> onCommitPressed(true));
            updateButtons();
            loadStatus();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        // The message field restores itself with the dialog's view state; the checklist does not,
        // because it is rebuilt from a fresh status after the rotation.
        outState.putStringArrayList(STATE_SELECTED_PATHS, new ArrayList<>(_selection.getSelectedPaths()));
    }

    // The whole list is replaced at once, so there is no finer-grained event to send.
    @SuppressLint("NotifyDataSetChanged")
    private void bindViews(final View root) {
        _subtitle = root.findViewById(R.id.git__commit_dialog__subtitle);
        _messageEdit = root.findViewById(R.id.git__commit_dialog__message);
        _errorView = root.findViewById(R.id.git__commit_dialog__error);
        _emptyView = root.findViewById(R.id.git__commit_dialog__empty);
        _selectAll = root.findViewById(R.id.git__commit_dialog__select_all);
        _progress = root.findViewById(R.id.git__commit_dialog__progress);
        _list = root.findViewById(R.id.git__commit_dialog__files);

        _subtitle.setText(_repoRoot.getName());

        _adapter = new FileAdapter();
        _list.setLayoutManager(new LinearLayoutManager(root.getContext()));
        _list.setAdapter(_adapter);

        _messageEdit.addTextChangedListener(new GsTextWatcherAdapter() {
            @Override
            public void afterTextChanged(final Editable s) {
                _errorView.setVisibility(View.GONE);
                updateButtons();
            }
        });

        _selectAll.setOnClickListener(v -> {
            _selection.setAllChecked(_selectAll.isChecked());
            _adapter.notifyDataSetChanged();
            updateButtons();
        });
    }

    // ---------------------------------------------------------------- status

    private void loadStatus() {
        setBusy(true, R.string.git_loading_changes);
        GitTaskRunner.get().submit(_repoRoot.getAbsolutePath(),
                token -> {
                    final GitProgress progress = new CancelTokenProgress(token);
                    final GitResult<List<GitStatusEntry>> status = _git.status(_repoRoot, progress);
                    // The header shows branch and identity; a repository that cannot be described is
                    // not a reason to refuse the commit, so a failure here is simply left out.
                    final GitResult<GitRepoInfo> info = _git.open(_repoRoot, progress);
                    return new StatusLoad(status, info.getValueOrNull());
                },
                () -> getDialog() != null,
                result -> {
                    setBusy(false, R.string.git_no_changes);
                    if (!result.isSuccess()) {
                        showError(getString(R.string.git_operation_failed));
                        return;
                    }
                    onStatusLoaded(result.getValue());
                });
    }

    // The whole list is replaced at once, so there is no finer-grained event to send.
    @SuppressLint("NotifyDataSetChanged")
    private void onStatusLoaded(final StatusLoad load) {
        final GitResult<List<GitStatusEntry>> status = load.status;
        if (status.getKind() != GitResult.Kind.OK) {
            showError(status.getMessage());
            _selection = new GitCommitSelection(null);
        } else {
            _selection = new GitCommitSelection(status.getValue());
            if (_restoreSelection != null) {
                _selection.restoreSelection(_restoreSelection);
                _restoreSelection = null;
            }
        }
        if (load.info != null) {
            _subtitle.setText(subtitleFor(load.info));
        }
        _adapter.notifyDataSetChanged();
        _emptyView.setVisibility(_selection.isEmpty() ? View.VISIBLE : View.GONE);
        capListHeight();
        updateButtons();
        _messageEdit.post(_messageEdit::requestFocus);
    }

    private String subtitleFor(final GitRepoInfo info) {
        final String branch = info.getBranch();
        return branch == null || branch.isEmpty() ? info.getName() : info.getName() + " (" + branch + ")";
    }

    /**
     * Keeps a repository with many changes from pushing the message field and the buttons off screen:
     * once the list is taller than the cap it gets a fixed height and scrolls inside it.
     */
    private void capListHeight() {
        _list.post(() -> {
            if (getDialog() == null) {
                return;
            }
            final int maxPx = Math.round(getResources().getDisplayMetrics().density * LIST_MAX_HEIGHT_DP);
            final ViewGroup.LayoutParams params = _list.getLayoutParams();
            if (_list.getHeight() > maxPx && params.height != maxPx) {
                params.height = maxPx;
                _list.setLayoutParams(params);
            }
        });
    }

    // ---------------------------------------------------------------- commit

    private void onCommitPressed(final boolean push) {
        if (_busy) {
            return;
        }
        final GitCommitValidator.Result validation =
                GitCommitValidator.validate(messageText(), _selection.getSelectedCount());
        if (!validation.isValid()) {
            if (validation.firstProblem() == GitCommitValidator.Problem.EMPTY_MESSAGE) {
                _messageEdit.setError(getString(R.string.git_commit_message_required));
                _messageEdit.requestFocus();
            }
            return;
        }

        GitAuthorDialog.requireAuthor(requireActivity(), _repoRoot, author -> commit(author, push));
    }

    private void commit(final GitAuthor author, final boolean push) {
        final String message = GitCommitValidator.normalizeMessage(messageText());
        final List<String> paths = _selection.getSelectedPaths();
        setBusy(true, R.string.git_committing);

        GitTaskRunner.get().submit(_repoRoot.getAbsolutePath(),
                token -> {
                    // Record the identity in .git/config so a desktop git on the same folder agrees;
                    // it never overwrites one the repository already has, and a failure is not fatal.
                    GitAuthorConfig.write(_repoRoot, author);
                    return _git.commit(_repoRoot, message, paths, author, new CancelTokenProgress(token));
                },
                () -> getDialog() != null,
                result -> {
                    setBusy(false, R.string.git_no_changes);
                    if (!result.isSuccess()) {
                        // GitService reports failures as a non-OK GitResult, so an ERROR here means the
                        // task itself broke (or was cancelled); either way there is nothing to explain.
                        showError(getString(R.string.git_operation_failed));
                        return;
                    }
                    onCommitFinished(result.getValue(), push);
                });
    }

    private void onCommitFinished(final GitResult<GitCommitInfo> result, final boolean push) {
        if (result.getKind() != GitResult.Kind.OK || result.getValue() == null) {
            showError(result.getMessage());
            // The status may have moved on (another tab saved a file); reload so the checklist is honest.
            loadStatus();
            return;
        }
        final String sha = result.getValue().getSha();
        dismissAllowingStateLoss();
        if (_listener != null) {
            _listener.onCommitted(sha, push);
        }
    }

    // ---------------------------------------------------------------- small helpers

    private String messageText() {
        return _messageEdit.getText() == null ? "" : _messageEdit.getText().toString();
    }

    private void showError(final String message) {
        _errorView.setText(message == null ? "" : message);
        _errorView.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void setBusy(final boolean busy, final int busyTextRes) {
        _busy = busy;
        _progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            showError(null);
            _emptyView.setText(busyTextRes);
            _emptyView.setVisibility(View.VISIBLE);
        } else {
            _emptyView.setText(R.string.git_no_changes);
            _emptyView.setVisibility(View.GONE);
        }
        updateButtons();
    }

    private void updateButtons() {
        final Dialog dialog = getDialog();
        if (!(dialog instanceof AlertDialog)) {
            return;
        }
        final AlertDialog alert = (AlertDialog) dialog;
        final boolean enabled = !_busy && _selection.getSelectedCount() > 0;
        final Button commit = alert.getButton(AlertDialog.BUTTON_POSITIVE);
        final Button commitPush = alert.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (commit != null) {
            commit.setEnabled(enabled);
        }
        if (commitPush != null) {
            commitPush.setEnabled(enabled);
        }
        _selectAll.setChecked(_selection.isAllSelected());
        _selectAll.setEnabled(!_busy && _selection.getSelectableCount() > 0);
    }

    /** Carries both halves of the initial load so the checklist and the header appear together. */
    private static final class StatusLoad {
        final GitResult<List<GitStatusEntry>> status;
        final GitRepoInfo info;

        StatusLoad(final GitResult<List<GitStatusEntry>> status, final GitRepoInfo info) {
            this.status = status;
            this.info = info;
        }
    }

    // ---------------------------------------------------------------- checklist

    private final class FileAdapter extends RecyclerView.Adapter<FileHolder> {

        @NonNull
        @Override
        public FileHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new FileHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.git__commit_dialog__file_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final FileHolder holder, final int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return _selection.size();
        }
    }

    private final class FileHolder extends RecyclerView.ViewHolder {
        private final CheckBox _check;
        private final TextView _kind;
        private final TextView _path;
        private final TextView _hint;

        FileHolder(final View item) {
            super(item);
            _check = item.findViewById(R.id.git__commit_dialog__file_item__check);
            _kind = item.findViewById(R.id.git__commit_dialog__file_item__kind);
            _path = item.findViewById(R.id.git__commit_dialog__file_item__path);
            _hint = item.findViewById(R.id.git__commit_dialog__file_item__hint);
            item.setOnClickListener(v -> {
                final int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION || _busy || !_selection.isSelectable(position)) {
                    return;
                }
                _check.setChecked(_selection.toggle(position));
                updateButtons();
            });
        }

        void bind(final int position) {
            final GitStatusEntry entry = _selection.getEntry(position);
            final boolean selectable = _selection.isSelectable(position);
            _check.setChecked(_selection.isChecked(position));
            _check.setEnabled(selectable);
            _kind.setText(letterOf(entry.getKind()));
            _kind.setContentDescription(getString(descriptionOf(entry.getKind())));
            _path.setText(entry.getPath());
            _hint.setVisibility(selectable ? View.GONE : View.VISIBLE);
            if (!selectable) {
                _hint.setText(R.string.git_conflict_not_committable);
            }
            itemView.setEnabled(selectable);
            itemView.setAlpha(selectable ? 1f : 0.5f);
        }
    }

    private static String letterOf(final GitStatusEntry.Kind kind) {
        switch (kind) {
            case MODIFIED:
                return "M";
            case ADDED:
                return "A";
            case DELETED:
                return "D";
            case CONFLICT:
                return "!";
            case UNTRACKED:
            default:
                return "?";
        }
    }

    private static int descriptionOf(final GitStatusEntry.Kind kind) {
        switch (kind) {
            case MODIFIED:
                return R.string.git_status_modified;
            case ADDED:
                return R.string.git_status_added;
            case DELETED:
                return R.string.git_status_deleted;
            case CONFLICT:
                return R.string.git_status_conflict;
            case UNTRACKED:
            default:
                return R.string.git_status_untracked;
        }
    }

    /** Bridges {@link GitTaskRunner}'s cancel token to the {@link GitProgress} the service expects. */
    private static final class CancelTokenProgress implements GitProgress {
        private final GitCancelToken _token;

        CancelTokenProgress(final GitCancelToken token) {
            _token = token;
        }

        @Override
        public void onTaskBegin(final String task, final int totalWork) {
        }

        @Override
        public void onTaskProgress(final String task, final int completedWork, final int totalWork, final int percent) {
        }

        @Override
        public void onTaskEnd(final String task) {
        }

        @Override
        public boolean isCancelled() {
            return _token.isCancelled();
        }
    }
}
