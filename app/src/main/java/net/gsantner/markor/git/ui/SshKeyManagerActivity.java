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
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.MarkorBaseActivity;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.git.GitTaskRunner;
import net.gsantner.markor.git.ssh.GitSshKey;
import net.gsantner.markor.git.ssh.GitSshKeyException;
import net.gsantner.markor.git.ssh.GitSshKeyStore;
import net.gsantner.markor.git.ssh.GitSshKeyStores;
import net.gsantner.opoc.frontend.GsSearchOrCustomTextDialog;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.util.GsContextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * The SSH keys of the app: the list, <i>Generate</i>, <i>Import from file</i> and, per key, copy or
 * share the public key, make it the default, delete it (roadmap task 8.1b). Reached from
 * Settings &gt; Git &gt; Identity &gt; SSH key.
 * <p>
 * Copying the public key is the point of the screen - it is how the key reaches GitHub, GitLab or a
 * private server - so it is the first action offered for every key and the toast says what to do
 * with it.
 * <p>
 * Everything that generates, reads or deletes key material runs on {@link GitTaskRunner}, which
 * delivers back on the main thread and drops the callback when this screen is gone. A private key
 * touches this class only as the {@code byte[]} of a file being imported; that array is wiped as
 * soon as the import is over, and it is never put into {@link #onSaveInstanceState(Bundle)} - a
 * rotation in the middle of the passphrase prompt therefore cancels the import instead of parking a
 * key in a bundle.
 */
public class SshKeyManagerActivity extends MarkorBaseActivity {

    /** Key of the {@link GitTaskRunner} worker used for key work; not a repository path. */
    private static final String TASK_KEY = "net.gsantner.markor.git.ssh";

    private GitSshKeyStore _store;
    private KeyAdapter _adapter;
    private ProgressBar _progress;
    private TextView _empty;
    private boolean _busy;

    /** The file being imported, held only between the file picker and the end of the import. */
    private byte[] _pendingKeyBytes;
    private String _pendingKeyName;

    public static void launch(final Activity activity) {
        if (activity != null) {
            activity.startActivity(new Intent(activity, SshKeyManagerActivity.class));
        }
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.git__ssh_keys__activity);

        _store = GitSshKeyStores.get(this);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.git_ssh_keys__title);
        }

        _progress = findViewById(R.id.git_ssh_keys__progress);
        _empty = findViewById(R.id.git_ssh_keys__empty);
        findViewById(R.id.git_ssh_keys__unavailable)
                .setVisibility(_store.isUsable() ? View.GONE : View.VISIBLE);

        final RecyclerView list = findViewById(R.id.git_ssh_keys__list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        _adapter = new KeyAdapter();
        list.setAdapter(_adapter);

        refresh();
    }

    @Override
    protected void onDestroy() {
        forgetPendingKey();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.git__ssh_keys__menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final boolean enabled = _store.isUsable() && !_busy;
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setEnabled(enabled);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.git_ssh_keys__generate) {
            showGenerateDialog();
            return true;
        }
        if (id == R.id.git_ssh_keys__import) {
            showFilePicker();
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- list

    private void refresh() {
        final List<GitSshKey> keys = new ArrayList<>(_store.list());
        final GitSshKey current = _store.getDefault();
        _adapter.setKeys(keys, current == null ? null : current.getId());
        _empty.setVisibility(keys.isEmpty() && _store.isUsable() ? View.VISIBLE : View.GONE);
    }

    private void setBusy(final boolean busy) {
        _busy = busy;
        _progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        invalidateOptionsMenu();
    }

    // ---------------------------------------------------------------- generate

    private void showGenerateDialog() {
        final View root = LayoutInflater.from(this).inflate(R.layout.git__ssh_key__generate_dialog, null);
        final EditText nameField = root.findViewById(R.id.git__ssh_key__generate_dialog__name);
        final RadioGroup typeGroup = root.findViewById(R.id.git__ssh_key__generate_dialog__type);

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_ssh_keys__generate)
                .setView(root)
                .setPositiveButton(R.string.git_ssh_keys__generate, null)
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = GitUiText.trimmedText(nameField);
            if (name.isEmpty()) {
                nameField.setError(getString(R.string.git_ssh_keys__name_required));
                nameField.requestFocus();
                return;
            }
            final GitSshKey.Type type = typeGroup.getCheckedRadioButtonId() == R.id.git__ssh_key__generate_dialog__ecdsa
                    ? GitSshKey.Type.ECDSA : GitSshKey.Type.RSA;
            dialog.dismiss();
            generate(type, name);
        }));
        dialog.show();
    }

    private void generate(final GitSshKey.Type type, final String name) {
        setBusy(true);
        Toast.makeText(this, R.string.git_ssh_keys__generate_running, Toast.LENGTH_SHORT).show();
        final GitSshKeyStore store = _store;
        GitTaskRunner.get().submit(TASK_KEY,
                token -> store.generate(type, name),
                () -> !isFinishing(),
                result -> {
                    setBusy(false);
                    if (!result.isSuccess()) {
                        showError(result.getError());
                        return;
                    }
                    refresh();
                    Toast.makeText(this, R.string.git_ssh_keys__generated, Toast.LENGTH_LONG).show();
                    // The public key is what has to reach the git server, so offer it right away.
                    copyPublicKey(result.getValue());
                });
    }

    // ---------------------------------------------------------------- import

    private void showFilePicker() {
        MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
            @Override
            public void onFsViewerSelected(final String request, final File file, final Integer lineNumber) {
                readAndImport(file);
            }

            @Override
            public void onFsViewerConfig(final GsFileBrowserOptions.Options dopt) {
                dopt.titleText = R.string.git_ssh_keys__import_title;
                dopt.newDirButtonEnable = false;
            }
            // No mime filter: a private key has no extension and is not text/* on every device.
        }, getSupportFragmentManager(), this, (context, file) -> file != null && file.isFile());
    }

    private void readAndImport(final File file) {
        if (file == null) {
            return;
        }
        setBusy(true);
        GitTaskRunner.get().submit(TASK_KEY,
                token -> readKeyFile(file),
                () -> !isFinishing(),
                result -> {
                    setBusy(false);
                    if (!result.isSuccess()) {
                        showError(result.getError());
                        return;
                    }
                    forgetPendingKey();
                    _pendingKeyBytes = result.getValue();
                    _pendingKeyName = file.getName();
                    tryImport(null, false);
                });
    }

    /**
     * @param passphrase UTF-8 bytes, or null for the first attempt; wiped here
     * @param retry      true when this attempt follows a refused passphrase, so the prompt says so
     */
    private void tryImport(final byte[] passphrase, final boolean retry) {
        if (_pendingKeyBytes == null) {
            GitUiText.wipe(passphrase);
            return;
        }
        setBusy(true);
        final GitSshKeyStore store = _store;
        final byte[] bytes = _pendingKeyBytes;
        final String name = _pendingKeyName;
        GitTaskRunner.get().submit(TASK_KEY,
                token -> {
                    try {
                        return store.importKey(name, bytes, passphrase);
                    } finally {
                        // In the task, not the callback: the callback is dropped when the screen is
                        // gone, and the passphrase must be gone either way.
                        GitUiText.wipe(passphrase);
                    }
                },
                () -> !isFinishing(),
                result -> {
                    setBusy(false);
                    if (result.isSuccess()) {
                        forgetPendingKey();
                        refresh();
                        Toast.makeText(this, R.string.git_ssh_keys__imported, Toast.LENGTH_SHORT).show();
                        copyPublicKey(result.getValue());
                        return;
                    }
                    final GitSshKeyException failure = asKeyException(result.getError());
                    if (failure != null && (failure.getReason() == GitSshKeyException.Reason.PASSPHRASE_REQUIRED
                            || failure.getReason() == GitSshKeyException.Reason.BAD_PASSPHRASE)) {
                        showPassphraseDialog(failure.getReason() == GitSshKeyException.Reason.BAD_PASSPHRASE);
                        return;
                    }
                    forgetPendingKey();
                    showError(result.getError());
                });
    }

    private void showPassphraseDialog(final boolean wasWrong) {
        final View root = LayoutInflater.from(this).inflate(R.layout.git__ssh_key__passphrase_dialog, null);
        final EditText field = root.findViewById(R.id.git__ssh_key__passphrase_dialog__passphrase);
        if (wasWrong) {
            field.setError(getString(R.string.git_ssh_keys__error_passphrase));
        }

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_ssh_keys__passphrase_title)
                .setView(root)
                .setPositiveButton(R.string.git_ssh_keys__import, null)
                .setNegativeButton(R.string.cancel, (d, which) -> {
                    forgetPendingKey();
                    d.dismiss();
                })
                .setOnCancelListener(d -> forgetPendingKey())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final char[] typed = GitUiText.readSecret(field);
            final byte[] passphrase = GitUiText.utf8Bytes(typed);
            GitUiText.wipe(typed);
            field.setText("");
            if (passphrase.length == 0) {
                GitUiText.wipe(passphrase);
                field.setError(getString(R.string.git_ssh_keys__error_passphrase));
                return;
            }
            dialog.dismiss();
            tryImport(passphrase, true);
        }));
        dialog.show();
    }

    private void forgetPendingKey() {
        GitUiText.wipe(_pendingKeyBytes);
        _pendingKeyBytes = null;
        _pendingKeyName = null;
    }

    /** Reads a picked file with the store's size cap, so a huge file cannot be pulled into memory. */
    private static byte[] readKeyFile(final File file) throws IOException, GitSshKeyException {
        if (file.length() > GitSshKeyStore.MAX_PRIVATE_KEY_BYTES) {
            throw new GitSshKeyException(GitSshKeyException.Reason.UNREADABLE_KEY, "The file is too large");
        }
        final byte[] bytes = new byte[(int) file.length()];
        try (final InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                final int read = in.read(bytes, offset, bytes.length - offset);
                if (read <= 0) {
                    break;
                }
                offset += read;
            }
            if (offset != bytes.length) {
                Arrays.fill(bytes, (byte) 0);
                throw new GitSshKeyException(GitSshKeyException.Reason.IO, "The file could not be read fully");
            }
        }
        return bytes;
    }

    // ---------------------------------------------------------------- per key

    private void showKeyActions(final GitSshKey key) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.git_ssh_keys__copy_public));
        actions.add(() -> copyPublicKey(key));
        labels.add(getString(R.string.git_ssh_keys__share_public));
        actions.add(() -> sharePublicKey(key));
        if (key.canAuthenticate() && !key.equals(_store.getDefault())) {
            labels.add(getString(R.string.git_ssh_keys__set_default));
            actions.add(() -> setDefault(key));
        }
        labels.add(getString(R.string.git_ssh_keys__delete));
        actions.add(() -> confirmDelete(key));

        final GsSearchOrCustomTextDialog.DialogOptions dopt = MarkorDialogFactory.baseConf(this);
        dopt.data = labels;
        dopt.titleText = R.string.git_settings__ssh_key;
        dopt.messageText = key.getName() + "\n" + key.getFingerprintSha256();
        dopt.isSearchEnabled = false;
        dopt.isSoftInputVisible = false;
        dopt.okButtonText = 0;
        dopt.positionCallback = indices -> {
            if (!indices.isEmpty()) {
                actions.get(indices.get(0)).run();
            }
        };
        GsSearchOrCustomTextDialog.showMultiChoiceDialogWithSearchFilterUI(this, dopt);
    }

    private void copyPublicKey(final GitSshKey key) {
        if (key == null || key.getPublicKeyLine().isEmpty()) {
            return;
        }
        _cu.setClipboard(this, key.getPublicKeyLine());
        Toast.makeText(this, R.string.git_ssh_keys__copied, Toast.LENGTH_LONG).show();
    }

    private void sharePublicKey(final GitSshKey key) {
        if (key != null && !key.getPublicKeyLine().isEmpty()) {
            _cu.shareText(this, key.getPublicKeyLine(), GsContextUtils.MIME_TEXT_PLAIN);
        }
    }

    private void setDefault(final GitSshKey key) {
        if (_store.setDefault(key.getId())) {
            refresh();
            Toast.makeText(this, getString(R.string.git_ssh_keys__set_default_done, key.getName()),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.git_ssh_keys__set_default_refused, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final GitSshKey key) {
        new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Rounded)
                .setTitle(R.string.git_ssh_keys__delete_title)
                .setMessage(getString(R.string.git_ssh_keys__delete_message, key.getName()))
                .setPositiveButton(R.string.delete, (d, which) -> delete(key))
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .show();
    }

    private void delete(final GitSshKey key) {
        setBusy(true);
        final GitSshKeyStore store = _store;
        GitTaskRunner.get().submit(TASK_KEY,
                token -> store.delete(key.getId()),
                () -> !isFinishing(),
                result -> {
                    setBusy(false);
                    refresh();
                    if (result.isSuccess() && Boolean.TRUE.equals(result.getValue())) {
                        Toast.makeText(this, R.string.git_ssh_keys__deleted, Toast.LENGTH_SHORT).show();
                    } else {
                        showError(result.isSuccess() ? null : result.getError());
                    }
                });
    }

    // ---------------------------------------------------------------- errors

    private void showError(final Throwable error) {
        final GitSshKeyException keyError = asKeyException(error);
        Toast.makeText(this, keyError != null
                ? GitUiText.messageFor(this, keyError)
                : getString(R.string.git_ssh_keys__error_generic), Toast.LENGTH_LONG).show();
    }

    private static GitSshKeyException asKeyException(final Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof GitSshKeyException) {
                return (GitSshKeyException) t;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- adapter

    private final class KeyAdapter extends RecyclerView.Adapter<KeyViewHolder> {
        private final List<GitSshKey> _keys = new ArrayList<>();
        private String _defaultId;

        void setKeys(final List<GitSshKey> keys, final String defaultId) {
            _keys.clear();
            _keys.addAll(keys);
            _defaultId = defaultId;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public KeyViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new KeyViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.git__ssh_keys__key_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final KeyViewHolder holder, final int position) {
            holder.bind(_keys.get(position), _keys.get(position).getId().equals(_defaultId));
        }

        @Override
        public int getItemCount() {
            return _keys.size();
        }
    }

    private final class KeyViewHolder extends RecyclerView.ViewHolder {
        private final TextView _name;
        private final TextView _detail;
        private final TextView _note;
        private final TextView _defaultBadge;

        KeyViewHolder(final View itemView) {
            super(itemView);
            _name = itemView.findViewById(R.id.git_ssh_keys__item_name);
            _detail = itemView.findViewById(R.id.git_ssh_keys__item_detail);
            _note = itemView.findViewById(R.id.git_ssh_keys__item_note);
            _defaultBadge = itemView.findViewById(R.id.git_ssh_keys__item_default);
        }

        void bind(final GitSshKey key, final boolean isDefault) {
            final Context context = itemView.getContext();
            _name.setText(key.getName());
            _detail.setText(key.describe());
            _defaultBadge.setVisibility(isDefault ? View.VISIBLE : View.GONE);

            final String note = note(context, key);
            _note.setText(note);
            _note.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
            itemView.setOnClickListener(v -> showKeyActions(key));
        }

        private String note(final Context context, final GitSshKey key) {
            if (!key.canAuthenticate()) {
                return context.getString(R.string.git_ssh_keys__cannot_authenticate);
            }
            if (key.hasPassphrase()) {
                return context.getString(R.string.git_ssh_keys__has_passphrase);
            }
            if (key.getCreatedEpoch() > 0) {
                return context.getString(R.string.git_ssh_keys__added,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(key.getCreatedEpoch())));
            }
            return "";
        }
    }
}
