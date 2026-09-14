/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The two public-key spellings the UI needs, computed from an SSH public-key blob (roadmap task
 * 8.1b): the OpenSSH one-liner that is pasted into GitHub, and the {@code SHA256:...} fingerprint
 * that GitHub, GitLab and {@code ssh-keygen -lf} print next to it.
 * <p>
 * Plain Java: {@code java.util.Base64} and {@code MessageDigest} only, both available from API 26
 * (this fork's minSdk, ADR 0001), so the same code runs in JVM unit tests. Nothing here touches a
 * private key.
 * <p>
 * A blob is the wire format of RFC 4253 section 6.6: a sequence of length-prefixed strings whose
 * first element is the algorithm name ({@code ssh-rsa}, {@code ecdsa-sha2-nistp256},
 * {@code ssh-ed25519}, ...). The algorithm is therefore read out of the blob rather than passed in,
 * so a line can never name a different algorithm than the key it carries.
 */
public final class GitSshPublicKeys {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Longest string this reads out of a blob; a real algorithm name is about 20 characters. */
    private static final int MAX_ALGORITHM_LENGTH = 64;

    /** Longest comment kept on a generated key; the line stays readable in a hosting service's UI. */
    private static final int MAX_COMMENT_LENGTH = 100;

    private GitSshPublicKeys() {
    }

    /**
     * @param blob an SSH public-key blob
     * @return the algorithm name it declares, e.g. {@code ssh-rsa}; {@code null} when the blob is
     * null, truncated or does not start with a plausible name
     */
    public static String algorithmOf(final byte[] blob) {
        if (blob == null || blob.length < 5) {
            return null;
        }
        final int length = ((blob[0] & 0xFF) << 24) | ((blob[1] & 0xFF) << 16)
                | ((blob[2] & 0xFF) << 8) | (blob[3] & 0xFF);
        if (length <= 0 || length > MAX_ALGORITHM_LENGTH || 4 + length > blob.length) {
            return null;
        }
        final String name = new String(blob, 4, length, UTF8);
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            // Algorithm names are ASCII letters, digits and -._@ (ssh-rsa, ecdsa-sha2-nistp256,
            // rsa-sha2-512, ssh-ed25519, and vendor names such as name@example.com).
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '@' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * The line that goes into {@code authorized_keys} or a hosting service's "add SSH key" field.
     *
     * @param blob    an SSH public-key blob
     * @param comment trailing comment; whitespace-collapsed, may be null or empty
     * @return {@code <algorithm> <base64 blob>[ <comment>]}, or {@code null} when the blob is unusable
     */
    public static String lineFor(final byte[] blob, final String comment) {
        final String algorithm = algorithmOf(blob);
        if (algorithm == null) {
            return null;
        }
        final String body = Base64.getEncoder().encodeToString(blob);
        final String tail = sanitizeComment(comment);
        return tail.isEmpty() ? algorithm + " " + body : algorithm + " " + body + " " + tail;
    }

    /**
     * @param blob an SSH public-key blob
     * @return {@code SHA256:} plus the unpadded base64 of the blob's SHA-256, exactly as OpenSSH
     * and GitHub print it; {@code null} when the blob is null or empty
     */
    public static String fingerprintSha256(final byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(blob);
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java and Android platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    /**
     * @param line an OpenSSH public-key line, with or without a comment
     * @return the blob it carries, or {@code null} when the line is not one (wrong field count, bad
     * base64, or a body that does not declare the algorithm the line names)
     */
    public static byte[] blobOf(final String line) {
        if (line == null) {
            return null;
        }
        final String[] parts = line.trim().split("\\s+", 3);
        if (parts.length < 2 || parts[0].isEmpty()) {
            return null;
        }
        final byte[] blob;
        try {
            blob = Base64.getDecoder().decode(parts[1]);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        return parts[0].equals(algorithmOf(blob)) ? blob : null;
    }

    /**
     * A comment for a key the app generates. Control characters and line breaks are removed because
     * the comment is the rest of a single line, and runs of whitespace are collapsed so the line
     * cannot be made to look like it has more fields than it has.
     *
     * @param comment what the user typed, may be null
     * @return a one-line comment, possibly empty, at most 100 characters
     */
    public static String sanitizeComment(final String comment) {
        if (comment == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(comment.length());
        boolean pendingSpace = false;
        for (int i = 0; i < comment.length() && sb.length() < MAX_COMMENT_LENGTH; i++) {
            final char c = comment.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0;
            } else if (c >= ' ' && c != 127) {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** @return the bytes of {@code line} plus a trailing newline, the shape a {@code .pub} file has */
    public static byte[] toPublicKeyFileBytes(final String line) {
        return ((line == null ? "" : line.trim()) + "\n").getBytes(UTF8);
    }
}
