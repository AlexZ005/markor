/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.wrapper.GsCallback;

import java.io.File;

/**
 * Warns, once per repository, that a folder-sync tool (Syncthing, Nextcloud, Dropbox, …) which also
 * syncs the {@code .git} folder will corrupt the repository: two devices write loose objects, packs
 * and refs independently and the sync tool merges them file by file, which git cannot recover from.
 * <p>
 * Roadmap task 7.3, and the mitigation for the "users also run Syncthing on the same folder" risk.
 * The Git fragment calls {@link #maybeWarn(Activity, File, GsCallback.a0)} right after a repository
 * is registered — added, initialized or cloned. "Shown" is remembered per repository path in
 * {@link AppSettings}, before the dialog is answered, so dismissing it with the back button does not
 * bring it back on the next repository switch.
 */
public final class GitSyncWarningDialog {

    /** The Syncthing how-to that ships with Markor's documentation. */
    public static final String SYNCTHING_DOC_URL =
            "https://github.com/gsantner/markor/blob/master/doc/2020-04-04-syncthing-file-sync-setup-how-to-use-with-markor.md";

    private GitSyncWarningDialog() {
    }

    /**
     * Shows the warning for {@code repoRoot} unless it was shown for that path before.
     *
     * @param activity host for the dialog and the settings
     * @param repoRoot working-tree root of the repository that was just added
     * @param onDone   run once the dialog is gone, or straight away when there is nothing to show;
     *                 may be {@code null}. <i>Read the documentation</i> leaves the app, and
     *                 {@code onDone} runs then too.
     */
    public static void maybeWarn(final Activity activity, final File repoRoot, final GsCallback.a0 onDone) {
        final String repoKey = repoRoot == null ? "" : repoRoot.getAbsolutePath();
        if (activity == null || repoKey.isEmpty()) {
            done(onDone);
            return;
        }
        final AppSettings settings = AppSettings.get(activity);
        if (settings.isGitSyncWarningShown(repoKey)) {
            done(onDone);
            return;
        }
        // Remembered before the answer, not after, for the same reason as the .gitignore suggestion.
        settings.setGitSyncWarningShown(repoKey);

        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_sync_warning__title)
                .setMessage(activity.getString(R.string.git_sync_warning__message, repoRoot.getName()))
                .setNeutralButton(R.string.git_sync_warning__read_doc, (d, which) -> {
                    d.dismiss();
                    GsContextUtils.instance.openWebpageInExternalBrowser(activity, SYNCTHING_DOC_URL);
                    done(onDone);
                })
                .setPositiveButton(R.string.git_sync_warning__understood, (d, which) -> {
                    d.dismiss();
                    done(onDone);
                })
                .setOnCancelListener(d -> done(onDone))
                .show();
    }

    private static void done(final GsCallback.a0 onDone) {
        if (onDone != null) {
            onDone.callback();
        }
    }
}
