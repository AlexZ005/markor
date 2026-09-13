/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.content.Context;
import android.text.Editable;
import android.widget.EditText;

import net.gsantner.markor.R;
import net.gsantner.markor.git.GitCredentialStore;
import net.gsantner.markor.git.GitResult;

import java.util.Arrays;

/**
 * Turns the typed outcomes of the git layer into the sentences the dialogs show, and reads a secret
 * out of an {@link EditText} without ever making it a {@code String}.
 * <p>
 * Only the two kinds the roadmap names get their own wording ({@code AUTH_FAILED} and
 * {@code NETWORK}); everything else falls back to the result's own credential-free message, so a new
 * result kind in the core layer still produces something readable here.
 */
public final class GitUiText {

    private GitUiText() {
    }

    /**
     * @param result a non-OK result
     * @return a message for a toast or an inline error; never contains a token or a URL with userinfo
     */
    public static String messageFor(final Context context, final GitResult<?> result) {
        if (result == null) {
            return context.getString(R.string.git_error__generic);
        }
        switch (result.getKind()) {
            case AUTH_FAILED:
                return context.getString(R.string.git_error__auth_failed);
            case NETWORK:
                return context.getString(R.string.git_error__network);
            default:
                final String message = result.getMessage();
                return message == null || message.trim().isEmpty() ? context.getString(R.string.git_error__generic) : message;
        }
    }

    /** @return the sentence explaining why a URL was refused, or {@code null} when it is fine */
    public static String messageFor(final Context context, final GitRemoteUrlValidator.Problem problem) {
        switch (problem) {
            case EMPTY:
                return context.getString(R.string.git_error__url_empty);
            case SSH_NOT_SUPPORTED:
                return context.getString(R.string.git_error__url_ssh);
            case CLEARTEXT_HTTP:
                return context.getString(R.string.git_error__url_http);
            case UNSUPPORTED_SCHEME:
                return context.getString(R.string.git_error__url_scheme);
            case CONTAINS_PASSWORD:
                return context.getString(R.string.git_error__url_password);
            case MALFORMED:
                return context.getString(R.string.git_error__url_malformed);
            default:
                return null;
        }
    }

    /**
     * Copies the field's content into a fresh array. The caller must {@link #wipe(char[])} it; the
     * token deliberately never becomes a {@code String}, which would linger in the string pool.
     *
     * @return the characters, or an empty array when the field is empty
     */
    public static char[] readSecret(final EditText field) {
        final Editable text = field == null ? null : field.getText();
        final int length = text == null ? 0 : text.length();
        if (length == 0) {
            return new char[0];
        }
        final char[] out = new char[length];
        text.getChars(0, length, out, 0);
        return out;
    }

    /** Overwrites the array with NULs. Null-safe. */
    public static void wipe(final char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    /**
     * Stores the credentials for the URL's host, tolerating a device whose Keystore throws.
     * A repository whose credentials could not be saved still works; the user is asked again.
     *
     * @param secret the caller keeps ownership and wipes it afterwards; an empty array stores nothing
     * @return {@code true} when they were stored
     */
    public static boolean saveCredentials(final Context context, final String url, final String username, final char[] secret) {
        if (secret == null || secret.length == 0 || username == null || username.isEmpty()) {
            return false;
        }
        try {
            return GitCredentialStore.get(context).save(url, username, secret);
        } catch (RuntimeException e) {
            // Keystore failures differ per ROM and must never take the app down mid-dialog.
            return false;
        }
    }

    /** @return the trimmed text of the field, never {@code null} */
    public static String trimmedText(final EditText field) {
        final CharSequence text = field == null || field.getText() == null ? "" : field.getText();
        return text.toString().trim();
    }
}
