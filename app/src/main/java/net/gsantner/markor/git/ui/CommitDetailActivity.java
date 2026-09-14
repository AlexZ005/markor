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
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.DocumentActivity;
import net.gsantner.markor.activity.MarkorBaseActivity;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitCommitInfo;
import net.gsantner.markor.git.GitDiff;
import net.gsantner.markor.git.GitPaths;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.JGitService;
import net.gsantner.opoc.frontend.GsSearchOrCustomTextDialog;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One commit in detail: full hash (tap to copy), author, date, message and the files it changed with
 * their change kind and +/- counts. Tapping a file opens its diff at this commit; the toolbar opens
 * the current working-tree version of a file in the editor.
 */
public class CommitDetailActivity extends MarkorBaseActivity {

    /** {@link File}, the repository working folder. Required. */
    public static final String EXTRA_REPO_ROOT = "EXTRA_GIT_REPO_ROOT";

    /** {@link String}, the commit id. Required. */
    public static final String EXTRA_SHA = "EXTRA_GIT_SHA";

    // Optional: metadata the caller already has, so the history walk below can be skipped.
    private static final String EXTRA_SUBJECT = "EXTRA_GIT_SUBJECT";
    private static final String EXTRA_BODY = "EXTRA_GIT_BODY";
    private static final String EXTRA_AUTHOR_NAME = "EXTRA_GIT_AUTHOR_NAME";
    private static final String EXTRA_AUTHOR_EMAIL = "EXTRA_GIT_AUTHOR_EMAIL";
    private static final String EXTRA_EPOCH_SECONDS = "EXTRA_GIT_EPOCH_SECONDS";

    /** Commits read per {@code log} call while looking for a commit by id. */
    private static final int LOOKUP_PAGE_SIZE = 200;

    /** Upper bound on that search, so a huge history cannot make this screen spin forever. */
    private static final int LOOKUP_MAX_COMMITS = 10000;

    /**
     * Opens the commit detail screen.
     *
     * @param activity calling activity
     * @param repoRoot the repository working folder (any path inside it works)
     * @param sha      full or abbreviated commit id
     */
    public static void launch(final Activity activity, final File repoRoot, final String sha) {
        if (activity == null || repoRoot == null || sha == null || sha.isEmpty()) {
            return;
        }

        final Intent intent = new Intent(activity, CommitDetailActivity.class);
        intent.putExtra(EXTRA_REPO_ROOT, repoRoot);
        intent.putExtra(EXTRA_SHA, sha);
        activity.startActivity(intent);
    }

    /**
     * Same, for a caller that already holds the commit (the History list does). Saves this screen
     * the walk through {@code log} that looking a commit up by id alone costs.
     */
    public static void launch(final Activity activity, final File repoRoot, final GitCommitInfo commit) {
        if (activity == null || repoRoot == null || commit == null) {
            return;
        }

        final Intent intent = new Intent(activity, CommitDetailActivity.class);
        intent.putExtra(EXTRA_REPO_ROOT, repoRoot);
        intent.putExtra(EXTRA_SHA, commit.getSha());
        intent.putExtra(EXTRA_SUBJECT, commit.getSubject());
        intent.putExtra(EXTRA_BODY, commit.getBody());
        intent.putExtra(EXTRA_AUTHOR_NAME, commit.getAuthorName());
        intent.putExtra(EXTRA_AUTHOR_EMAIL, commit.getAuthorEmail());
        intent.putExtra(EXTRA_EPOCH_SECONDS, commit.getEpochSeconds());
        activity.startActivity(intent);
    }

    private static final String LOG_TAG = "CommitDetail";

    private final GitService _git = new JGitService();

    private File _repoRoot;
    private String _sha;
    private GitCommitInfo _commit;
    private List<GitDiff.FileChange> _files = Collections.emptyList();
    private GitCancelToken _cancelToken;

    private ProgressBar _progress;
    private TextView _messageBox;
    private View _scroll;
    private FileAdapter _adapter;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _repoRoot = (File) getIntent().getSerializableExtra(EXTRA_REPO_ROOT);
        _sha = getIntent().getStringExtra(EXTRA_SHA);
        if (_repoRoot == null || _sha == null || _sha.isEmpty()) {
            finish();
            return;
        }
        _commit = commitFromIntent();

        setContentView(R.layout.git__commit_detail__activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.git_commit);
            getSupportActionBar().setSubtitle(shortSha());
        }

        _progress = findViewById(R.id.git_commit_detail__progress);
        _messageBox = findViewById(R.id.git_commit_detail__message_box);
        _scroll = findViewById(R.id.git_commit_detail__scroll);

        final RecyclerView files = findViewById(R.id.git_commit_detail__files);
        files.setLayoutManager(new LinearLayoutManager(this));
        files.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        _adapter = new FileAdapter();
        files.setAdapter(_adapter);

        bindHeader();
        load();
    }

    private GitCommitInfo commitFromIntent() {
        final Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_EPOCH_SECONDS)) {
            return null;
        }
        return new GitCommitInfo(
                _sha,
                intent.getStringExtra(EXTRA_SUBJECT),
                intent.getStringExtra(EXTRA_BODY),
                intent.getStringExtra(EXTRA_AUTHOR_NAME),
                intent.getStringExtra(EXTRA_AUTHOR_EMAIL),
                intent.getLongExtra(EXTRA_EPOCH_SECONDS, 0));
    }

    private String shortSha() {
        final String sha = _commit != null ? _commit.getSha() : _sha;
        return sha.length() > GitCommitInfo.SHORT_SHA_LENGTH ? sha.substring(0, GitCommitInfo.SHORT_SHA_LENGTH) : sha;
    }

    private void bindHeader() {
        final String fullSha = _commit != null ? _commit.getSha() : _sha;
        final TextView shaView = findViewById(R.id.git_commit_detail__sha);
        shaView.setText(fullSha);
        shaView.setOnClickListener(v -> {
            _cu.setClipboard(this, fullSha);
            Toast.makeText(this, R.string.git_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        });

        final TextView author = findViewById(R.id.git_commit_detail__author);
        final TextView date = findViewById(R.id.git_commit_detail__date);
        final TextView message = findViewById(R.id.git_commit_detail__message);

        if (_commit == null) {
            // The id resolved to a diff but the commit was not found in the history walked below.
            author.setVisibility(View.GONE);
            date.setVisibility(View.GONE);
            message.setVisibility(View.GONE);
            return;
        }

        author.setVisibility(View.VISIBLE);
        date.setVisibility(View.VISIBLE);
        message.setVisibility(View.VISIBLE);

        final String email = _commit.getAuthorEmail();
        author.setText(email.isEmpty() ? _commit.getAuthorName() : _commit.getAuthorName() + " <" + email + ">");
        date.setText(DateFormat
                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(_commit.getEpochSeconds() * 1000L)));

        final String body = _commit.getBody();
        message.setText(body.isEmpty() ? _commit.getSubject() : _commit.getSubject() + "\n\n" + body);
    }

    // The whole list is replaced at once, so there is no finer-grained event to send.
    @SuppressLint("NotifyDataSetChanged")
    private void load() {
        final File repoRoot = _repoRoot;
        final String sha = _sha;
        final boolean needCommit = _commit == null;

        _cancelToken = GitTaskRunner.get().submit(
                repoRoot.getAbsolutePath(),
                token -> {
                    final GitProgress progress = GitUiProgress.cancelOnly(token);
                    final GitResult<GitDiff> diff = _git.diffForCommit(repoRoot, sha, progress);
                    return new Detail(needCommit ? findCommit(repoRoot, sha, progress) : null, diff);
                },
                () -> !isFinishing() && !isDestroyed(),
                result -> {
                    _progress.setVisibility(View.GONE);
                    if (result.isCancelled()) {
                        return;
                    }
                    if (result.isError()) {
                        Log.w(LOG_TAG, "Loading the commit failed", result.getError());
                        showMessage(getString(R.string.error_could_not_open_file));
                        return;
                    }

                    final Detail detail = result.getValue();
                    if (!detail.diff.isOk()) {
                        showMessage(detail.diff.getMessage());
                        return;
                    }
                    if (detail.commit != null) {
                        _commit = detail.commit;
                        bindHeader();
                        if (getSupportActionBar() != null) {
                            getSupportActionBar().setSubtitle(shortSha());
                        }
                    }

                    _files = detail.diff.getValue().getFiles();
                    _adapter.notifyDataSetChanged();
                    ((TextView) findViewById(R.id.git_commit_detail__files_header))
                            .setText(getString(R.string.git_changed_files_count, _files.size()));
                    invalidateOptionsMenu();
                });
    }

    /**
     * Finds a commit by id. {@code GitService} has no "describe this commit" call, so the history is
     * walked in pages until the id matches or {@link #LOOKUP_MAX_COMMITS} commits have been seen.
     *
     * @return the commit, or {@code null} when it is not in the first {@link #LOOKUP_MAX_COMMITS} commits of HEAD
     */
    private GitCommitInfo findCommit(final File repoRoot, final String sha, final GitProgress progress) {
        final String wanted = sha.toLowerCase(Locale.ROOT);
        for (int skip = 0; skip < LOOKUP_MAX_COMMITS && !progress.isCancelled(); skip += LOOKUP_PAGE_SIZE) {
            final GitResult<List<GitCommitInfo>> page = _git.log(repoRoot, LOOKUP_PAGE_SIZE, skip, progress);
            if (!page.isOk() || page.getValue().isEmpty()) {
                return null;
            }
            for (final GitCommitInfo commit : page.getValue()) {
                if (commit.getSha().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                    return commit;
                }
            }
            if (page.getValue().size() < LOOKUP_PAGE_SIZE) {
                return null; // end of history
            }
        }
        return null;
    }

    private void showMessage(final String message) {
        _scroll.setVisibility(View.GONE);
        _messageBox.setText(message);
        _messageBox.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ "Open current version"

    /** @return the changed files that still exist in the working tree */
    private List<GitDiff.FileChange> filesInWorkingTree() {
        final List<GitDiff.FileChange> existing = new ArrayList<>();
        for (final GitDiff.FileChange file : _files) {
            final File inTree = GitPaths.resolveInside(_repoRoot, file.getPath());
            if (inTree != null && inTree.isFile()) {
                existing.add(file);
            }
        }
        return existing;
    }

    private void openCurrentVersion() {
        final List<GitDiff.FileChange> existing = filesInWorkingTree();
        if (existing.isEmpty()) {
            Toast.makeText(this, R.string.git_file_not_in_working_tree, Toast.LENGTH_SHORT).show();
            return;
        }
        if (existing.size() == 1) {
            openInEditor(existing.get(0).getPath());
            return;
        }

        final List<String> paths = new ArrayList<>();
        for (final GitDiff.FileChange file : existing) {
            paths.add(file.getPath());
        }

        final GsSearchOrCustomTextDialog.DialogOptions dopt = MarkorDialogFactory.baseConf(this);
        dopt.data = paths;
        dopt.titleText = R.string.git_open_current_version;
        dopt.isSearchEnabled = paths.size() > 8;
        dopt.isSoftInputVisible = false;
        dopt.okButtonText = 0;
        dopt.positionCallback = indices -> {
            if (!indices.isEmpty()) {
                openInEditor(paths.get(indices.get(0)));
            }
        };
        GsSearchOrCustomTextDialog.showMultiChoiceDialogWithSearchFilterUI(this, dopt);
    }

    private void openInEditor(final String path) {
        final File file = GitPaths.resolveInside(_repoRoot, path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.git_file_not_in_working_tree, Toast.LENGTH_SHORT).show();
            return;
        }
        DocumentActivity.launch(this, file, null, null);
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.git__commit_detail__menu, menu);
        _cu.tintMenuItems(menu, true, _cu.rcolor(this, R.color.dark__primary_text));
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final MenuItem open = menu.findItem(R.id.git_commit_detail__open_current);
        if (open != null) {
            open.setVisible(!filesInWorkingTree().isEmpty());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.git_commit_detail__open_current) {
            openCurrentVersion();
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (_cancelToken != null) {
            _cancelToken.cancel();
        }
        super.onDestroy();
    }

    /** What one load produces: the commit (when it had to be looked up) and its diff. */
    private static final class Detail {
        private final GitCommitInfo commit;
        private final GitResult<GitDiff> diff;

        private Detail(final GitCommitInfo commit, final GitResult<GitDiff> diff) {
            this.commit = commit;
            this.diff = diff;
        }
    }

    // ------------------------------------------------------------------ file list

    /** The letter git itself uses in {@code git status} / {@code git show --name-status}. */
    private static String kindLetter(final GitDiff.ChangeKind kind) {
        switch (kind) {
            case ADDED:
                return "A";
            case DELETED:
                return "D";
            case RENAMED:
                return "R";
            case COPIED:
                return "C";
            case MODIFIED:
            default:
                return "M";
        }
    }

    private static String displayPath(final GitDiff.FileChange file) {
        final String old = file.getOldPath();
        return old == null ? file.getPath() : old + " → " + file.getPath();
    }

    private class FileAdapter extends RecyclerView.Adapter<FileHolder> {

        @NonNull
        @Override
        public FileHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.git__commit_detail__file_item, parent, false);
            return new FileHolder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull final FileHolder holder, final int position) {
            final GitDiff.FileChange file = _files.get(position);
            holder.bind(file);
            holder.itemView.setOnClickListener(v -> DiffViewerActivity.launch(
                    CommitDetailActivity.this, _repoRoot, file.getPath(), _sha));
            holder.itemView.setOnLongClickListener(v -> {
                openInEditor(file.getPath());
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return _files.size();
        }
    }

    private static class FileHolder extends RecyclerView.ViewHolder {
        private final TextView _kind;
        private final TextView _path;
        private final TextView _added;
        private final TextView _deleted;

        private FileHolder(final View row) {
            super(row);
            _kind = row.findViewById(R.id.git_commit_detail__item_kind);
            _path = row.findViewById(R.id.git_commit_detail__item_path);
            _added = row.findViewById(R.id.git_commit_detail__item_added);
            _deleted = row.findViewById(R.id.git_commit_detail__item_deleted);
        }

        private void bind(final GitDiff.FileChange file) {
            _kind.setText(kindLetter(file.getKind()));
            _path.setText(displayPath(file));

            final Context context = itemView.getContext();
            if (file.isBinary()) {
                _added.setTextColor(ContextCompat.getColor(context, R.color.secondary_text));
                _added.setText(R.string.git_binary_file);
                _deleted.setText("");
            } else {
                _added.setTextColor(ContextCompat.getColor(context, R.color.git_diff_add_fg));
                _added.setText(context.getString(R.string.git_diff_lines_added, file.getLinesAdded()));
                _deleted.setText(context.getString(R.string.git_diff_lines_deleted, file.getLinesDeleted()));
            }
        }
    }
}
