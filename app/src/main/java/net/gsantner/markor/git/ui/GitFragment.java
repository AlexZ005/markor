/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.activity.MainActivity;
import net.gsantner.markor.activity.MarkorBaseFragment;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.git.GitAheadBehind;
import net.gsantner.markor.git.GitAuthor;
import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitCommitInfo;
import net.gsantner.markor.git.GitCredentialStore;
import net.gsantner.markor.git.GitCredentialsSource;
import net.gsantner.markor.git.GitFetchThrottle;
import net.gsantner.markor.git.GitHistoryPager;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitPullStrategy;
import net.gsantner.markor.git.GitRelativeTime;
import net.gsantner.markor.git.GitRepoConfig;
import net.gsantner.markor.git.GitRepoInfo;
import net.gsantner.markor.git.GitRepoRegistry;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.markor.git.GitSettingsStore;
import net.gsantner.markor.git.GitStatusEntry;
import net.gsantner.markor.git.GitTabState;
import net.gsantner.markor.git.GitTask;
import net.gsantner.markor.git.GitTaskResult;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.JGitService;
import net.gsantner.opoc.frontend.GsSearchOrCustomTextDialog;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Git" bottom-navigation tab: the one screen from which a repository is picked, its
 * uncommitted changes are reviewed and committed, and the remote is pulled from and pushed to.
 * <p>
 * It renders one of three states ({@link net.gsantner.markor.git.GitTabState}): no repository
 * configured, a folder that is not a repository, or an open repository with a header, an action row
 * and the Changes / History lists.
 * <p>
 * <b>Threading.</b> Nothing here touches git on the main thread. Every call goes through
 * {@link GitTaskRunner}, which serializes per repository, delivers on the main thread and drops the
 * callback when this fragment's view is gone. While an operation runs the action row is disabled and
 * the header shows an indeterminate bar with a Cancel button.
 */
public class GitFragment extends MarkorBaseFragment {
    public static final String FRAGMENT_TAG = "GitFragment";

    /**
     * Choices that belong to the tab rather than to a fragment instance. The bottom-navigation
     * adapter recreates its fragments on every rotation (and does not always restore their saved
     * state), so the selected segment and a picked-but-not-initialized folder live here instead of
     * in a {@link Bundle}. Process death clears them, which is correct: what survives a restart is
     * the registry, and that is re-read on every refresh.
     */
    private static final class TabUiState {
        private File pendingFolder;
        private boolean showHistory;
        private String ignoreSuggestedFor;
    }

    private static final TabUiState UI = new TabUiState();

    /** Process-wide, so switching tabs back and forth does not fetch again (roadmap task 5.5). */
    private static final GitFetchThrottle FETCH_THROTTLE = new GitFetchThrottle();

    private final GitService _git = new JGitService();
    private final GitHistoryPager _pager = new GitHistoryPager();
    private final List<GitStatusEntry> _status = new ArrayList<>();

    private GitRepoRegistry _registry;
    private GitRepoConfig _active;
    private File _repoRoot;
    private GitRepoInfo _info;
    private GitAheadBehind _aheadBehind;
    private long _lastFetchMillis;
    private String _loadError;

    private GitCancelToken _opToken;
    private String _opLabel;
    private boolean _refreshing;
    private Runnable _afterRefresh;

    /** Task 5.3: the pull state machine of the active repository; a new one whenever the repository changes. */
    private GitPullFlow _flow;
    private boolean _wasResolving;
    /** "Commit and push" chosen in the commit dialog the pull asked for: push once that pull is done. */
    private boolean _pushAfterPull;
    private final List<String> _renderedConflictFiles = new ArrayList<>();

    /** Bumped whenever the history is thrown away, so a page that arrives late is not appended. */
    private int _historyGeneration;

    private View _setupView;
    private TextView _setupTitle;
    private TextView _setupMessage;
    private Button _setupPrimary;
    private Button _setupSecondary;
    private Button _setupSuggestion;

    private View _repoView;
    private TextView _repoName;
    private TextView _branch;
    private TextView _aheadBehindText;
    private TextView _syncState;
    private View _progressBox;
    private TextView _progressText;
    private ProgressBar _progressBar;
    private View _actions;
    private Button _pullButton;
    private Button _commitButton;
    private Button _pushButton;
    private View _conflictBanner;
    private TextView _conflictMessage;
    private ViewGroup _conflictFiles;
    private Button _markResolvedButton;
    private Button _abortButton;
    private TextView _segmentChanges;
    private TextView _segmentHistory;
    private SwipeRefreshLayout _swipe;
    private RecyclerView _list;
    private TextView _empty;

    private StatusAdapter _statusAdapter;
    private HistoryAdapter _historyAdapter;

    public static GitFragment newInstance() {
        return new GitFragment();
    }

    public GitFragment() {
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.git__fragment;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    /**
     * Title {@code MainActivity} puts in the toolbar for this tab.
     *
     * @return the active repository's display name, or {@code null} when none is open or the
     * fragment is not attached yet — the caller then uses the plain tab name
     */
    public String getTabTitle() {
        final GitRepoConfig active = _active != null ? _active : activeFromRegistry(getContext());
        return active == null ? null : active.getDisplayName();
    }

    private GitRepoConfig activeFromRegistry(final Context context) {
        if (context == null) {
            return null;
        }
        if (_registry == null) {
            _registry = GitSettingsStore.newRegistry();
        }
        return _registry.getActive();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        _registry = GitSettingsStore.newRegistry();

        _setupView = view.findViewById(R.id.git__fragment__setup);
        _setupTitle = view.findViewById(R.id.git__fragment__setup_title);
        _setupMessage = view.findViewById(R.id.git__fragment__setup_message);
        _setupPrimary = view.findViewById(R.id.git__fragment__setup_primary);
        _setupSecondary = view.findViewById(R.id.git__fragment__setup_secondary);
        _setupSuggestion = view.findViewById(R.id.git__fragment__setup_suggestion);

        _repoView = view.findViewById(R.id.git__fragment__repo);
        _repoName = view.findViewById(R.id.git__fragment__repo_name);
        _branch = view.findViewById(R.id.git__fragment__branch);
        _aheadBehindText = view.findViewById(R.id.git__fragment__ahead_behind);
        _syncState = view.findViewById(R.id.git__fragment__sync_state);
        _progressBox = view.findViewById(R.id.git__fragment__progress_box);
        _progressText = view.findViewById(R.id.git__fragment__progress_text);
        _progressBar = view.findViewById(R.id.git__fragment__progress_bar);
        _actions = view.findViewById(R.id.git__fragment__actions);
        _pullButton = view.findViewById(R.id.git__fragment__pull);
        _commitButton = view.findViewById(R.id.git__fragment__commit);
        _pushButton = view.findViewById(R.id.git__fragment__push);
        _segmentChanges = view.findViewById(R.id.git__fragment__segment_changes);
        _segmentHistory = view.findViewById(R.id.git__fragment__segment_history);
        _swipe = view.findViewById(R.id.git__fragment__swipe);
        _list = view.findViewById(R.id.git__fragment__list);
        _empty = view.findViewById(R.id.git__fragment__empty);
        _conflictBanner = view.findViewById(R.id.git__fragment__conflict_banner);
        _conflictMessage = view.findViewById(R.id.git__fragment__conflict_message);
        _conflictFiles = view.findViewById(R.id.git__fragment__conflict_files);
        _markResolvedButton = view.findViewById(R.id.git__fragment__mark_resolved);
        _abortButton = view.findViewById(R.id.git__fragment__abort);

        view.findViewById(R.id.git__fragment__overflow).setOnClickListener(this::showOverflow);
        _pullButton.setOnClickListener(v -> pull());
        _commitButton.setOnClickListener(v -> showCommitDialog());
        _pushButton.setOnClickListener(v -> push());
        _markResolvedButton.setOnClickListener(v -> markResolved());
        _abortButton.setOnClickListener(v -> confirmAbort());
        _segmentChanges.setOnClickListener(v -> showSegment(false));
        _segmentHistory.setOnClickListener(v -> showSegment(true));
        view.findViewById(R.id.git__fragment__progress_cancel).setOnClickListener(v -> cancelOperation());
        _swipe.setOnRefreshListener(() -> refresh(true));

        _statusAdapter = new StatusAdapter();
        _historyAdapter = new HistoryAdapter();
        _list.setLayoutManager(new LinearLayoutManager(view.getContext()));
        _list.addItemDecoration(new DividerItemDecoration(view.getContext(), DividerItemDecoration.VERTICAL));
        _list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView, final int dx, final int dy) {
                maybeLoadMoreHistory();
            }
        });

        reattachDialogListeners();
        render();
        // No refresh here: onResume follows for the visible page, and an offscreen page must not
        // walk a repository it may never show.
    }

    /**
     * The three dialogs hand their result back through a plain listener field, which a
     * configuration change drops (see the dialog lanes' handovers). Re-attach after a recreation so
     * the tab still learns about a commit or a saved remote; a clone registers itself either way.
     */
    private void reattachDialogListeners() {
        final CommitDialog commit = (CommitDialog) getChildFragmentManager().findFragmentByTag(CommitDialog.FRAGMENT_TAG);
        if (commit != null) {
            commit.setListener(this::onCommitted);
        }
        final CloneDialog clone = (CloneDialog) getChildFragmentManager().findFragmentByTag(CloneDialog.FRAGMENT_TAG);
        if (clone != null) {
            clone.setListener(this::onCloned);
        }
        final RemoteSetupDialog remote = (RemoteSetupDialog) getChildFragmentManager().findFragmentByTag(RemoteSetupDialog.FRAGMENT_TAG);
        if (remote != null) {
            remote.setListener((repoPath, remoteUrl) -> refresh(false));
        }
    }

    @Override
    protected void onFragmentFirstTimeVisible() {
        refresh(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        // ViewPager2 keeps offscreen pages STARTED, so onResume is "this tab became visible".
        refresh(false);
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        if (getView() == null) {
            return;
        }
        final GitTabState state = GitTabState.select(_repoRoot, UI.pendingFolder);
        _setupView.setVisibility(state.isSetup() ? View.VISIBLE : View.GONE);
        _repoView.setVisibility(state.isSetup() ? View.GONE : View.VISIBLE);
        if (state.isSetup()) {
            renderSetup(state);
        } else {
            renderRepo();
        }
        updateToolbarTitle();
    }

    /**
     * The toolbar title of this tab is the repository name (see {@code MainActivity.getPosTitle}).
     * It has to be re-applied when the active repository changes while the tab is on screen.
     */
    private void updateToolbarTitle() {
        final Activity activity = getActivity();
        if (!(activity instanceof MainActivity)) {
            return;
        }
        final MainActivity main = (MainActivity) activity;
        final int pos = main.getCurrentPos();
        if (pos == main.tabIdToPos(R.id.nav_git)) {
            main.setTitle(main.getPosTitle(pos));
        }
    }

    private void renderSetup(final GitTabState state) {
        final Context context = requireContext();
        if (state == GitTabState.FOLDER_NOT_A_REPOSITORY) {
            final File folder = UI.pendingFolder;
            _setupTitle.setText(R.string.git_tab__not_a_repo_title);
            _setupMessage.setText(context.getString(R.string.git_tab__not_a_repo_message, folder.getAbsolutePath()));
            _setupPrimary.setText(R.string.git_tab__init_here);
            _setupPrimary.setOnClickListener(v -> initRepository(folder));
            _setupSecondary.setText(R.string.git_tab__clone_into_folder);
            _setupSecondary.setOnClickListener(v -> cloneInto(folder));
            // A way back: to another repository when there is one, otherwise to another folder.
            _setupSuggestion.setVisibility(View.VISIBLE);
            if (_registry.isEmpty()) {
                _setupSuggestion.setText(R.string.git_tab__select_folder);
                _setupSuggestion.setOnClickListener(v -> chooseFolder());
            } else {
                _setupSuggestion.setText(R.string.git_tab__switch_repository);
                _setupSuggestion.setOnClickListener(v -> showSwitchRepository());
            }
            return;
        }

        _setupTitle.setText(R.string.git_tab__no_repo_title);
        _setupMessage.setText(R.string.git_tab__no_repo_message);
        _setupPrimary.setText(R.string.git_tab__select_folder);
        _setupPrimary.setOnClickListener(v -> chooseFolder());
        _setupSecondary.setText(R.string.git_clone__title);
        _setupSecondary.setOnClickListener(v -> cloneInto(null));

        final File notebook = notebookRepositorySuggestion();
        if (notebook == null) {
            _setupSuggestion.setVisibility(View.GONE);
        } else {
            _setupSuggestion.setVisibility(View.VISIBLE);
            _setupSuggestion.setText(context.getString(R.string.git_tab__use_notebook_folder, notebook.getName()));
            _setupSuggestion.setOnClickListener(v -> useFolder(notebook));
        }
    }

    /** @return the notebook folder when it is a repository that is not registered yet, else {@code null} */
    private File notebookRepositorySuggestion() {
        final File notebook = _appSettings != null ? _appSettings.getNotebookDirectory() : null;
        if (notebook == null || !new File(notebook, ".git").exists()) {
            return null;
        }
        return _registry.contains(notebook.getAbsolutePath()) ? null : notebook;
    }

    private void renderRepo() {
        final Context context = requireContext();
        _repoName.setText(_active != null ? _active.getDisplayName() : _repoRoot.getName());
        _branch.setText(branchLabel(context));
        _aheadBehindText.setText(aheadBehindLabel());
        _syncState.setText(syncLabel(context));

        final boolean busy = isBusy();
        final boolean resolving = _flow != null && _flow.isResolving();
        _progressBox.setVisibility(_opLabel == null ? View.GONE : View.VISIBLE);
        _progressBar.setVisibility(_opLabel == null ? View.GONE : View.VISIBLE);
        // Task 5.3: while a merge or rebase waits for the user, the banner takes the action row's place.
        _actions.setVisibility(resolving ? View.GONE : View.VISIBLE);
        _conflictBanner.setVisibility(resolving ? View.VISIBLE : View.GONE);
        if (resolving) {
            renderConflictBanner(context, busy);
        } else {
            _renderedConflictFiles.clear();
        }
        _pullButton.setEnabled(!busy);
        _commitButton.setEnabled(!busy);
        _pushButton.setEnabled(!busy);
        _actions.setAlpha(busy ? 0.5f : 1f);
        _swipe.setRefreshing(_refreshing);

        _segmentChanges.setText(_status.isEmpty()
                ? context.getString(R.string.git_tab__changes)
                : context.getString(R.string.git_tab__changes_count, _status.size()));
        _segmentChanges.setSelected(!UI.showHistory);
        _segmentHistory.setSelected(UI.showHistory);

        final RecyclerView.Adapter<?> wanted = UI.showHistory ? _historyAdapter : _statusAdapter;
        if (_list.getAdapter() != wanted) {
            _list.setAdapter(wanted);
        }

        final String emptyText = emptyText(context);
        _empty.setText(emptyText == null ? "" : emptyText);
        _empty.setVisibility(emptyText == null ? View.GONE : View.VISIBLE);
    }

    /**
     * The "Resolving conflicts" banner: what stopped, the files (one status row each, tap opens the
     * editor), and the two buttons. Only <i>Abort</i> for a state this app did not start.
     */
    private void renderConflictBanner(final Context context, final boolean busy) {
        final List<String> files = _flow.getConflictFiles();
        final int message;
        switch (_flow.getRepoState()) {
            case MERGING:
                message = files.isEmpty() ? R.string.git_tab__resolving_no_files : R.string.git_tab__resolving_merge;
                break;
            case REBASING:
                message = files.isEmpty() ? R.string.git_tab__resolving_no_files : R.string.git_tab__resolving_rebase;
                break;
            default:
                message = R.string.git_tab__resolving_other;
                break;
        }
        _conflictMessage.setText(message);
        if (!files.equals(_renderedConflictFiles)) {
            _renderedConflictFiles.clear();
            _renderedConflictFiles.addAll(files);
            _conflictFiles.removeAllViews();
            final LayoutInflater inflater = LayoutInflater.from(context);
            for (final String path : files) {
                final View row = inflater.inflate(R.layout.git__fragment__status_item, _conflictFiles, false);
                final TextView kind = row.findViewById(R.id.git__fragment__status_item__kind);
                kind.setText(letterOf(GitStatusEntry.Kind.CONFLICT));
                kind.setContentDescription(context.getString(R.string.git_status_conflict));
                ((TextView) row.findViewById(R.id.git__fragment__status_item__path)).setText(path);
                ((TextView) row.findViewById(R.id.git__fragment__status_item__kind_name)).setText(R.string.git_tab__conflict_open_hint);
                row.setOnClickListener(v -> openInEditor(path));
                _conflictFiles.addView(row);
            }
        }
        _markResolvedButton.setVisibility(_flow.canContinue() ? View.VISIBLE : View.GONE);
        _markResolvedButton.setEnabled(!busy);
        _abortButton.setEnabled(!busy);
        _conflictBanner.setAlpha(busy ? 0.6f : 1f);
    }

    private String branchLabel(final Context context) {
        if (_info == null) {
            return "";
        }
        if (_info.isDetached()) {
            return context.getString(R.string.git_tab__detached_head);
        }
        final String branch = _info.getBranch();
        return branch == null || branch.isEmpty() ? context.getString(R.string.git_tab__no_branch_yet) : branch;
    }

    private String aheadBehindLabel() {
        if (_aheadBehind == null || !_aheadBehind.hasUpstream()) {
            return "";
        }
        return "↑" + _aheadBehind.getAhead() + " ↓" + _aheadBehind.getBehind();
    }

    private String syncLabel(final Context context) {
        if (_loadError != null) {
            return _loadError;
        }
        final GitRelativeTime.Label label = GitRelativeTime.of(_lastFetchMillis, System.currentTimeMillis());
        final String text;
        switch (label.getUnit()) {
            case JUST_NOW:
                text = context.getString(R.string.git_tab__synced_just_now);
                break;
            case MINUTES:
                text = context.getString(R.string.git_tab__synced_minutes, label.getValue());
                break;
            case HOURS:
                text = context.getString(R.string.git_tab__synced_hours, label.getValue());
                break;
            case DAYS:
                text = context.getString(R.string.git_tab__synced_days, label.getValue());
                break;
            case NEVER:
            default:
                text = context.getString(R.string.git_tab__never_fetched);
                break;
        }
        final boolean wantsFetch = isFetchOnOpenEnabled() && _info != null && _info.hasRemote();
        return wantsFetch && !isOnline(context)
                ? text + " · " + context.getString(R.string.git_tab__offline)
                : text;
    }

    /** @return the message for the empty view, or {@code null} when the list has rows */
    private String emptyText(final Context context) {
        if (_loadError != null && !UI.showHistory) {
            return _loadError;
        }
        if (UI.showHistory) {
            if (!_pager.isEmpty()) {
                return null;
            }
            return _pager.isLoading()
                    ? context.getString(R.string.git_tab__loading)
                    : context.getString(R.string.git_tab__no_commits);
        }
        if (!_status.isEmpty()) {
            return null;
        }
        return _refreshing
                ? context.getString(R.string.git_tab__loading)
                : context.getString(R.string.git_tab__nothing_to_commit);
    }

    private boolean isBusy() {
        return _opToken != null || _refreshing
                || (_repoRoot != null && GitTaskRunner.get().isBusy(_repoRoot.getAbsolutePath()))
                || (_flow != null && _flow.isBusy());
    }

    private void showSegment(final boolean history) {
        UI.showHistory = history;
        if (history && _pager.isEmpty()) {
            loadMoreHistory();
        }
        render();
    }

    // ---------------------------------------------------------------- refresh

    /**
     * Re-reads the registry and, when a repository is active, its info, status and ahead/behind.
     * Called when the tab becomes visible, after every operation and from pull-to-refresh.
     *
     * @param fromSwipe {@code true} for pull-to-refresh, which is allowed to restart a refresh that
     *                  is already running; the visible-again path is not, so switching tabs quickly
     *                  does not queue one status walk per tap
     */
    private void refresh(final boolean fromSwipe) {
        if (getView() == null) {
            _afterRefresh = null;
            return;
        }
        // Task 4.2, before any early return: every time this tab is looked at, what the To-Do and
        // QuickNote tabs show is written to disk, so git (and the user) see the same thing.
        flushOpenEditors();
        _active = _registry.getActive();
        final File previousRoot = _repoRoot;
        _repoRoot = _active != null ? _active.getFile() : null;
        if (_repoRoot == null || !_repoRoot.equals(previousRoot)) {
            forgetRepositoryData();
            _flow = _repoRoot == null ? null : newFlow(_repoRoot);
            _wasResolving = false;
        }

        if (_repoRoot == null) {
            _refreshing = false;
            render();
            runAfterRefresh();
            return;
        }
        if (_refreshing && !fromSwipe) {
            return; // The running refresh will deliver, and it will run _afterRefresh.
        }
        if (!_repoRoot.isDirectory()) {
            // Roadmap 7.2 territory, but an unplugged SD card or a renamed folder must not crash here.
            _loadError = getString(R.string.git_tab__folder_gone, _repoRoot.getAbsolutePath());
            _info = null;
            _status.clear();
            _refreshing = false;
            render();
            runAfterRefresh();
            return;
        }

        final File root = _repoRoot;
        resetHistory();
        _refreshing = true;
        render();
        GitTaskRunner.get().submit(root.getAbsolutePath(),
                token -> {
                    final GitProgress progress = GitUiProgress.cancelOnly(token);
                    final GitResult<GitRepoInfo> info = _git.open(root, progress);
                    if (!info.isOk()) {
                        return new Snapshot(info, null, null);
                    }
                    return new Snapshot(info, _git.status(root, progress), _git.aheadBehind(root, progress));
                },
                this::isAlive,
                result -> {
                    _refreshing = false;
                    if (!result.isSuccess()) {
                        _loadError = result.isCancelled() ? null : getString(R.string.git_operation_failed);
                        render();
                        runAfterRefresh();
                        return;
                    }
                    applySnapshot(result.getValue());
                    render();
                    if (UI.showHistory) {
                        loadMoreHistory();
                    }
                    runAfterRefresh();
                    maybeSuggestGitIgnore(root);
                    maybeFetchOnOpen();
                });
    }

    /** Throws away everything that belongs to one repository, for a switch or for "no repository". */
    private void forgetRepositoryData() {
        resetHistory();
        _status.clear();
        if (_statusAdapter != null) {
            _statusAdapter.notifyDataSetChanged();
        }
        _info = null;
        _aheadBehind = null;
        _lastFetchMillis = 0;
        _loadError = null;
    }

    private void resetHistory() {
        _historyGeneration++;
        _pager.reset();
        if (_historyAdapter != null) {
            _historyAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Runs whatever was chained onto this refresh (a push after a commit, a retried operation after
     * the remote dialog saved). It runs after the header and the ahead/behind counts are current,
     * which is what those callers need.
     */
    private void runAfterRefresh() {
        final Runnable action = _afterRefresh;
        _afterRefresh = null;
        if (action != null) {
            action.run();
        }
    }

    private void applySnapshot(final Snapshot snapshot) {
        if (!snapshot.info.isOk()) {
            _info = null;
            _status.clear();
            _loadError = snapshot.info.getMessage();
            return;
        }
        _loadError = null;
        _info = snapshot.info.getValue();
        _lastFetchMillis = Math.max(_lastFetchMillis, _info.getLastFetchEpochMillis());
        _status.clear();
        if (snapshot.status != null && snapshot.status.isOk()) {
            // Settings > Git > "Show untracked files" (task 7.1), on by default. The contract has no
            // flag for it, so the list is filtered here; the commit dialog reads status for itself.
            final boolean showUntracked = _appSettings == null || _appSettings.isGitShowUntrackedFiles();
            for (final GitStatusEntry entry : snapshot.status.getValue()) {
                if (showUntracked || entry.getKind() != GitStatusEntry.Kind.UNTRACKED) {
                    _status.add(entry);
                }
            }
        }
        _aheadBehind = snapshot.aheadBehind != null && snapshot.aheadBehind.isOk()
                ? snapshot.aheadBehind.getValue() : null;
        _statusAdapter.notifyDataSetChanged();
        syncFlowWithRepository();

        // Keep the registry's cheap metadata in step with the repository itself.
        if (_active != null) {
            final String branch = _info.getBranch();
            final String remote = _info.getRemoteUrl();
            boolean changed = false;
            if (branch != null && !branch.isEmpty() && !branch.equals(_active.getDefaultBranch())) {
                _active.setDefaultBranch(branch);
                changed = true;
            }
            if (remote != null && !remote.equals(_active.getRemoteUrl())) {
                _active.setRemoteUrl(remote);
                changed = true;
            }
            if (changed) {
                _registry.update(_active);
            }
        }
    }

    /** What one refresh reads. A non-OK {@code info} means the other two were never asked for. */
    private static final class Snapshot {
        private final GitResult<GitRepoInfo> info;
        private final GitResult<List<GitStatusEntry>> status;
        private final GitResult<GitAheadBehind> aheadBehind;

        private Snapshot(final GitResult<GitRepoInfo> info, final GitResult<List<GitStatusEntry>> status,
                         final GitResult<GitAheadBehind> aheadBehind) {
            this.info = info;
            this.status = status;
            this.aheadBehind = aheadBehind;
        }
    }

    private boolean isAlive() {
        return getView() != null;
    }

    // ---------------------------------------------------------------- history paging

    private void maybeLoadMoreHistory() {
        if (!UI.showHistory || _repoRoot == null) {
            return;
        }
        final RecyclerView.LayoutManager manager = _list.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return;
        }
        if (_pager.shouldLoadMore(((LinearLayoutManager) manager).findLastVisibleItemPosition())) {
            loadMoreHistory();
        }
    }

    private void loadMoreHistory() {
        final File root = _repoRoot;
        if (root == null || !_pager.beginLoad()) {
            return;
        }
        final int limit = _pager.getPageSize();
        final int skip = _pager.nextSkip();
        final int generation = _historyGeneration;
        GitTaskRunner.get().submit(root.getAbsolutePath(),
                token -> _git.log(root, limit, skip, GitUiProgress.cancelOnly(token)),
                this::isAlive,
                result -> {
                    if (generation != _historyGeneration) {
                        return; // The history was thrown away while this page was loading.
                    }
                    if (!result.isSuccess() || !result.getValue().isOk()) {
                        _pager.onPageFailed();
                        render();
                        return;
                    }
                    final int before = _pager.size();
                    _pager.onPageLoaded(result.getValue().getValue());
                    if (before == 0) {
                        _historyAdapter.notifyDataSetChanged();
                    } else {
                        _historyAdapter.notifyItemRangeInserted(before, _pager.size() - before);
                    }
                    render();
                });
    }

    // ---------------------------------------------------------------- repository selection (task 3.5)

    private void chooseFolder() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        MarkorFileBrowserFactory.showFolderDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
            @Override
            public void onFsViewerSelected(final String request, final File file, final Integer lineNumber) {
                useFolder(file);
            }

            @Override
            public void onFsViewerConfig(final GsFileBrowserOptions.Options dopt) {
                dopt.titleText = R.string.git_tab__select_folder;
            }
        }, getParentFragmentManager(), context);
    }

    /**
     * Takes a folder the user picked: refuses it when it is reached through the Storage Access
     * Framework (D6 — JGit needs a real {@code java.io.File} path), registers it when it is inside a
     * repository, and otherwise offers to initialize or clone into it.
     */
    private void useFolder(final File folder) {
        final Context context = getContext();
        if (context == null || folder == null) {
            return;
        }
        if (_cu.isUnderStorageAccessFolder(context, folder, true)) {
            showMessageDialog(R.string.git_tab__select_folder, context.getString(R.string.git_tab__folder_storage_access));
            return;
        }
        final File root = _git.findRepositoryRoot(folder);
        if (root == null) {
            UI.pendingFolder = folder;
            render();
            return;
        }
        activate(root, true);
    }

    /** Registers {@code root} when it is new, makes it the active repository and reloads. */
    private void activate(final File root, final boolean suggestIgnore) {
        final String path = root.getAbsolutePath();
        if (!_registry.contains(path)) {
            _registry.add(new GitRepoConfig(path));
        }
        _registry.setActive(path);
        UI.pendingFolder = null;
        if (suggestIgnore) {
            UI.ignoreSuggestedFor = null;
        }
        forgetRepositoryData();
        refresh(false);
    }

    private void showSwitchRepository() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        final List<GitRepoConfig> repos = new ArrayList<>(_registry.list());
        final List<String> rows = new ArrayList<>();
        for (final GitRepoConfig repo : repos) {
            rows.add(repo.getDisplayName() + "\n" + repo.getPath());
        }
        rows.add(getString(R.string.git_tab__add_folder));

        final GsSearchOrCustomTextDialog.DialogOptions dopt = MarkorDialogFactory.baseConf(activity);
        dopt.data = rows;
        dopt.titleText = R.string.git_tab__switch_repository;
        dopt.isSearchEnabled = repos.size() > 8;
        dopt.isSoftInputVisible = false;
        dopt.okButtonText = 0;
        dopt.positionCallback = indices -> {
            if (indices.isEmpty()) {
                return;
            }
            final int index = indices.get(0);
            if (index >= repos.size()) {
                chooseFolder();
            } else {
                activate(repos.get(index).getFile(), true);
            }
        };
        GsSearchOrCustomTextDialog.showMultiChoiceDialogWithSearchFilterUI(activity, dopt);
    }

    private void confirmRemoveRepository() {
        final Activity activity = getActivity();
        if (activity == null || _active == null) {
            return;
        }
        final GitRepoConfig repo = _active;
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(getString(R.string.git_tab__remove_title, repo.getDisplayName()))
                .setMessage(R.string.git_tab__remove_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.git_tab__remove, (d, w) -> removeRepository(repo))
                .show();
    }

    /** Forgets a repository. Files and commits are never touched. */
    private void removeRepository(final GitRepoConfig repo) {
        _registry.remove(repo.getPath());
        final List<GitRepoConfig> rest = _registry.list();
        if (!rest.isEmpty()) {
            _registry.setActive(rest.get(0).getPath());
        }
        snack(getString(R.string.git_tab__removed, repo.getDisplayName()));
        forgetRepositoryData();
        refresh(false);
    }

    private void initRepository(final File folder) {
        runOperation(folder.getAbsolutePath(), R.string.git_tab__initializing,
                (token, progress) -> _git.init(folder, progress),
                result -> {
                    if (result.isOk()) {
                        snack(getString(R.string.git_tab__initialized, folder.getAbsolutePath()));
                        activate(result.getValue().getWorkTree(), true);
                    } else {
                        showMessageDialog(R.string.git_tab__init_here, result.getMessage());
                    }
                });
    }

    private void cloneInto(final File folder) {
        if (folder != null) {
            final String[] children = folder.list();
            if (children != null && children.length > 0) {
                showMessageDialog(R.string.git_clone__title, getString(R.string.git_tab__clone_needs_empty_folder));
                return;
            }
        }
        final CloneDialog dialog = folder == null ? CloneDialog.newInstance() : CloneDialog.newInstance(null, folder);
        dialog.setListener(this::onCloned);
        dialog.show(getChildFragmentManager(), CloneDialog.FRAGMENT_TAG);
    }

    private void onCloned(final File repoRoot) {
        if (repoRoot != null) {
            activate(repoRoot, true);
        } else {
            refresh(false);
        }
    }

    private void maybeSuggestGitIgnore(final File root) {
        final Activity activity = getActivity();
        final String path = root.getAbsolutePath();
        if (activity == null || path.equals(UI.ignoreSuggestedFor)) {
            return;
        }
        UI.ignoreSuggestedFor = path;
        GitIgnoreSuggestDialog.maybeSuggest(activity, root, added -> {
            if (Boolean.TRUE.equals(added)) {
                refresh(false);
            }
        });
    }

    // ---------------------------------------------------------------- operations

    /** A git call that reports progress into the header and its result to the main thread. */
    private interface Operation<T> {
        GitResult<T> run(GitCancelToken token, GitProgress progress) throws Exception;
    }

    /**
     * Runs one foreground operation: header progress with Cancel, action row disabled, result on the
     * main thread, and a refresh afterwards. Only one runs at a time because the runner serializes
     * per repository and the action row is disabled while {@link #_opToken} is set.
     */
    private <T> void runOperation(@StringRes final int labelRes, final Operation<T> operation,
                                  final GsCallback.a1<GitResult<T>> onResult) {
        if (_repoRoot != null) {
            runOperation(_repoRoot.getAbsolutePath(), labelRes, operation, onResult);
        }
    }

    /**
     * Same, for an operation that has no open repository yet (initializing a folder): {@code queueKey}
     * is the path whose worker thread runs it. The header progress belongs to the repository screen,
     * so such an operation reports only through its result.
     */
    private <T> void runOperation(final String queueKey, @StringRes final int labelRes, final Operation<T> operation,
                                  final GsCallback.a1<GitResult<T>> onResult) {
        runOperation(queueKey, labelRes, operation, onResult, false);
    }

    /**
     * @param deliverEverything {@code false}: a cancelled or crashed task is reported to the user here and
     *                          {@code onResult} is not called; {@code true}: it is delivered as a CANCELLED
     *                          or FAILED result instead, for a caller that must hear back either way
     *                          (the pull state machine)
     * @return {@code false} when nothing was started because another operation is still running
     */
    private <T> boolean runOperation(final String queueKey, @StringRes final int labelRes, final Operation<T> operation,
                                     final GsCallback.a1<GitResult<T>> onResult, final boolean deliverEverything) {
        if (queueKey == null || _opToken != null) {
            return false;
        }
        _opLabel = getString(labelRes);
        _progressText.setText(_opLabel);
        final GitTask<GitResult<T>> task = token -> operation.run(token, new GitUiProgress(token, this::onProgress));
        _opToken = GitTaskRunner.get().submit(queueKey, task, this::isAlive,
                (GitTaskResult<GitResult<T>> result) -> {
                    _opToken = null;
                    _opLabel = null;
                    _progressText.setText("");
                    if (result.isCancelled()) {
                        if (deliverEverything) {
                            onResult.callback(GitResult.<T>cancelled());
                        } else {
                            snack(getString(R.string.git_tab__cancelled));
                            refresh(false);
                        }
                        return;
                    }
                    if (result.isError()) {
                        if (deliverEverything) {
                            onResult.callback(GitResult.<T>failed(getString(R.string.git_operation_failed)));
                        } else {
                            snack(getString(R.string.git_operation_failed));
                            refresh(false);
                        }
                        return;
                    }
                    onResult.callback(result.getValue());
                });
        render();
        return true;
    }

    private void onProgress(final String task, final int percent) {
        if (getView() == null || _opLabel == null) {
            return;
        }
        final String text = task == null || task.isEmpty() ? _opLabel : task;
        _progressText.setText(percent == GitProgress.UNKNOWN ? text : text + " " + percent + "%");
    }

    private void cancelOperation() {
        if (_opToken != null) {
            _opToken.cancel();
        }
    }

    private void showCommitDialog() {
        if (_repoRoot == null) {
            return;
        }
        flushOpenEditors(); // task 4.2: the dialog's checklist must see what the To-Do and QuickNote tabs show
        CommitDialog.newInstance(_repoRoot, this::onCommitted)
                .show(getChildFragmentManager(), CommitDialog.FRAGMENT_TAG);
    }

    private void onCommitted(final String sha, final boolean pushRequested) {
        if (_flow != null && _flow.getState() == GitPullFlow.State.NEEDS_COMMIT) {
            // The commit the pull asked for ("Commit first"): the pull goes on, the push waits for it.
            _pushAfterPull = pushRequested;
            _flow.onCommitted();
            return;
        }
        if (pushRequested) {
            refreshThen(this::push);
        } else {
            refresh(false);
        }
    }

    /** Refreshes first so that {@code _info} and the ahead count the follow-up needs are current. */
    private void refreshThen(final Runnable action) {
        _afterRefresh = action;
        refresh(false);
    }

    // ---------------------------------------------------------------- pull (task 5.3)

    /**
     * Starts a fast-forward pull. Everything after that — the rebase / merge question when the branches
     * diverged, <i>Commit first</i> for uncommitted edits, the conflict banner with <i>Mark resolved</i> and
     * <i>Abort</i> — is {@link GitPullFlow}'s, which drives this fragment through {@link FlowHost}.
     */
    private void pull() {
        if (_repoRoot == null || _flow == null) {
            return;
        }
        if (_info != null && !_info.hasRemote()) {
            promptForRemote(this::pull);
            return;
        }
        flushOpenEditors(); // task 4.2: never pull over an edit that is only in an editor
        _flow.startPull(configuredStrategy());
    }

    private GitPullFlow newFlow(final File root) {
        final FlowHost host = new FlowHost();
        final GitPullFlow flow = new GitPullFlow(_git, root, credentials(), host);
        host.flow = flow;
        flow.setPreferredStrategy(preferredStrategy());
        return flow;
    }

    /**
     * Settings &gt; Git &gt; <i>Default pull strategy</i> (task 7.1), which is what the <i>Pull</i>
     * button does. {@code FF_ONLY} (the default) only fast-forwards and lets {@link GitPullFlow} ask
     * the rebase-or-merge question when the branches diverged; the other two pull that way straight
     * away. It is one setting for every repository; {@link GitRepoConfig#getPullStrategy()} is kept
     * in the model for a per-repository override that does not exist yet.
     */
    private GitPullStrategy configuredStrategy() {
        final String name = _appSettings == null ? null : _appSettings.getGitDefaultPullStrategy();
        if (name == null) {
            return GitPullStrategy.FF_ONLY;
        }
        try {
            return GitPullStrategy.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return GitPullStrategy.FF_ONLY;
        }
    }

    /** Which button the diverged question preselects: the configured strategy, but never fast-forward. */
    private GitPullStrategy preferredStrategy() {
        return configuredStrategy() == GitPullStrategy.MERGE ? GitPullStrategy.MERGE : GitPullStrategy.REBASE;
    }

    /**
     * After every refresh: tells the machine what the repository itself is in the middle of, so the
     * banner comes back after process death and goes away when another git client finished the merge.
     */
    private void syncFlowWithRepository() {
        if (_flow == null || _info == null) {
            return;
        }
        _flow.setPreferredStrategy(preferredStrategy());
        final List<String> conflicts = new ArrayList<>();
        for (final GitStatusEntry entry : _status) {
            if (entry.getKind() == GitStatusEntry.Kind.CONFLICT) {
                conflicts.add(entry.getPath());
            }
        }
        _flow.syncWithRepository(_info.getState(), conflicts);
    }

    private void markResolved() {
        if (_flow == null) {
            return;
        }
        flushOpenEditors();
        // null: a merge commit gets git's prepared message, a rebase keeps its commits' own messages.
        _flow.markResolved(null);
    }

    private void confirmAbort() {
        final Activity activity = getActivity();
        if (activity == null || _flow == null) {
            return;
        }
        final GitPullFlow flow = _flow;
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_tab__abort_title)
                .setMessage(R.string.git_tab__abort_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.git_tab__abort, (d, w) -> flow.abort())
                .show();
    }

    /**
     * Roadmap task 4.2: the To-Do and QuickNote tabs stay alive next to this one and save only on their
     * own pause, which a tab switch does not trigger. Write them out before git looks at the tree.
     */
    private void flushOpenEditors() {
        final Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).saveOpenEditors();
        }
    }

    @StringRes
    private static int labelFor(final GitPullFlow.Op op) {
        switch (op) {
            case PULL_REBASE:
                return R.string.git_tab__rebasing;
            case PULL_MERGE:
                return R.string.git_tab__merging;
            case CHECK_MARKERS:
                return R.string.git_tab__checking_markers;
            case CONTINUE:
                return R.string.git_tab__finishing;
            case ABORT:
                return R.string.git_tab__aborting;
            case PULL_FF_ONLY:
            default:
                return R.string.git_tab__pulling;
        }
    }

    @StringRes
    private static int labelFor(final GitPullStrategy strategy) {
        return strategy == GitPullStrategy.MERGE ? R.string.git_tab__merge : R.string.git_tab__rebase;
    }

    /**
     * What {@link GitPullFlow} needs from this fragment: the worker thread with header progress, the
     * author prompt, the two questions, the commit dialog, and the messages once a step is done.
     */
    private final class FlowHost implements GitPullFlow.Host {
        private GitPullFlow flow;

        @Override
        public <T> void run(final GitPullFlow.Op op, final GitPullFlow.Work<T> work, final GsCallback.a1<GitResult<T>> onResult) {
            final boolean started = runOperation(flow.getRoot().getAbsolutePath(), labelFor(op),
                    (token, progress) -> work.run(progress), onResult, true);
            if (!started) {
                onResult.callback(GitResult.<T>failed(getString(R.string.git_operation_failed)));
            }
        }

        @Override
        public void requireAuthor(final GsCallback.a1<GitAuthor> onAuthor) {
            final Activity activity = getActivity();
            if (activity == null) {
                onAuthor.callback(null);
                return;
            }
            GitAuthorDialog.requireAuthor(activity, flow.getRoot(), onAuthor::callback, () -> onAuthor.callback(null));
        }

        @Override
        public void showDiverged(final GitPullStrategy preselected) {
            final Activity activity = getActivity();
            if (activity == null) {
                flow.cancel();
                return;
            }
            final GitPullStrategy other = preselected == GitPullStrategy.MERGE ? GitPullStrategy.REBASE : GitPullStrategy.MERGE;
            final boolean[] answered = {false};
            new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                    .setTitle(R.string.git_tab__diverged_title)
                    .setMessage(R.string.git_tab__diverged_message)
                    .setPositiveButton(labelFor(preselected), (d, w) -> {
                        answered[0] = true;
                        flow.chooseStrategy(preselected);
                    })
                    .setNeutralButton(labelFor(other), (d, w) -> {
                        answered[0] = true;
                        flow.chooseStrategy(other);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener(d -> {
                        if (!answered[0]) {
                            flow.cancel();
                        }
                    })
                    .show();
        }

        @Override
        public void showCommitFirst(final List<String> files) {
            final Activity activity = getActivity();
            if (activity == null) {
                flow.cancel();
                return;
            }
            final boolean[] answered = {false};
            new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                    .setTitle(R.string.git_tab__dirty_title)
                    .setMessage(getString(R.string.git_tab__dirty_message, fileList(files)))
                    .setPositiveButton(R.string.git_tab__commit_first, (d, w) -> {
                        answered[0] = true;
                        flow.commitFirst();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener(d -> {
                        if (!answered[0]) {
                            flow.cancel();
                        }
                    })
                    .show();
        }

        @Override
        public void openCommitDialog() {
            showCommitDialog();
        }

        @Override
        public void showStillConflicted(final List<String> files) {
            showMessageDialog(R.string.git_tab__still_conflicted_title,
                    getString(R.string.git_tab__still_conflicted_message, fileList(files)));
        }

        @Override
        public void showConflictsRemain(final List<String> files) {
            showMessageDialog(R.string.git_tab__conflicts_remain_title,
                    getString(R.string.git_tab__conflicts_remain_message, fileList(files)));
        }

        @Override
        public void onStateChanged(final GitPullFlow.State previous, final GitPullFlow.State current) {
            if (flow != _flow) {
                return; // a machine of a repository the user has since switched away from
            }
            if (flow.isResolving() && !_wasResolving) {
                UI.showHistory = false; // the conflicted files are in the Changes list
            }
            _wasResolving = flow.isResolving();
            render();
        }

        @Override
        public void onFinished(final GitPullFlow.Op op, final GitResult<?> result) {
            final boolean pull = op == GitPullFlow.Op.PULL_FF_ONLY || op == GitPullFlow.Op.PULL_REBASE || op == GitPullFlow.Op.PULL_MERGE;
            switch (result.getKind()) {
                case OK:
                    if (pull) {
                        _lastFetchMillis = System.currentTimeMillis();
                        snack(getString(R.string.git_tab__pulled));
                        if (_pushAfterPull) {
                            _pushAfterPull = false;
                            refreshThen(GitFragment.this::push);
                            return;
                        }
                    } else if (op == GitPullFlow.Op.CONTINUE) {
                        snack(getString(R.string.git_tab__resolved));
                    } else if (op == GitPullFlow.Op.ABORT) {
                        snack(getString(R.string.git_tab__aborted));
                    }
                    break;
                case AUTH_FAILED:
                    promptForRemote(GitFragment.this::pull);
                    break;
                case CANCELLED:
                    snack(getString(R.string.git_tab__cancelled));
                    break;
                case CONFLICTS:
                case NON_FAST_FORWARD:
                case DIRTY_WORK_TREE:
                    break; // the machine put up the banner or a question
                default:
                    final Context context = getContext();
                    if (context != null) {
                        snack(GitUiText.messageFor(context, result));
                    }
                    break;
            }
            if (pull) {
                _pushAfterPull = false;
            }
            refresh(false);
        }
    }

    // ---------------------------------------------------------------- push (task 5.4)

    private void push() {
        if (_repoRoot == null) {
            return;
        }
        if (_info != null && !_info.hasRemote()) {
            promptForRemote(this::push);
            return;
        }
        // Settings > Git > "Confirm before push" (task 7.1), off by default.
        final Activity activity = getActivity();
        if (_appSettings != null && _appSettings.isGitConfirmBeforePush() && activity != null) {
            confirmPush(activity);
            return;
        }
        doPush();
    }

    private void doPush() {
        if (_repoRoot == null) {
            return;
        }
        final File root = _repoRoot;
        // The contract's push returns no count, so the ahead count from before the push is used.
        final int ahead = _aheadBehind != null && _aheadBehind.hasUpstream() ? _aheadBehind.getAhead() : 0;
        runOperation(R.string.git_tab__pushing,
                (token, progress) -> _git.push(root, credentials(), progress),
                result -> {
                    switch (result.getKind()) {
                        case OK:
                            snack(ahead > 0
                                    ? getResources().getQuantityString(R.plurals.git_tab__pushed, ahead, ahead)
                                    : getString(R.string.git_tab__push_up_to_date));
                            break;
                        case NON_FAST_FORWARD:
                            showPushRejected();
                            break;
                        case AUTH_FAILED:
                            // The push itself was already confirmed; do not ask twice for one tap.
                            promptForRemote(this::doPush);
                            break;
                        default:
                            snack(GitUiText.messageFor(requireContext(), result));
                            break;
                    }
                    refresh(false);
                });
    }

    /**
     * Asks before any commit leaves the device (task 7.1). The remote URL shown here is the one
     * {@link GitRepoInfo} sanitised, so it never carries a username or a token.
     */
    private void confirmPush(final Activity activity) {
        final String remote = _info != null && _info.getRemoteUrl() != null
                ? _info.getRemoteUrl() : getString(R.string.git_tab__remote);
        final int ahead = _aheadBehind != null && _aheadBehind.hasUpstream() ? _aheadBehind.getAhead() : 0;
        final String message = ahead > 0
                ? getResources().getQuantityString(R.plurals.git_tab__push_confirm_message, ahead, ahead, remote)
                : getString(R.string.git_tab__push_confirm_message_unknown, remote);
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_tab__push_confirm_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.git_tab__push, (d, w) -> doPush())
                .show();
    }

    private void showPushRejected() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_tab__push_rejected_title)
                .setMessage(R.string.git_tab__push_rejected_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.git_tab__pull_first, (d, w) -> pull())
                .show();
    }

    /**
     * Opens the remote setup dialog pre-filled for the active repository and repeats {@code retry}
     * once the remote (and its credentials) were saved.
     */
    private void promptForRemote(final Runnable retry) {
        if (_repoRoot == null) {
            return;
        }
        final RemoteSetupDialog dialog = RemoteSetupDialog.newInstance(_repoRoot.getAbsolutePath());
        dialog.setListener((repoPath, remoteUrl) -> refreshThen(retry));
        dialog.show(getChildFragmentManager(), RemoteSetupDialog.FRAGMENT_TAG);
    }

    private void showRemoteSetup() {
        if (_repoRoot == null) {
            return;
        }
        final RemoteSetupDialog dialog = RemoteSetupDialog.newInstance(_repoRoot.getAbsolutePath());
        dialog.setListener((repoPath, remoteUrl) -> refresh(false));
        dialog.show(getChildFragmentManager(), RemoteSetupDialog.FRAGMENT_TAG);
    }

    // ---------------------------------------------------------------- fetch (task 5.5)

    private void fetch() {
        if (_repoRoot == null) {
            return;
        }
        if (_info != null && !_info.hasRemote()) {
            promptForRemote(this::fetch);
            return;
        }
        final File root = _repoRoot;
        FETCH_THROTTLE.reset(root.getAbsolutePath());
        runOperation(R.string.git_tab__fetching,
                (token, progress) -> _git.fetch(root, credentials(), progress),
                result -> {
                    if (result.isOk()) {
                        _aheadBehind = result.getValue();
                        _lastFetchMillis = System.currentTimeMillis();
                    } else if (result.getKind() == GitResult.Kind.AUTH_FAILED) {
                        promptForRemote(this::fetch);
                    } else {
                        snack(GitUiText.messageFor(requireContext(), result));
                    }
                    refresh(false);
                });
    }

    /**
     * Roadmap task 5.5: when the repository asks for it and the device has a network, fetch at most
     * once per {@link GitFetchThrottle#DEFAULT_INTERVAL_MILLIS} as the tab becomes visible, then
     * recompute the header. Failures are silent — the header says "offline" at most.
     */
    private void maybeFetchOnOpen() {
        final Context context = getContext();
        final File root = _repoRoot;
        if (context == null || root == null || _active == null || _info == null || !_info.hasRemote()) {
            return;
        }
        final String path = root.getAbsolutePath();
        final long now = System.currentTimeMillis();
        if (!FETCH_THROTTLE.shouldFetch(path, isFetchOnOpenEnabled(), isOnline(context), _lastFetchMillis, now)) {
            return;
        }
        FETCH_THROTTLE.recordAttempt(path, now);
        GitTaskRunner.get().submit(path,
                token -> _git.fetch(root, credentials(), GitUiProgress.cancelOnly(token)),
                this::isAlive,
                result -> {
                    if (!result.isSuccess() || !result.getValue().isOk()) {
                        render(); // Silent: only the "offline" hint in the header may change.
                        return;
                    }
                    _aheadBehind = result.getValue().getValue();
                    _lastFetchMillis = System.currentTimeMillis();
                    render();
                });
    }

    /** Settings &gt; Git &gt; <i>Fetch when the tab opens</i> (task 7.1), on by default. */
    private boolean isFetchOnOpenEnabled() {
        return _appSettings != null && _appSettings.isGitFetchOnOpen();
    }

    private static boolean isOnline(final Context context) {
        final ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        final Network network = manager.getActiveNetwork();
        final NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private GitCredentialsSource credentials() {
        final Context context = getContext();
        return context == null ? GitCredentialsSource.NONE : GitCredentialStore.get(context).asSource();
    }

    // ---------------------------------------------------------------- overflow menu

    private void showOverflow(final View anchor) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final PopupMenu popup = new PopupMenu(context, anchor);
        popup.inflate(R.menu.git__fragment__overflow);
        // "Switch repository" stays available; the rest would race with the running operation.
        final boolean busy = isBusy();
        for (final int id : new int[]{R.id.git__fragment__menu_fetch, R.id.git__fragment__menu_remote,
                R.id.git__fragment__menu_remove}) {
            final MenuItem item = popup.getMenu().findItem(id);
            if (item != null) {
                item.setEnabled(!busy);
            }
        }
        popup.setOnMenuItemClickListener(item -> {
            final int id = item.getItemId();
            if (id == R.id.git__fragment__menu_fetch) {
                fetch();
            } else if (id == R.id.git__fragment__menu_switch) {
                showSwitchRepository();
            } else if (id == R.id.git__fragment__menu_remote) {
                showRemoteSetup();
            } else if (id == R.id.git__fragment__menu_remove) {
                confirmRemoveRepository();
            } else {
                return false;
            }
            return true;
        });
        popup.show();
    }

    // ---------------------------------------------------------------- small helpers

    private void snack(final String text) {
        final View view = getView();
        if (view == null || text == null || text.isEmpty()) {
            return;
        }
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
    }

    private void showMessageDialog(@StringRes final int title, final String message) {
        showMessageDialog(title, message, android.R.string.ok);
    }

    private void showMessageDialog(@StringRes final int title, final String message, @StringRes final int button) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(button, null)
                .show();
    }

    private static String fileList(final List<String> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        final int shown = Math.min(files.size(), 10);
        for (int i = 0; i < shown; i++) {
            builder.append(files.get(i));
            if (i < shown - 1) {
                builder.append('\n');
            }
        }
        if (files.size() > shown) {
            builder.append("\n…");
        }
        return builder.toString();
    }

    private void openInEditor(final String path) {
        final Activity activity = getActivity();
        final File file = _repoRoot == null ? null : new File(_repoRoot, path);
        if (activity == null || file == null) {
            return;
        }
        if (!file.isFile()) {
            Toast.makeText(activity, R.string.git_file_not_in_working_tree, Toast.LENGTH_SHORT).show();
            return;
        }
        DocumentActivity.launch(activity, file, null, null);
    }

    /** The letter git itself shows in {@code git status}. */
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

    @StringRes
    private static int nameOf(final GitStatusEntry.Kind kind) {
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

    // ---------------------------------------------------------------- Changes list (task 3.6)

    private class StatusAdapter extends RecyclerView.Adapter<StatusHolder> {
        @NonNull
        @Override
        public StatusHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new StatusHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.git__fragment__status_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final StatusHolder holder, final int position) {
            final GitStatusEntry entry = _status.get(position);
            holder.bind(entry);
            holder.itemView.setOnClickListener(v -> {
                final Activity activity = getActivity();
                if (activity != null && _repoRoot != null) {
                    DiffViewerActivity.launch(activity, _repoRoot, entry.getPath(), null);
                }
            });
            holder.itemView.setOnLongClickListener(v -> {
                openInEditor(entry.getPath());
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return _status.size();
        }
    }

    private static class StatusHolder extends RecyclerView.ViewHolder {
        private final TextView _kind;
        private final TextView _path;
        private final TextView _kindName;

        private StatusHolder(final View row) {
            super(row);
            _kind = row.findViewById(R.id.git__fragment__status_item__kind);
            _path = row.findViewById(R.id.git__fragment__status_item__path);
            _kindName = row.findViewById(R.id.git__fragment__status_item__kind_name);
        }

        private void bind(final GitStatusEntry entry) {
            final int name = nameOf(entry.getKind());
            _kind.setText(letterOf(entry.getKind()));
            _kind.setContentDescription(itemView.getContext().getString(name));
            _path.setText(entry.getPath());
            _kindName.setText(name);
        }
    }

    // ---------------------------------------------------------------- History list (task 3.7)

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryHolder> {
        @NonNull
        @Override
        public HistoryHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new HistoryHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.git__fragment__history_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final HistoryHolder holder, final int position) {
            final GitCommitInfo commit = _pager.get(position);
            holder.bind(commit);
            holder.itemView.setOnClickListener(v -> {
                final Activity activity = getActivity();
                if (activity != null && _repoRoot != null) {
                    CommitDetailActivity.launch(activity, _repoRoot, commit);
                }
            });
        }

        @Override
        public int getItemCount() {
            return _pager.size();
        }
    }

    private static class HistoryHolder extends RecyclerView.ViewHolder {
        private final TextView _sha;
        private final TextView _subject;
        private final TextView _meta;

        private HistoryHolder(final View row) {
            super(row);
            _sha = row.findViewById(R.id.git__fragment__history_item__sha);
            _subject = row.findViewById(R.id.git__fragment__history_item__subject);
            _meta = row.findViewById(R.id.git__fragment__history_item__meta);
        }

        private void bind(final GitCommitInfo commit) {
            _sha.setText(commit.getShortSha());
            _subject.setText(commit.getSubject());
            final CharSequence when = DateUtils.getRelativeTimeSpanString(
                    commit.getEpochSeconds() * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            final String author = commit.getAuthorName();
            _meta.setText(author == null || author.isEmpty() ? when : author + " · " + when);
        }
    }
}
