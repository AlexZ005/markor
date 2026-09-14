/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;
import net.gsantner.markor.git.GitIgnoreHelper;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;

/**
 * Offers to keep Markor's own {@code .app/} folder — snippets and templates — out of the user's commits,
 * once per repository.
 * <p>
 * The Git fragment calls {@link #maybeSuggest(Activity, File, GsCallback.a1)} after adding or opening a
 * repository; it does nothing at all when the folder is outside the repository, is already in
 * {@code .gitignore}, or this repository was asked about before. "Asked" is remembered whichever way the
 * user answered, so a declined suggestion does not come back.
 */
public final class GitIgnoreSuggestDialog {

    private GitIgnoreSuggestDialog() {
    }

    /**
     * The entry point for the Git fragment: asks about this repository at most once.
     *
     * @param activity host for the dialog and the settings
     * @param repoRoot working-tree root of the repository
     * @param onDone   called with {@code true} when the entry was written, {@code false} in every other
     *                 case (declined, already ignored, nothing to suggest, write failed);
     *                 may be {@code null}
     */
    public static void maybeSuggest(final Activity activity, final File repoRoot, final GsCallback.a1<Boolean> onDone) {
        final AppSettings settings = AppSettings.get(activity);
        final String repoKey = repoRoot == null ? "" : repoRoot.getAbsolutePath();
        final File appFolder = appFolderOf(settings);

        if (repoKey.isEmpty() || settings.isGitIgnoreSuggested(repoKey)
                || !GitIgnoreHelper.shouldSuggest(repoRoot, appFolder)) {
            done(onDone, false);
            return;
        }

        final String entry = GitIgnoreHelper.entryFor(repoRoot, appFolder);
        // Remembered before the answer, not after: a dialog the user dismisses with the back button
        // must not come back on the next repository switch either.
        settings.setGitIgnoreSuggested(repoKey);

        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_ignore_suggest_title)
                .setMessage(activity.getString(R.string.git_ignore_suggest_message, entry))
                .setNegativeButton(R.string.git_ignore_suggest_skip, (d, which) -> {
                    d.dismiss();
                    done(onDone, false);
                })
                .setPositiveButton(R.string.git_ignore_suggest_add, (d, which) -> {
                    d.dismiss();
                    done(onDone, append(activity, repoRoot, entry));
                })
                .setOnCancelListener(d -> done(onDone, false))
                .show();
    }

    /**
     * @return Markor's own data folder, the parent of the snippet directory. Derived from the snippet
     * setting rather than hard-coded so a user who moved it is not offered a folder that does not exist.
     */
    public static File appFolderOf(final AppSettings settings) {
        final File snippets = settings.getSnippetsDirectory();
        final File parent = snippets.getParentFile();
        return parent != null && GitIgnoreHelper.APP_FOLDER.equals(parent.getName())
                ? parent : new File(settings.getNotebookDirectory(), GitIgnoreHelper.APP_FOLDER);
    }

    private static boolean append(final Activity activity, final File repoRoot, final String entry) {
        try {
            final boolean written = GitIgnoreHelper.append(repoRoot, entry);
            if (written) {
                Toast.makeText(activity, activity.getString(R.string.git_ignore_suggest_added, entry),
                        Toast.LENGTH_SHORT).show();
            }
            return written;
        } catch (Exception e) {
            Toast.makeText(activity, R.string.git_ignore_suggest_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static void done(final GsCallback.a1<Boolean> onDone, final boolean written) {
        if (onDone != null) {
            onDone.callback(written);
        }
    }
}
