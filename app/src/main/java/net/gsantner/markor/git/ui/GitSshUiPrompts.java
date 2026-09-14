/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks the user the two SSH questions with a real dialog, from the git worker thread (roadmap task
 * 8.1c).
 * <p>
 * The call blocks: it posts the dialog to the main thread and waits on a latch until a button is
 * pressed. That is what the SSH stack needs — a connection cannot be paused while a fragment is
 * asked to show something later — and it is safe because the git operations already run on their own
 * per-repository thread ({@code GitTaskRunner}), never on the main one. Two things keep it from
 * becoming a hang:
 * <ul>
 * <li>a call from the main thread answers "no" instead of deadlocking against itself;</li>
 * <li>the wait gives up after {@link #TIMEOUT_SECONDS}, so an operation started behind an activity
 * that has since gone away ends in an ordinary failure.</li>
 * </ul>
 * The activity is fetched through a supplier rather than held, so this object can outlive one
 * rotation without leaking the one it was made with. If none is there when the question comes up,
 * the answer is no: silently trusting a host nobody was shown is exactly what must not happen.
 */
public final class GitSshUiPrompts implements GitSshPrompts {

    /** How long a question waits for an answer before the operation is given up on. */
    public static final int TIMEOUT_SECONDS = 120;

    /** Where to find the activity to show a dialog in; called on the main thread. */
    public interface ActivitySource {
        /** @return the activity in the foreground, or {@code null} when there is none */
        Activity getActivity();
    }

    private final ActivitySource _activitySource;
    private final Handler _main = new Handler(Looper.getMainLooper());

    public GitSshUiPrompts(final ActivitySource activitySource) {
        _activitySource = activitySource;
    }

    // ---------------------------------------------------------------- host key

    @Override
    public boolean confirmHostKey(final String host, final String keyType, final String fingerprint) {
        final AtomicBoolean trusted = new AtomicBoolean(false);
        ask(activity -> {
            final View root = LayoutInflater.from(activity).inflate(R.layout.git_host_key_dialog, null);
            ((TextView) root.findViewById(R.id.git_host_key_dialog__host)).setText(host);
            ((TextView) root.findViewById(R.id.git_host_key_dialog__fingerprint)).setText(fingerprint);
            ((TextView) root.findViewById(R.id.git_host_key_dialog__key_type)).setText(keyType);
            return new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                    .setTitle(R.string.git_host_key__title)
                    .setView(root)
                    .setPositiveButton(R.string.git_host_key__trust, (d, w) -> trusted.set(true))
                    .setNegativeButton(R.string.cancel, null);
        });
        return trusted.get();
    }

    // ---------------------------------------------------------------- passphrase

    @Override
    public char[] askPassphrase(final String keyName, final String fingerprint, final boolean wasWrong) {
        final AtomicReference<char[]> typed = new AtomicReference<>();
        ask(activity -> {
            final View root = LayoutInflater.from(activity).inflate(R.layout.git_passphrase_dialog, null);
            ((TextView) root.findViewById(R.id.git_passphrase_dialog__key)).setText(keyName);
            ((TextView) root.findViewById(R.id.git_passphrase_dialog__fingerprint)).setText(fingerprint);
            root.findViewById(R.id.git_passphrase_dialog__wrong)
                    .setVisibility(wasWrong ? View.VISIBLE : View.GONE);
            final EditText field = root.findViewById(R.id.git_passphrase_dialog__passphrase);
            final AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                    .setTitle(R.string.git_ssh__passphrase_title)
                    .setView(root)
                    .setPositiveButton(android.R.string.ok, (d, w) -> typed.set(GitUiText.readSecret(field)))
                    .setNegativeButton(R.string.cancel, null);
            // A passphrase field that is not focused means an extra tap on every sync.
            field.requestFocus();
            return builder;
        });
        return typed.get();
    }

    // ---------------------------------------------------------------- the blocking part

    /** Builds the dialog on the main thread; the caller reads its answer out of what it captured. */
    private interface DialogFactory {
        AlertDialog.Builder build(Activity activity);
    }

    /**
     * Shows one dialog and waits for it to be dismissed, however that happens — a button, the back
     * key, or the activity going away with it. The listeners set their answer before the latch is
     * counted down, so there is no race between reading the answer and the wait returning.
     */
    private void ask(final DialogFactory factory) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Would wait for a dialog that can never be shown, because this thread is the one that
            // would have to show it. Nothing in the app does this; answering no is the safe way out.
            return;
        }
        final CountDownLatch answered = new CountDownLatch(1);
        _main.post(() -> {
            final Activity activity = _activitySource == null ? null : _activitySource.getActivity();
            if (activity == null || activity.isFinishing()) {
                answered.countDown();
                return;
            }
            final AlertDialog dialog;
            try {
                dialog = factory.build(activity).create();
                dialog.setOnDismissListener(d -> answered.countDown());
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                dialog.show();
            } catch (RuntimeException e) {
                // A window that cannot be added (the activity went away between the two checks)
                // must not leave the operation waiting.
                answered.countDown();
            }
        });
        try {
            // On a timeout the answer stays "no" and the operation fails. The dialog is left where
            // it is rather than dismissed from here: the activity takes it down with itself, and
            // closing someone's half-typed passphrase dialog from a background thread is worse than
            // letting them find out the sync gave up.
            answered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
