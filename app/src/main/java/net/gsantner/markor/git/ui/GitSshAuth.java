/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.content.Context;

import net.gsantner.markor.git.GitRepoConfig;
import net.gsantner.markor.git.GitRepoRegistry;
import net.gsantner.markor.git.GitSettingsStore;
import net.gsantner.markor.git.GitSshAuthSource;
import net.gsantner.markor.git.ssh.GitKnownHosts;
import net.gsantner.markor.git.ssh.GitSshKey;
import net.gsantner.markor.git.ssh.GitSshKeyException;
import net.gsantner.markor.git.ssh.GitSshKeySelection;
import net.gsantner.markor.git.ssh.GitSshKeyStore;
import net.gsantner.markor.git.ssh.GitSshKeyStores;
import net.gsantner.markor.git.ssh.GitSshPassphraseCheck;
import net.gsantner.markor.git.ssh.GitSshPublicKeys;
import net.gsantner.markor.git.ssh.GitSshRemoteTrust;
import net.gsantner.markor.git.ssh.GitSshSessionFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Arrays;

/**
 * The Android side of {@link GitSshAuthSource} (roadmap task 8.1c): finds the key a repository
 * authenticates with, decrypts it, and asks the user the two questions that can come up on the way.
 * <p>
 * Two ways in, because there are two situations:
 * <ul>
 * <li>{@link #forRegistry} — an operation on a repository the app knows. The key comes from
 * {@code GitRepoConfig.sshKeyId} or the app default, and the URL in {@code .git/config} has to be the
 * one the app itself recorded: see {@link GitSshRemoteTrust} for why that check exists and what it
 * stops.</li>
 * <li>{@link #forChosenKey} — a clone, or "Test connection" in a dialog. There is no repository yet,
 * and the user picked both the URL and the key a moment ago, so there is nothing to compare them
 * against; the chosen URL is still pinned, so a redirect cannot move the key to another address.</li>
 * </ul>
 * <p>
 * Every refusal is a sentence the user can act on. None of them names the key file, the key's bytes
 * or the URL's userinfo.
 */
public final class GitSshAuth implements GitSshAuthSource {

    /** What the key-selection step concluded: a key, or the reason there is none to use. */
    private static final class Selected {
        final GitSshKey key;
        final String refusal;

        private Selected(final GitSshKey key, final String refusal) {
            this.key = key;
            this.refusal = refusal;
        }

        static Selected of(final GitSshKey key) {
            return new Selected(key, null);
        }

        static Selected refused(final String refusal) {
            return new Selected(null, refusal);
        }
    }

    /** Which key an operation on {@code repoDir} against {@code url} should use. */
    private interface Selector {
        Selected select(GitSshKeyStore store, File repoDir, String url);
    }

    private final Context _context;
    private final GitSshPrompts _prompts;
    private final Selector _selector;

    private GitSshAuth(final Context context, final GitSshPrompts prompts, final Selector selector) {
        _context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        _prompts = prompts == null ? GitSshPrompts.NONE : prompts;
        _selector = selector;
    }

    /**
     * For the Git tab: every repository brings its own key, or uses the app default.
     *
     * @param prompts how to ask the user; {@link GitSshPrompts#NONE} refuses instead of asking
     */
    public static GitSshAuth forRegistry(final Context context, final GitSshPrompts prompts) {
        return new GitSshAuth(context, prompts, (store, repoDir, url) -> {
            if (repoDir == null) {
                return Selected.refused("This operation has no repository to take an SSH key from");
            }
            final GitRepoRegistry registry = GitSettingsStore.newRegistry();
            final GitRepoConfig repo = registry.get(repoDir.getAbsolutePath());
            if (repo == null) {
                return Selected.refused("This repository is not in the Git tab's list, so it has no"
                        + " SSH key. Add it to the Git tab before syncing.");
            }
            final String untrusted = GitSshRemoteTrust.refusalFor(repo.getRemoteUrl(), url);
            if (untrusted != null) {
                return Selected.refused(untrusted);
            }
            final GitSshKey key = GitSshKeySelection.resolve(repo, store);
            if (key == null) {
                return Selected.refused(GitSshKeySelection.isSelectedKeyMissing(repo, store)
                        ? "The SSH key this repository was set up with is gone. Choose another one"
                        + " under ⋮ ‣ Remote…, or add one under Settings › Git."
                        : "No SSH key is set up yet. Add one under Settings › Git, then put its"
                        + " public half on the server.");
            }
            return Selected.of(key);
        });
    }

    /**
     * For the clone and remote dialogs: one key, chosen for one URL, for as long as that dialog's
     * operation runs.
     *
     * @param keyId the key the user picked, or {@code null} for the app default
     * @param url   the URL the user typed; an operation against any other address is refused
     */
    public static GitSshAuth forChosenKey(final Context context, final String keyId, final String url,
                                          final GitSshPrompts prompts) {
        final String pinned = url == null ? "" : url.trim();
        return new GitSshAuth(context, prompts, (store, repoDir, requested) -> {
            if (!GitSshRemoteTrust.sameRemote(pinned, requested)) {
                return Selected.refused("The address this operation connects to is not the one the"
                        + " key was chosen for.");
            }
            final GitSshKey key = keyId == null ? store.getDefault() : store.get(keyId);
            if (key == null) {
                return Selected.refused(keyId == null
                        ? "No SSH key is set up yet. Add one under Settings › Git, then put its"
                        + " public half on the server."
                        : "The SSH key that was chosen is not there any more. Choose another one.");
            }
            return Selected.of(key);
        });
    }

    // ---------------------------------------------------------------- GitSshAuthSource

    @Override
    public Resolution resolve(final File repoDir, final String url) {
        final GitSshKeyStore store = GitSshKeyStores.get(_context);
        if (store == null || !store.isUsable()) {
            return Resolution.refused("SSH keys cannot be read on this device: the app's key store is"
                    + " unavailable. Set the remote to an https:// address instead.");
        }
        final Selected selected = _selector.select(store, repoDir, url);
        if (selected.refusal != null) {
            return Resolution.refused(selected.refusal);
        }
        final GitSshKey key = selected.key;
        if (!key.canAuthenticate()) {
            // An imported ed25519 key parses and shows the right fingerprint but cannot sign in this
            // build, which has no Bouncy Castle (ADR 0002, decision 2). Said plainly rather than let
            // go and become "Auth fail" after a round trip.
            return Resolution.refused("The key “" + key.getName() + "” is an "
                    + key.getType() + " key, which this version cannot authenticate with."
                    + " Use an RSA or ECDSA key.");
        }

        byte[] privateKey = null;
        byte[] passphrase = null;
        try {
            privateKey = store.loadPrivateKey(key.getId());
            passphrase = passphraseFor(key, privateKey);
            if (passphrase == null && GitSshPassphraseCheck.isEncrypted(privateKey)) {
                return Resolution.refused("The passphrase for the key “" + key.getName()
                        + "” is needed to use it.");
            }
            final File knownHosts = GitKnownHosts.ensure(GitKnownHosts.fileIn(_context.getFilesDir()));
            if (knownHosts == null) {
                return Resolution.refused("The list of known servers cannot be written, so no server"
                        + " can be trusted. Check the app's storage.");
            }
            final Resolution resolution = Resolution.of(new GitSshSessionFactory.Identity(
                    key.getName(), privateKey, publicKeyBytes(key), passphrase), knownHosts);
            // Ownership moved into the Identity, which wipes both when the operation ends.
            privateKey = null;
            passphrase = null;
            return resolution;
        } catch (GitSshKeyException e) {
            return Resolution.refused(reasonFor(key, e));
        } finally {
            wipe(privateKey);
            wipe(passphrase);
        }
    }

    @Override
    public boolean acceptNewHostKey(final String host, final String keyType, final String fingerprint) {
        return _prompts.confirmHostKey(host, keyType, fingerprint);
    }

    // ---------------------------------------------------------------- passphrase

    /**
     * @return the passphrase bytes for {@code key}, {@code null} when it needs none or the user
     * cancelled. A remembered one is checked against the key before it is used and dropped when it
     * does not fit any more (the key may have been replaced under the same name).
     */
    private byte[] passphraseFor(final GitSshKey key, final byte[] privateKey) {
        if (!GitSshPassphraseCheck.isEncrypted(privateKey)) {
            return null;
        }
        final char[] remembered = GitSshPassphrases.get(key.getId());
        if (remembered != null) {
            final byte[] bytes = toUtf8(remembered);
            GitUiText.wipe(remembered);
            if (GitSshPassphraseCheck.canDecrypt(privateKey, bytes)) {
                return bytes;
            }
            wipe(bytes);
            GitSshPassphrases.forget(key.getId());
        }
        boolean wasWrong = false;
        for (int attempt = 0; attempt < MAX_PASSPHRASE_ATTEMPTS; attempt++) {
            final char[] typed = _prompts.askPassphrase(key.getName(), key.getFingerprintSha256(), wasWrong);
            if (typed == null) {
                return null;
            }
            final byte[] bytes = toUtf8(typed);
            final boolean fits = GitSshPassphraseCheck.canDecrypt(privateKey, bytes);
            if (fits) {
                GitSshPassphrases.remember(key.getId(), typed);
                GitUiText.wipe(typed);
                return bytes;
            }
            GitUiText.wipe(typed);
            wipe(bytes);
            wasWrong = true;
        }
        return null;
    }

    /** Three tries, then the operation fails and the user can start it again. */
    private static final int MAX_PASSPHRASE_ATTEMPTS = 3;

    // ---------------------------------------------------------------- helpers

    private static byte[] publicKeyBytes(final GitSshKey key) {
        final String line = key.getPublicKeyLine();
        return line == null || line.isEmpty() ? null : GitSshPublicKeys.toPublicKeyFileBytes(line);
    }

    private static String reasonFor(final GitSshKey key, final GitSshKeyException e) {
        switch (e.getReason()) {
            case NOT_FOUND:
                return "The SSH key “" + key.getName() + "” is not in the key store any"
                        + " more. Choose another one under Settings › Git.";
            case CRYPTO_UNAVAILABLE:
            case CRYPTO_FAILED:
                return "The SSH key “" + key.getName() + "” cannot be unlocked on this"
                        + " device. It may have been stored by another installation of the app;"
                        + " import or generate it again under Settings › Git.";
            default:
                return "The SSH key “" + key.getName() + "” cannot be read.";
        }
    }

    /** UTF-8 bytes without letting the characters become a String on the way. */
    private static byte[] toUtf8(final char[] chars) {
        if (chars == null) {
            return null;
        }
        final ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }

    private static void wipe(final byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
