/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.MarkorBaseActivity;
import net.gsantner.markor.git.GitCancelToken;
import net.gsantner.markor.git.GitCommitInfo;
import net.gsantner.markor.git.GitDiff;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.JGitService;

import java.io.File;
import java.util.List;

/**
 * Shows one unified diff: the working tree against HEAD, or a commit against its parent, for the
 * whole repository or for a single file.
 * <p>
 * The diff is loaded off the main thread through {@link GitTaskRunner} and rendered as a
 * {@link SpannableString} in a monospace {@link TextView} inside a vertical and a horizontal scroll
 * container - no WebView. Which line gets which style is decided by {@link DiffTextFormatter}, which
 * also caps the rendering of very large diffs.
 */
public class DiffViewerActivity extends MarkorBaseActivity {

    /** {@link File}, the repository working folder. Required. */
    public static final String EXTRA_REPO_ROOT = "EXTRA_GIT_REPO_ROOT";

    /** {@link String}, repository-relative path, or absent for the whole tree. */
    public static final String EXTRA_PATH = "EXTRA_GIT_PATH";

    /** {@link String}, commit id, or absent for the working tree against HEAD. */
    public static final String EXTRA_SHA = "EXTRA_GIT_SHA";

    /**
     * Opens the diff viewer.
     *
     * @param activity calling activity
     * @param repoRoot the repository working folder (any path inside it works)
     * @param path     repository-relative path to restrict the diff to one file, or {@code null} for the whole tree
     * @param sha      commit id to show against its parent, or {@code null} for the working tree against HEAD
     */
    public static void launch(final Activity activity, final File repoRoot, @Nullable final String path, @Nullable final String sha) {
        if (activity == null || repoRoot == null) {
            return;
        }

        final Intent intent = new Intent(activity, DiffViewerActivity.class);
        intent.putExtra(EXTRA_REPO_ROOT, repoRoot);
        if (path != null) {
            intent.putExtra(EXTRA_PATH, path);
        }
        if (sha != null) {
            intent.putExtra(EXTRA_SHA, sha);
        }
        activity.startActivity(intent);
    }

    private static final String LOG_TAG = "DiffViewer";

    private final GitService _git = new JGitService();

    private File _repoRoot;
    private String _path;
    private String _sha;

    private TextView _text;
    private TextView _message;
    private ProgressBar _progress;

    /** The unedited diff text, for copy and share; {@code null} until it is loaded. */
    private String _unified;

    private GitCancelToken _cancelToken;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _repoRoot = (File) getIntent().getSerializableExtra(EXTRA_REPO_ROOT);
        _path = getIntent().getStringExtra(EXTRA_PATH);
        _sha = getIntent().getStringExtra(EXTRA_SHA);
        if (_repoRoot == null) {
            finish();
            return;
        }

        setContentView(R.layout.git__diff_viewer__activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title());
            getSupportActionBar().setSubtitle(subtitle());
        }

        _text = findViewById(R.id.git_diff_viewer__text);
        _message = findViewById(R.id.git_diff_viewer__message);
        _progress = findViewById(R.id.git_diff_viewer__progress);

        load();
    }

    private String title() {
        if (_path == null) {
            return _repoRoot.getName();
        }
        final int slash = _path.lastIndexOf('/');
        return slash < 0 ? _path : _path.substring(slash + 1);
    }

    private String subtitle() {
        if (_sha == null) {
            return getString(R.string.git_working_tree);
        }
        return _sha.length() > GitCommitInfo.SHORT_SHA_LENGTH
                ? _sha.substring(0, GitCommitInfo.SHORT_SHA_LENGTH)
                : _sha;
    }

    private void load() {
        final File repoRoot = _repoRoot;
        final String path = _path;
        final String sha = _sha;

        _cancelToken = GitTaskRunner.get().submit(
                repoRoot.getAbsolutePath(),
                token -> sha == null
                        ? _git.diffWorkingTree(repoRoot, path, GitUiProgress.cancelOnly(token))
                        // diffForCommit has no path argument; the wanted file is cut out below.
                        : _git.diffForCommit(repoRoot, sha, GitUiProgress.cancelOnly(token)),
                () -> !isFinishing() && !isDestroyed(),
                result -> {
                    _progress.setVisibility(View.GONE);
                    if (result.isCancelled()) {
                        return;
                    }
                    if (result.isError()) {
                        Log.w(LOG_TAG, "Loading the diff failed", result.getError());
                        showMessage(getString(R.string.error_could_not_open_file));
                        return;
                    }

                    final GitResult<GitDiff> diff = result.getValue();
                    if (!diff.isOk()) {
                        showMessage(diff.getMessage());
                        return;
                    }

                    String unified = diff.getValue().getUnified();
                    if (sha != null && path != null) {
                        unified = DiffTextFormatter.sliceFile(unified, path);
                    }
                    render(unified);
                });
    }

    private void render(final String unified) {
        _unified = unified;
        if (unified == null || unified.trim().isEmpty()) {
            showMessage(getString(R.string.git_no_changes));
            return;
        }

        final DiffTextFormatter.Formatted formatted = DiffTextFormatter.format(
                unified, DiffTextFormatter.DEFAULT_MAX_LINES, getString(R.string.git_diff_truncated));

        final @ColorInt int addBg = ContextCompat.getColor(this, R.color.git_diff_add_bg);
        final @ColorInt int delBg = ContextCompat.getColor(this, R.color.git_diff_del_bg);
        final @ColorInt int hunkFg = ContextCompat.getColor(this, R.color.git_diff_hunk);
        final @ColorInt int mutedFg = ContextCompat.getColor(this, R.color.secondary_text);
        final @ColorInt int accentFg = ContextCompat.getColor(this, R.color.colorAccent);

        final SpannableString spannable = new SpannableString(formatted.getText());
        final List<DiffTextFormatter.Line> lines = formatted.getLines();

        // One span per RUN of same-kind lines, not per line: a diff is mostly long blocks of
        // additions and deletions, and TextView pays for every span on every draw. A 500-line block
        // of additions costs one span here instead of 500, and looks identical - LineBackgroundSpan
        // is invoked for each line a span covers.
        for (int start = 0; start < lines.size(); ) {
            final DiffTextFormatter.LineKind kind = lines.get(start).getKind();
            int end = start + 1;
            while (end < lines.size() && lines.get(end).getKind() == kind) {
                end++;
            }

            final int from = lines.get(start).getStart();
            final int to = lines.get(end - 1).getEnd();
            switch (kind) {
                case ADDED:
                    setSpan(spannable, new DiffLineBackgroundSpan(addBg), from, to);
                    break;
                case REMOVED:
                    setSpan(spannable, new DiffLineBackgroundSpan(delBg), from, to);
                    break;
                case HUNK_HEADER:
                    setSpan(spannable, new ForegroundColorSpan(hunkFg), from, to);
                    break;
                case FILE_HEADER:
                    setSpan(spannable, new StyleSpan(Typeface.BOLD), from, to);
                    break;
                case META:
                    setSpan(spannable, new ForegroundColorSpan(mutedFg), from, to);
                    break;
                case TRUNCATION_FOOTER:
                    setSpan(spannable, new ForegroundColorSpan(accentFg), from, to);
                    setSpan(spannable, new StyleSpan(Typeface.BOLD), from, to);
                    break;
                case CONTEXT:
                default:
                    break;
            }
            start = end;
        }

        _message.setVisibility(View.GONE);
        _text.setVisibility(View.VISIBLE);
        _text.setText(spannable);
    }

    private static void setSpan(final SpannableString spannable, final Object span, final int from, final int to) {
        if (to > from) {
            spannable.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void showMessage(final String message) {
        _text.setVisibility(View.GONE);
        _message.setText(message);
        _message.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.git__diff_viewer__menu, menu);
        _cu.tintMenuItems(menu, true, _cu.rcolor(this, R.color.dark__primary_text));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (_unified == null || _unified.isEmpty()) {
            return false;
        }
        if (id == R.id.git_diff_viewer__copy) {
            _cu.setClipboard(this, _unified);
            Toast.makeText(this, R.string.git_copied_to_clipboard, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.git_diff_viewer__share) {
            try {
                // A big diff can exceed the binder transaction limit; that throws at startActivity.
                _cu.shareText(this, _unified, null);
            } catch (final Exception e) {
                Toast.makeText(this, R.string.git_share_failed, Toast.LENGTH_LONG).show();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        // Only this screen's own load, never whatever else is queued for the repository.
        if (_cancelToken != null) {
            _cancelToken.cancel();
        }
        super.onDestroy();
    }

    /**
     * Paints the whole line width, not just the characters, so added and removed lines read as
     * bands the way a desktop diff viewer shows them.
     */
    private static final class DiffLineBackgroundSpan implements LineBackgroundSpan {
        private final @ColorInt int _color;

        private DiffLineBackgroundSpan(final @ColorInt int color) {
            _color = color;
        }

        @Override
        public void drawBackground(final Canvas canvas, final Paint paint, final int left, final int right,
                                   final int top, final int baseline, final int bottom,
                                   final CharSequence text, final int start, final int end, final int lineNumber) {
            final int original = paint.getColor();
            paint.setColor(_color);
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setColor(original);
        }
    }
}
