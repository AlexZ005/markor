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

import net.gsantner.markor.git.GitRepoConfig;
import net.gsantner.markor.git.GitSettingsStore;
import net.gsantner.markor.git.ssh.GitSshKey;
import net.gsantner.markor.git.ssh.GitSshKeySelection;
import net.gsantner.markor.git.ssh.GitSshKeyStore;
import net.gsantner.markor.git.ssh.GitSshKeyStores;
import net.gsantner.markor.git.ui.CloneDialog;
import net.gsantner.markor.git.ui.RemoteSetupDialog;

/**
 * Test-flavour only, not reachable from the app: opens {@link CloneDialog} and {@link RemoteSetupDialog}
 * on a device without going through the Git tab. This replaces the "Git (test build only)" preference
 * category that used to sit in the About screen of the <i>main</i> source set (roadmap task 7.2b).
 * <pre>
 * adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.test.GitRemoteDialogsTestActivity
 * adb logcat -s GitRemoteDialogsTest
 * </pre>
 */
public class GitRemoteDialogsTestActivity extends AppCompatActivity {

    private static final String TAG = "GitRemoteDialogsTest";

    private TextView _log;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        root.addView(button("Clone repository", v -> showCloneDialog()));
        root.addView(button("Remote repository (active repo)", v -> showRemoteSetupDialog()));
        root.addView(button("Print the registry", v -> printRegistry()));
        root.addView(button("Print the SSH keys and selections", v -> printSshKeys()));

        _log = new TextView(this);
        _log.setTextIsSelectable(true);
        root.addView(_log);
        setContentView(root);

        // The host re-attaches its callback after a rotation, exactly as the Git tab does.
        final CloneDialog clone = (CloneDialog) getSupportFragmentManager()
                .findFragmentByTag(CloneDialog.FRAGMENT_TAG);
        if (clone != null) {
            clone.setListener(root1 -> log("onCloned " + root1));
        }
    }

    private Button button(final String text, final View.OnClickListener onClick) {
        final Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private void showCloneDialog() {
        final CloneDialog dialog = CloneDialog.newInstance();
        dialog.setListener(repoRoot -> log("onCloned " + repoRoot));
        dialog.show(getSupportFragmentManager(), CloneDialog.FRAGMENT_TAG);
    }

    private void showRemoteSetupDialog() {
        final GitRepoConfig active = GitSettingsStore.newRegistry().getActive();
        if (active == null) {
            Toast.makeText(this, "No repository yet - clone one first", Toast.LENGTH_SHORT).show();
            return;
        }
        final RemoteSetupDialog dialog = RemoteSetupDialog.newInstance(active.getPath());
        dialog.setListener((repoPath, remoteUrl) -> log("onRemoteSaved " + repoPath));
        dialog.show(getSupportFragmentManager(), RemoteSetupDialog.FRAGMENT_TAG);
    }

    private void printRegistry() {
        for (final GitRepoConfig repo : GitSettingsStore.newRegistry().list()) {
            log(repo.toString());
        }
    }

    /**
     * What task 8.1b stored: the keys, the default, and which key each repository resolves to. Only
     * public information - names, types and fingerprints; never a private key.
     */
    private void printSshKeys() {
        final GitSshKeyStore store = GitSshKeyStores.get(this);
        log("store usable=" + store.isUsable() + " root=" + store.getRoot());
        for (final GitSshKey key : store.list()) {
            log(key.toString() + " name=" + key.getName());
            log("  " + key.getPublicKeyLine());
        }
        final GitSshKey def = store.getDefault();
        log("default=" + (def == null ? "none" : def.getName() + " " + def.getFingerprintSha256()));
        for (final GitRepoConfig repo : GitSettingsStore.newRegistry().list()) {
            final GitSshKey resolved = GitSshKeySelection.resolve(repo, store);
            log(repo.getDisplayName() + ": sshKeyId=" + repo.getSshKeyId()
                    + " resolved=" + (resolved == null ? "none" : resolved.getName())
                    + " missing=" + GitSshKeySelection.isSelectedKeyMissing(repo, store));
        }
    }

    private void log(final String line) {
        Log.i(TAG, line);
        _log.append(line + "\n");
    }
}
