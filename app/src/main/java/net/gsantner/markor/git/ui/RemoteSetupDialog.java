/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import net.gsantner.markor.R;
import net.gsantner.markor.git.GitCredentialStore;
import net.gsantner.markor.git.GitCredentialsSource;
import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitRepoConfig;
import net.gsantner.markor.git.GitRepoRegistry;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitService;
import net.gsantner.markor.git.GitSettingsStore;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.JGitService;

import java.io.File;
import java.util.List;

/**
 * Configures the remote of one registered repository (roadmap task 5.1): URL, username and personal
 * access token, with a <i>Test connection</i> button that runs {@code ls-remote}.
 * <p>
 * Only HTTPS is offered (decision D4); {@link GitRemoteUrlValidator} explains every refusal. Saving
 * writes the URL both into the repository's {@code .git/config} (remote {@code origin}) and onto the
 * {@link GitRepoConfig} in the {@link GitRepoRegistry}, and puts username and token into the
 * {@link GitCredentialStore}, which is Keystore-backed from API 23 on. Below that the dialog says in
 * one line that the token only lives until the app closes.
 * <p>
 * The token is read out of the field as a {@code char[]}, handed to exactly one operation and wiped
 * afterwards; it is never logged, never shown in a toast and never written into the URL.
 * <p>
 * Usage:
 * <pre>
 * RemoteSetupDialog.newInstance(repo.getPath())
 *         .setListener((repoPath, url) -&gt; refreshHeader())
 *         .show(getParentFragmentManager(), RemoteSetupDialog.FRAGMENT_TAG);
 * </pre>
 */
public class RemoteSetupDialog extends DialogFragment {

    public static final String FRAGMENT_TAG = RemoteSetupDialog.class.getName();
    private static final String EXTRA_REPO_PATH = "EXTRA_REPO_PATH";

    /** Told when the remote was stored; called on the main thread, just before the dialog closes. */
    public interface Listener {
        /**
         * @param repoPath  path of the repository whose remote changed
         * @param remoteUrl the stored URL, without credentials
         */
        void onRemoteSaved(String repoPath, String remoteUrl);
    }

    private Listener _listener;
    private GitService _service;
    private GitRepoRegistry _registry;

    private EditText _urlEdit;
    private EditText _usernameEdit;
    private EditText _tokenEdit;
    private TextView _statusText;
    private ProgressBar _busy;
    private boolean _testing;

    /**
     * @param repoPath path of a repository registered in the {@link GitRepoRegistry}
     */
    public static RemoteSetupDialog newInstance(final String repoPath) {
        final RemoteSetupDialog dialog = new RemoteSetupDialog();
        final Bundle args = new Bundle();
        args.putString(EXTRA_REPO_PATH, repoPath);
        dialog.setArguments(args);
        return dialog;
    }

    /** @return this, so the call can be chained onto {@link #newInstance(String)} */
    public RemoteSetupDialog setListener(final Listener listener) {
        _listener = listener;
        return this;
    }

    private String getRepoPath() {
        final Bundle args = getArguments();
        return args == null ? "" : args.getString(EXTRA_REPO_PATH, "");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final Context context = requireContext();
        _service = new JGitService();
        _registry = GitSettingsStore.newRegistry();

        final LayoutInflater inflater = LayoutInflater.from(context);
        final View root = inflater.inflate(R.layout.git_remote_setup_dialog, null);
        _urlEdit = root.findViewById(R.id.git_remote_setup_dialog__url);
        _usernameEdit = root.findViewById(R.id.git_remote_setup_dialog__username);
        _tokenEdit = root.findViewById(R.id.git_remote_setup_dialog__token);
        _statusText = root.findViewById(R.id.git_remote_setup_dialog__status);
        _busy = root.findViewById(R.id.git_remote_setup_dialog__busy);

        final GitRepoConfig repo = _registry.get(getRepoPath());
        final TextView repoText = root.findViewById(R.id.git_remote_setup_dialog__repo);
        repoText.setText(repo == null ? getRepoPath() : repo.getDisplayName());

        if (savedInstanceState == null && repo != null && repo.getRemoteUrl() != null) {
            _urlEdit.setText(repo.getRemoteUrl());
            final String username = GitCredentialStore.get(context).getUsername(repo.getRemoteUrl());
            if (username != null) {
                _usernameEdit.setText(username);
            }
        }
        if (!GitCredentialStore.get(context).isPersistent()) {
            root.findViewById(R.id.git_remote_setup_dialog__not_persisted).setVisibility(View.VISIBLE);
        }

        final AlertDialog dialog = new AlertDialog.Builder(context, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_remote_setup__title)
                .setView(root)
                .setPositiveButton(R.string.save, null)
                .setNeutralButton(R.string.git_remote__test_connection, null)
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .create();

        // Wired after show(), otherwise every click dismisses the dialog even when the input is bad.
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> save());
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> testConnection());
        });
        return dialog;
    }

    // ---------------------------------------------------------------- test connection

    private void testConnection() {
        final Context context = getContext();
        if (context == null || _testing) {
            return;
        }
        final GitRemoteUrlValidator.Result url = validatedUrl(context);
        if (url == null) {
            return;
        }

        final char[] typed = GitUiText.readSecret(_tokenEdit);
        final GitFixedCredentials fixed = typed.length == 0
                ? null
                : new GitFixedCredentials(url.getHost(), GitUiText.trimmedText(_usernameEdit), typed);
        GitUiText.wipe(typed);
        final GitCredentialsSource credentials = fixed != null ? fixed : GitCredentialStore.get(context).asSource();

        setTesting(true, context.getString(R.string.git_remote__testing));
        final String remoteUrl = url.getUrl();
        GitTaskRunner.get().submit(getRepoPath(),
                token -> {
                    try {
                        return _service.lsRemote(remoteUrl, credentials, new GitUiProgress(token, null));
                    } finally {
                        // In the task, not in the callback: the callback is dropped when the dialog
                        // is gone (a rotation), and the token must be wiped either way.
                        if (fixed != null) {
                            fixed.wipe();
                        }
                    }
                },
                () -> getDialog() != null,
                result -> {
                    final Context ctx = getContext();
                    if (ctx == null) {
                        return;
                    }
                    setTesting(false, null);
                    if (!result.isSuccess()) {
                        showStatus(ctx.getString(R.string.git_error__generic));
                        return;
                    }
                    final GitResult<List<String>> branches = result.getValue();
                    if (!branches.isOk()) {
                        showStatus(GitUiText.messageFor(ctx, branches));
                    } else if (branches.getValue().isEmpty()) {
                        showStatus(ctx.getString(R.string.git_remote__connection_ok_empty));
                    } else {
                        final int count = branches.getValue().size();
                        showStatus(ctx.getResources().getQuantityString(R.plurals.git_remote__connection_ok, count, count));
                    }
                });
    }

    // ---------------------------------------------------------------- save

    private void save() {
        final Context context = getContext();
        if (context == null || _testing) {
            return;
        }
        final GitRemoteUrlValidator.Result url = validatedUrl(context);
        if (url == null) {
            return;
        }

        final char[] token = GitUiText.readSecret(_tokenEdit);
        final String username = GitUiText.trimmedText(_usernameEdit);
        if (token.length > 0 && username.isEmpty()) {
            GitUiText.wipe(token);
            _usernameEdit.setError(context.getString(R.string.git_error__username_required));
            _usernameEdit.requestFocus();
            return;
        }

        final GitRepoConfig repo = _registry.get(getRepoPath());
        if (repo == null) {
            GitUiText.wipe(token);
            showStatus(context.getString(R.string.git_error__repo_unknown));
            return;
        }

        setTesting(true, null);
        final File repoDir = repo.getFile();
        final String remoteUrl = url.getUrl();
        final Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final GitRepoRegistry registry = _registry;
        // Storing happens in the task, right behind the git call, so that a rotation cannot lose the
        // write half-way through — and so the token is wiped even when the callback is dropped.
        GitTaskRunner.get().submit(repo.getPath(),
                t -> {
                    try {
                        final GitResult<Void> written = _service.setRemoteUrl(repoDir, remoteUrl, GitProgress.NONE);
                        if (written.isOk()) {
                            registry.update(repo.setRemoteUrl(remoteUrl));
                            GitUiText.saveCredentials(appContext, remoteUrl, username, token);
                        }
                        return written;
                    } finally {
                        GitUiText.wipe(token);
                    }
                },
                () -> getDialog() != null,
                result -> {
                    final Context ctx = getContext();
                    if (ctx == null) {
                        return;
                    }
                    setTesting(false, null);
                    if (!result.isSuccess() || !result.getValue().isOk()) {
                        showStatus(result.isSuccess()
                                ? GitUiText.messageFor(ctx, result.getValue())
                                : ctx.getString(R.string.git_error__generic));
                        return;
                    }
                    Toast.makeText(ctx, R.string.git_remote__saved, Toast.LENGTH_SHORT).show();
                    if (_listener != null) {
                        _listener.onRemoteSaved(repo.getPath(), remoteUrl);
                    }
                    dismissAllowingStateLoss();
                });
    }

    // ---------------------------------------------------------------- shared bits

    /** @return the validated URL, or {@code null} after putting the reason on the URL field */
    private GitRemoteUrlValidator.Result validatedUrl(final Context context) {
        final GitRemoteUrlValidator.Result url = GitRemoteUrlValidator.validate(GitUiText.trimmedText(_urlEdit));
        if (!url.isValid()) {
            _urlEdit.setError(GitUiText.messageFor(context, url.getProblem()));
            _urlEdit.requestFocus();
            return null;
        }
        _urlEdit.setError(null);
        return url;
    }

    private void setTesting(final boolean testing, final String status) {
        _testing = testing;
        _busy.setVisibility(testing ? View.VISIBLE : View.GONE);
        showStatus(status);
        final Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog) {
            final AlertDialog alert = (AlertDialog) dialog;
            alert.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(!testing);
            alert.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!testing);
        }
    }

    private void showStatus(final String status) {
        if (_statusText == null) {
            return;
        }
        _statusText.setText(status == null ? "" : status);
        _statusText.setVisibility(status == null || status.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
