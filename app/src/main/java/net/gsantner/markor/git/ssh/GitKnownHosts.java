/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The servers this app has been told to trust: an OpenSSH {@code known_hosts} file in app-private
 * storage, plus the two things the UI does with it — show what is in it, and forget one entry.
 * <p>
 * The file is deliberately <b>not</b> hashed ({@code HashKnownHosts=no}, see
 * {@code doc/adr/0002-ssh-on-android.md}): hashing exists so that a stolen laptop does not reveal
 * which servers its owner logs in to, which buys nothing for an app-private file, and it would make
 * "here is what you trust, forget this one" impossible to offer. Writing is left to JSch, which
 * appends an entry when the user confirms a fingerprint; this class only reads and deletes.
 * <p>
 * <b>Why forgetting has to be a separate, deliberate act.</b> A host key that does not match the
 * stored one fails the operation and is never offered for confirmation — the error dialog has no
 * "trust it anyway" button, because a user who is being intercepted would press it. The way back is
 * Settings &rsaquo; Git &rsaquo; SSH known hosts, where the entry is removed on purpose and the next
 * connection asks again as if the host were new.
 * <p>
 * Plain Java, no Android types, so the parsing is covered by JVM unit tests.
 */
public final class GitKnownHosts {

    /** Where the file lives, relative to {@code Context.getFilesDir()}. */
    public static final String RELATIVE_PATH = "git/known_hosts";

    /** One trusted host key, as the Git tab shows it. */
    public static final class Entry {
        private final String _host;
        private final String _keyType;
        private final String _fingerprint;

        Entry(final String host, final String keyType, final String fingerprint) {
            _host = host;
            _keyType = keyType;
            _fingerprint = fingerprint;
        }

        /** @return the host as it was written in the remote URL, e.g. {@code github.com} or {@code [h]:2222} */
        public String getHost() {
            return _host;
        }

        /** @return {@code ssh-rsa}, {@code ecdsa-sha2-nistp256}, {@code ssh-ed25519}, … */
        public String getKeyType() {
            return _keyType;
        }

        /** @return {@code SHA256:…}, the spelling GitHub and {@code ssh-keygen -lf} show */
        public String getFingerprintSha256() {
            return _fingerprint;
        }

        /** @return one line for a list: host, key type and fingerprint */
        public String describe() {
            return _host + "\n" + _keyType + " · " + _fingerprint;
        }

        @Override
        public String toString() {
            return "GitKnownHosts.Entry{" + _host + ", " + _keyType + "}";
        }
    }

    private GitKnownHosts() {
    }

    /** @param filesDir {@code Context.getFilesDir()} */
    public static File fileIn(final File filesDir) {
        return new File(filesDir, RELATIVE_PATH);
    }

    /**
     * Creates the file and its folder if they are not there yet, so JSch can append to it.
     *
     * @return the file, or {@code null} when it could not be created
     */
    public static File ensure(final File knownHosts) {
        if (knownHosts == null) {
            return null;
        }
        final File parent = knownHosts.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }
        if (!knownHosts.exists()) {
            try {
                if (!knownHosts.createNewFile()) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return knownHosts;
    }

    /**
     * @return one entry per stored key, in file order; empty when the file is missing or unreadable.
     * A line that cannot be parsed is skipped rather than shown as garbage.
     */
    public static List<Entry> list(final File knownHosts) {
        final List<String> lines = readLines(knownHosts);
        final List<Entry> entries = new ArrayList<>();
        for (final String line : lines) {
            final Entry entry = parse(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Removes every stored key for one host. The next connection to it asks for a fingerprint again.
     *
     * @param host the host exactly as {@link Entry#getHost()} reported it
     * @return {@code true} when something was removed and the file was rewritten
     */
    public static boolean forget(final File knownHosts, final String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        final String wanted = host.trim();
        final List<String> lines = readLines(knownHosts);
        final List<String> kept = new ArrayList<>(lines.size());
        boolean removed = false;
        for (final String line : lines) {
            final Entry entry = parse(line);
            if (entry != null && wanted.equals(entry.getHost())) {
                removed = true;
            } else {
                kept.add(line);
            }
        }
        if (!removed) {
            return false;
        }
        final StringBuilder out = new StringBuilder();
        for (final String line : kept) {
            out.append(line).append('\n');
        }
        try {
            Files.write(knownHosts.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * One {@code known_hosts} line: {@code host[,host…] keytype base64[ comment]}. A marker line
     * ({@code @revoked}, {@code @cert-authority}) is skipped — the app never writes one and cannot
     * show it meaningfully — and so is a hashed entry ({@code |1|…}), whose host cannot be recovered.
     */
    static Entry parse(final String line) {
        if (line == null) {
            return null;
        }
        final String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '@' || trimmed.startsWith("|")) {
            return null;
        }
        final String[] parts = trimmed.split("\\s+");
        if (parts.length < 3) {
            return null;
        }
        final byte[] blob = GitSshPublicKeys.blobOf(parts[1] + " " + parts[2]);
        if (blob == null || blob.length == 0) {
            return null;
        }
        final String fingerprint = GitSshPublicKeys.fingerprintSha256(blob);
        if (fingerprint == null) {
            return null;
        }
        return new Entry(parts[0], parts[1].toLowerCase(Locale.ROOT), fingerprint);
    }

    private static List<String> readLines(final File knownHosts) {
        if (knownHosts == null || !knownHosts.isFile()) {
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(knownHosts.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }
}
