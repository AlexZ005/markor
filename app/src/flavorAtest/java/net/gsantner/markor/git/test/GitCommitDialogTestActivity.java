/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.test;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import net.gsantner.markor.git.GitIgnoreHelper;
import net.gsantner.markor.git.ui.CommitDialog;
import net.gsantner.markor.git.ui.GitIgnoreSuggestDialog;

import org.eclipse.jgit.api.Git;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Test-flavour only, not reachable from the app: a throw-away screen that builds a small repository in
 * the app's private storage and opens {@link CommitDialog} on it, so the dialog can be exercised on a
 * device before the Git fragment exists.
 * <pre>
 * adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.test.GitCommitDialogTestActivity
 * adb logcat -s GitCommitDialogTest
 * </pre>
 * <i>Reset repository</i> rebuilds the repository from scratch: one committed file, one modified, one
 * new, one deleted and one untracked, which covers every row the checklist can show except a conflict.
 */
public class GitCommitDialogTestActivity extends AppCompatActivity {

    private static final String TAG = "GitCommitDialogTest";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private File _repo;
    private TextView _log;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _repo = new File(getFilesDir(), "git-commit-dialog-test-repo");

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        root.addView(button("Reset repository", v -> resetRepo()));
        root.addView(button("Show commit dialog", v -> showCommitDialog()));
        root.addView(button("Show .gitignore suggestion", v -> GitIgnoreSuggestDialog.maybeSuggest(
                this, _repo, written -> log("gitignore suggestion written=" + written))));
        root.addView(button("Show .gitignore file", v -> showGitIgnore()));

        _log = new TextView(this);
        _log.setTextIsSelectable(true);
        root.addView(_log);
        setContentView(root);

        // The host re-attaches its callback after a rotation, exactly as MainActivity will have to.
        final CommitDialog existing = (CommitDialog) getSupportFragmentManager()
                .findFragmentByTag(CommitDialog.FRAGMENT_TAG);
        if (existing != null) {
            existing.setListener(this::onCommitted);
            log("re-attached listener to the surviving dialog");
        }

        if (!new File(_repo, ".git").isDirectory()) {
            resetRepo();
        } else {
            log("repository: " + _repo);
        }
    }

    private Button button(final String text, final View.OnClickListener onClick) {
        final Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private void showCommitDialog() {
        CommitDialog.newInstance(_repo, this::onCommitted)
                .show(getSupportFragmentManager(), CommitDialog.FRAGMENT_TAG);
    }

    private void onCommitted(final String sha, final boolean pushRequested) {
        log("onCommitted sha=" + sha + " pushRequested=" + pushRequested);
        Toast.makeText(this, "Committed " + sha.substring(0, 7)
                + (pushRequested ? " (push requested)" : ""), Toast.LENGTH_LONG).show();
    }

    private void showGitIgnore() {
        final File file = GitIgnoreHelper.getGitIgnoreFile(_repo);
        log(".gitignore exists=" + file.isFile() + " ignored="
                + GitIgnoreHelper.isIgnored(_repo, GitIgnoreHelper.DEFAULT_ENTRY));
    }

    private void resetRepo() {
        new Thread(() -> {
            String message;
            try {
                deleteRecursively(_repo);
                if (!_repo.mkdirs()) {
                    throw new IllegalStateException("Could not create " + _repo);
                }
                try (Git git = Git.init().setDirectory(_repo).setInitialBranch("main").call()) {
                    write("notes.md", "# Notes\nfirst line\n");
                    write("scratch.md", "throw away\n");
                    write("journal/old.md", "yesterday\n");
                    git.add().addFilepattern(".").call();
                    git.commit().setMessage("Initial commit")
                            .setAuthor("Test Author", "test@example.com").call();

                    write("notes.md", "# Notes\nfirst line\nsecond line\n");   // M
                    write("journal/today.md", "new entry\n");                   // ?
                    write(".app/snippets/hello.md", "snippet\n");               // ? (Markor's own folder)
                    if (!new File(_repo, "scratch.md").delete()) {              // D
                        throw new IllegalStateException("Could not delete scratch.md");
                    }
                    write("staged.md", "staged and new\n");
                    git.add().addFilepattern("staged.md").call();               // A
                }
                message = "repository rebuilt at " + _repo;
            } catch (Exception e) {
                Log.e(TAG, "reset failed", e);
                message = "reset failed: " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            final String result = message;
            runOnUiThread(() -> log(result));
        }).start();
    }

    private void write(final String relative, final String content) throws Exception {
        final File file = new File(_repo, relative);
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(UTF8));
        }
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void log(final String line) {
        Log.i(TAG, line);
        _log.append(line + "\n");
    }
}
