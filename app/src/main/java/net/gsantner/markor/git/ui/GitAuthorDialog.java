/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;
import net.gsantner.markor.git.GitAuthor;
import net.gsantner.markor.git.GitAuthorConfig;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.wrapper.GsCallback;
import net.gsantner.opoc.wrapper.GsTextWatcherAdapter;

import java.io.File;

/**
 * The "Who are you?" prompt. Commits record an author, and Android has no global git configuration to
 * take one from, so the first commit from the Git tab asks once and remembers the answer in
 * {@link AppSettings}.
 * <p>
 * A plain {@link AlertDialog} rather than a {@code DialogFragment}, like the rest of
 * {@code MarkorDialogFactory}: it is a transient prompt in front of the commit dialog, and losing it to
 * a rotation only means pressing <i>Commit</i> again.
 */
public final class GitAuthorDialog {

    private GitAuthorDialog() {
    }

    /**
     * Makes sure an author identity is known, asking for one only when it has to, and hands it to
     * {@code onAuthor}.
     * <p>
     * The identity is looked for in three places, in order: the app settings, the repository's own
     * {@code .git/config} (a folder a desktop git already uses needs no prompt — it is adopted into the
     * settings), and finally the user. Whatever the source, the result ends up in the settings; writing
     * it into the repository is left to the caller, which does it on its worker thread
     * ({@link GitAuthorConfig#write(File, GitAuthor)}).
     *
     * @param activity host, used for the dialog and the settings
     * @param repoRoot repository the commit is for, {@code null} to only use the settings
     * @param onAuthor called with the identity; not called when the user cancels
     */
    public static void requireAuthor(final Activity activity, final File repoRoot, final GsCallback.a1<GitAuthor> onAuthor) {
        requireAuthor(activity, repoRoot, onAuthor, null);
    }

    /**
     * Same as {@link #requireAuthor(Activity, File, GsCallback.a1)}, and additionally reports when the
     * user declines the prompt, for a caller that has to move on either way (the pull state machine).
     *
     * @param onCancel called when the dialog was shown and dismissed without an identity; may be {@code null}.
     *                 Never called when no dialog was needed.
     */
    public static void requireAuthor(final Activity activity, final File repoRoot, final GsCallback.a1<GitAuthor> onAuthor,
                                     final GsCallback.a0 onCancel) {
        final AppSettings settings = AppSettings.get(activity);
        if (settings.isGitAuthorSet()) {
            onAuthor.callback(new GitAuthor(settings.getGitAuthorName(), settings.getGitAuthorEmail()));
            return;
        }

        final GitAuthor fromRepo = GitAuthorConfig.read(repoRoot);
        if (fromRepo != null) {
            settings.setGitAuthorName(fromRepo.getName());
            settings.setGitAuthorEmail(fromRepo.getEmail());
            onAuthor.callback(fromRepo);
            return;
        }

        show(activity, settings.getGitAuthorName(), settings.getGitAuthorEmail(), author -> {
            settings.setGitAuthorName(author.getName());
            settings.setGitAuthorEmail(author.getEmail());
            onAuthor.callback(author);
        }, onCancel);
    }

    /**
     * Asks for a name and an e-mail address. Nothing is stored here; {@code onAuthor} receives a
     * validated {@link GitAuthor} and decides what to do with it.
     *
     * @param name  value to pre-fill the name field with, may be empty
     * @param email value to pre-fill the e-mail field with, may be empty
     */
    public static void show(final Activity activity, final String name, final String email, final GsCallback.a1<GitAuthor> onAuthor) {
        show(activity, name, email, onAuthor, null);
    }

    /**
     * @param onCancel called once when the dialog goes away without an identity — Cancel, back or a tap
     *                 outside; may be {@code null}
     */
    public static void show(final Activity activity, final String name, final String email, final GsCallback.a1<GitAuthor> onAuthor,
                            final GsCallback.a0 onCancel) {
        final boolean[] answered = {false};
        final View root = LayoutInflater.from(activity).inflate(R.layout.git__author_dialog, null);
        final EditText nameEdit = root.findViewById(R.id.git__author_dialog__name);
        final EditText emailEdit = root.findViewById(R.id.git__author_dialog__email);
        nameEdit.setText(name == null ? "" : name);
        emailEdit.setText(email == null ? "" : email);

        final AlertDialog dialog = new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_author_title)
                .setView(root)
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final GsCallback.a0 updateEnabled = () -> ok.setEnabled(
                    !text(nameEdit).isEmpty() && !text(emailEdit).isEmpty());
            final GsTextWatcherAdapter watcher = new GsTextWatcherAdapter() {
                @Override
                public void afterTextChanged(final Editable s) {
                    updateEnabled.callback();
                }
            };
            nameEdit.addTextChangedListener(watcher);
            emailEdit.addTextChangedListener(watcher);
            updateEnabled.callback();

            ok.setOnClickListener(v -> {
                final String enteredName = text(nameEdit);
                final String enteredEmail = text(emailEdit);
                if (enteredName.isEmpty()) {
                    nameEdit.setError(activity.getString(R.string.git_author_name_required));
                    nameEdit.requestFocus();
                } else if (enteredEmail.isEmpty()) {
                    emailEdit.setError(activity.getString(R.string.git_author_email_required));
                    emailEdit.requestFocus();
                } else {
                    answered[0] = true;
                    dialog.dismiss();
                    onAuthor.callback(new GitAuthor(enteredName, enteredEmail));
                }
            });
        });
        dialog.setOnDismissListener(d -> {
            if (!answered[0] && onCancel != null) {
                onCancel.callback();
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        nameEdit.post(nameEdit::requestFocus);
    }

    private static String text(final EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }
}
