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

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.git.ssh.GitSshKey;
import net.gsantner.markor.git.ssh.GitSshKeyStore;
import net.gsantner.markor.git.ssh.GitSshKeyStores;
import net.gsantner.opoc.frontend.GsSearchOrCustomTextDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * "Which SSH key does this repository use?" — shared by the remote-setup and clone dialogs, so the
 * two ask it the same way (roadmap tasks 8.1b and 8.1c).
 * <p>
 * Only the choice lives here. The keys themselves — generate, import, copy the public half, set the
 * default, delete — are {@link SshKeyManagerActivity}, in Settings &rsaquo; Git, which the last row
 * opens: a clone dialog is the place where someone first discovers they need a key, and sending them
 * out of a half-filled form with no way back would be the wrong answer.
 * <p>
 * A key this build cannot sign with is not offered at all. An imported ed25519 key parses and shows
 * its fingerprint but cannot authenticate without Bouncy Castle (ADR 0002), and the key manager
 * already says so where the key is listed; repeating it as a refusal here would only be a dead end.
 */
final class GitSshKeyChooser {

    /** Told which key was picked. */
    interface Listener {
        /**
         * @param keyId the chosen key, or {@code null} for "the app default", which is what a
         *              {@code GitRepoConfig} stores when it does not pin one
         */
        void onKeyChosen(String keyId);
    }

    private GitSshKeyChooser() {
    }

    /**
     * @param currentKeyId what is selected now, or {@code null} for the app default
     */
    static void show(final Activity activity, final String currentKeyId, final Listener listener) {
        final Context context = activity;
        final GitSshKeyStore store = GitSshKeyStores.get(context);
        final List<GitSshKey> keys = new ArrayList<>();
        if (store != null) {
            for (final GitSshKey key : store.list()) {
                if (key.canAuthenticate()) {
                    keys.add(key);
                }
            }
        }
        final GitSshKey appDefault = store == null ? null : store.getDefault();

        final List<String> rows = new ArrayList<>();
        rows.add(appDefault == null
                ? context.getString(R.string.git_ssh_keys__repo_key_default_none)
                : context.getString(R.string.git_ssh_keys__repo_key_default, appDefault.getName()));
        for (final GitSshKey key : keys) {
            rows.add(key.getName() + "\n" + key.describe()
                    + (key.getId().equals(currentKeyId) ? " ✓" : ""));
        }
        rows.add(context.getString(R.string.git_ssh_keys__title) + "…");

        final GsSearchOrCustomTextDialog.DialogOptions dopt = MarkorDialogFactory.baseConf(context);
        dopt.data = rows;
        dopt.titleText = R.string.git_ssh_keys__repo_key;
        dopt.messageText = keys.isEmpty() ? context.getString(R.string.git_ssh__key_none) : "";
        dopt.isSearchEnabled = rows.size() > 8;
        dopt.isSoftInputVisible = false;
        dopt.okButtonText = 0;
        dopt.positionCallback = indices -> {
            if (indices.isEmpty()) {
                return;
            }
            final int index = indices.get(0);
            if (index == 0) {
                listener.onKeyChosen(null);
            } else if (index <= keys.size()) {
                listener.onKeyChosen(keys.get(index - 1).getId());
            } else {
                SshKeyManagerActivity.launch(activity);
            }
        };
        GsSearchOrCustomTextDialog.showMultiChoiceDialogWithSearchFilterUI(activity, dopt);
    }
}
