/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import net.gsantner.markor.frontend.MarkorDialogFactory;
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
import net.gsantner.markor.git.ssh.GitSshKey;
import net.gsantner.markor.git.ssh.GitSshKeyStore;
import net.gsantner.markor.git.ssh.GitSshKeyStores;
import net.gsantner.opoc.frontend.GsSearchOrCustomTextDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures the remote of one registered repository (roadmap task 5.1): URL, username and personal
 * access token, with a <i>Test connection</i> button that runs {@code ls-remote}.
 * <p>
 * When the URL is an SSH one, an <b>SSH key</b> row appears instead of asking for a token: the
 * app's default key, or one named for this repository alone, stored as
 * {@link GitRepoConfig#getSshKeyId()} (roadmap task 8.1b). Picking a key takes effect at once,
 * because which key a repository uses is a setting of its own and must not be lost when the URL
 * next to it is refused. The keys themselves are managed in Settings &gt; Git &gt; SSH key.
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

    private GitSshKeyStore _sshKeyStore;
    /** The repository's stored selection: an id, or null for "the app default". */
    private String _sshKeyId;
    /** Whether the SSH key row is currently shown, so the label is only rebuilt when that changes. */
    private boolean _sshRowShown;

    private EditText _urlEdit;
    private EditText _usernameEdit;
    private EditText _tokenEdit;
    private TextView _statusText;
    private TextView _sshKeyText;
    private View _sshKeyBlock;
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
        _sshKeyBlock = root.findViewById(R.id.git_remote_setup_dialog__ssh_key_block);
        _sshKeyText = root.findViewById(R.id.git_remote_setup_dialog__ssh_key);
        _sshKeyStore = GitSshKeyStores.get(context);

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

        // Read from the registry, not from the instance state: the selection is persisted as soon
        // as it is made, so a rotation and a process death both show what is actually stored.
        _sshKeyId = repo == null ? null : repo.getSshKeyId();
        _sshKeyText.setOnClickListener(v -> chooseSshKey());
        _urlEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            }

            @Override
            public void afterTextChanged(final Editable s) {
                updateSshKeyRow(false);
            }
        });
        updateSshKeyRow(true);

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

    // ---------------------------------------------------------------- ssh key

    /**
     * Whether the typed URL is an SSH one. Asked of {@link GitRemoteUrlValidator}, which still
     * refuses SSH on this branch and names it as the reason; task 8.1c replaces that with a
     * transport marker on a valid result, and this method with a read of it.
     */
    private boolean isSshUrl(final String url) {
        return GitRemoteUrlValidator.validate(url).getProblem() == GitRemoteUrlValidator.Problem.SSH_NOT_SUPPORTED;
    }

    /**
     * @param reread {@code true} when the label has to be built again (the row just appeared, or the
     *               selection changed). The label reads the key store, so a keystroke that does not
     *               change the SSH verdict must not trigger it.
     */
    private void updateSshKeyRow(final boolean reread) {
        final Context context = getContext();
        if (context == null || _sshKeyBlock == null) {
            return;
        }
        final boolean ssh = isSshUrl(GitUiText.trimmedText(_urlEdit));
        if (ssh != _sshRowShown) {
            _sshRowShown = ssh;
            _sshKeyBlock.setVisibility(ssh ? View.VISIBLE : View.GONE);
        }
        if (ssh && (reread || _sshKeyText.getText().length() == 0)) {
            _sshKeyText.setText(sshKeyLabel(context));
        }
    }

    private String sshKeyLabel(final Context context) {
        if (_sshKeyId != null) {
            final GitSshKey selected = _sshKeyStore.get(_sshKeyId);
            return selected != null
                    ? selected.getName() + "\n" + selected.getFingerprintSha256()
                    : context.getString(R.string.git_ssh_keys__repo_key_missing);
        }
        final GitSshKey fallback = _sshKeyStore.getDefault();
        return fallback == null
                ? context.getString(R.string.git_ssh_keys__repo_key_default_none)
                : context.getString(R.string.git_ssh_keys__repo_key_default, fallback.getName());
    }

    /** Default key or one of the usable named keys; ed25519 keys are not offered (ADR 0002). */
    private void chooseSshKey() {
        final Activity activity = getActivity();
        final Context context = getContext();
        if (activity == null || context == null) {
            return;
        }
        final List<GitSshKey> keys = new ArrayList<>();
        for (final GitSshKey key : _sshKeyStore.list()) {
            if (key.canAuthenticate()) {
                keys.add(key);
            }
        }
        final List<String> rows = new ArrayList<>();
        final GitSshKey fallback = _sshKeyStore.getDefault();
        rows.add(fallback == null
                ? context.getString(R.string.git_ssh_keys__repo_key_default_none)
                : context.getString(R.string.git_ssh_keys__repo_key_default, fallback.getName()));
        for (final GitSshKey key : keys) {
            rows.add(key.getName() + "\n" + key.describe());
        }

        final GsSearchOrCustomTextDialog.DialogOptions dopt = MarkorDialogFactory.baseConf(context);
        dopt.data = rows;
        dopt.titleText = R.string.git_ssh_keys__repo_key;
        dopt.isSearchEnabled = rows.size() > 8;
        dopt.isSoftInputVisible = false;
        dopt.okButtonText = 0;
        dopt.positionCallback = indices -> {
            if (indices.isEmpty()) {
                return;
            }
            final int index = indices.get(0);
            selectSshKey(index == 0 ? null : keys.get(index - 1).getId());
        };
        GsSearchOrCustomTextDialog.showMultiChoiceDialogWithSearchFilterUI(activity, dopt);
    }

    /** @param keyId a stored key id, or null for "use the app default" */
    private void selectSshKey(final String keyId) {
        final Context context = getContext();
        final GitRepoConfig repo = _registry.get(getRepoPath());
        if (repo == null || !_registry.update(repo.setSshKeyId(keyId))) {
            // Nothing was stored, so nothing may be shown as stored: the repository is not (or no
            // longer) registered.
            if (context != null) {
                showStatus(context.getString(R.string.git_error__repo_unknown));
            }
            return;
        }
        _sshKeyId = keyId;
        updateSshKeyRow(true);
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
        final String sshKeyId = _sshKeyId;
        // Storing happens in the task, right behind the git call, so that a rotation cannot lose the
        // write half-way through — and so the token is wiped even when the callback is dropped.
        GitTaskRunner.get().submit(repo.getPath(),
                t -> {
                    try {
                        final GitResult<Void> written = _service.setRemoteUrl(repoDir, remoteUrl, GitProgress.NONE);
                        if (written.isOk()) {
                            registry.update(repo.setRemoteUrl(remoteUrl).setSshKeyId(sshKeyId));
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
