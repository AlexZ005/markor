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
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.git.ssh.GitKnownHosts;
import net.gsantner.opoc.frontend.GsSearchOrCustomTextDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings &rsaquo; Git &rsaquo; Known SSH servers: what the app has been told to trust, and the one
 * way to undo it (roadmap task 8.1c).
 * <p>
 * This is the way back from "the server's host key changed". That failure never offers to trust the
 * new key where it happens, because someone who is being intercepted would press whatever button
 * ends the error — so forgetting an entry is a separate act, in a screen nobody reaches by accident,
 * with a confirmation that says what will happen next. The next connection then asks for a
 * fingerprint as if the server were new.
 */
public final class GitKnownHostsDialog {

    /** Told after something was forgotten, so a settings summary can be refreshed. */
    public interface Listener {
        void onKnownHostsChanged();
    }

    private GitKnownHostsDialog() {
    }

    public static void show(final Activity activity, final Listener listener) {
        if (activity == null) {
            return;
        }
        final File file = GitKnownHosts.fileIn(activity.getFilesDir());
        final List<GitKnownHosts.Entry> entries = GitKnownHosts.list(file);
        if (entries.isEmpty()) {
            Toast.makeText(activity, R.string.git_settings__known_hosts_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> rows = new ArrayList<>();
        for (final GitKnownHosts.Entry entry : entries) {
            rows.add(entry.describe());
        }

        final GsSearchOrCustomTextDialog.DialogOptions dopt = MarkorDialogFactory.baseConf(activity);
        dopt.data = rows;
        dopt.titleText = R.string.git_settings__known_hosts;
        dopt.messageText = activity.getString(R.string.git_settings__known_hosts_summary);
        dopt.isSearchEnabled = entries.size() > 8;
        dopt.isSoftInputVisible = false;
        dopt.okButtonText = 0;
        dopt.positionCallback = indices -> {
            if (!indices.isEmpty()) {
                confirmForget(activity, file, entries.get(indices.get(0)).getHost(), listener);
            }
        };
        GsSearchOrCustomTextDialog.showMultiChoiceDialogWithSearchFilterUI(activity, dopt);
    }

    private static void confirmForget(final Activity activity, final File file, final String host,
                                      final Listener listener) {
        new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_known_hosts__forget_title)
                .setMessage(activity.getString(R.string.git_known_hosts__forget_message, host))
                .setPositiveButton(R.string.git_known_hosts__forget, (d, w) -> {
                    if (GitKnownHosts.forget(file, host)) {
                        Toast.makeText(activity, activity.getString(R.string.git_known_hosts__forgotten, host),
                                Toast.LENGTH_SHORT).show();
                        if (listener != null) {
                            listener.onKnownHostsChanged();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
