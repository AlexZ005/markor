/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The app's SSH keys: generate, import, list, export the public half, pick a default, delete
 * (roadmap task 8.1b, doc/adr/0002-ssh-on-android.md "What the next two lanes must implement (a)").
 *
 * <h3>Where things are</h3>
 * Everything lives under one folder, on Android {@code Context.getFilesDir()/git/ssh}, which is
 * app-private:
 * <pre>
 * &lt;root&gt;/index.json      names, types, public-key lines, fingerprints, the default key id
 * &lt;root&gt;/&lt;id&gt;/key.enc   the private key, encrypted with a key from the Android Keystore
 * </pre>
 * A private key is <b>never</b> put into {@code SharedPreferences}, a log line, a toast, an
 * instance state bundle or a repository's {@code .git/config}. It leaves this class only through
 * {@link #loadPrivateKey(String)}, as a {@code byte[]} the caller wipes.
 *
 * <h3>What is stored</h3>
 * A key this app <b>generates</b> is written as OpenSSH v1 without a passphrase - never with
 * {@code KeyPair.writePrivateKey(out, passphrase)}, which JSch cannot read back (ADR 0002, defect
 * 1); its protection is the Keystore encryption of {@code key.enc}. A key that is <b>imported</b> is
 * stored byte for byte as the file had it, so a passphrase-protected file stays passphrase-protected
 * and {@link GitSshKey#hasPassphrase()} is {@code true}; the passphrase itself is never stored, and
 * the code that runs the git operation has to ask for it.
 *
 * <h3>Threads</h3>
 * Every method is synchronized and does file I/O, so call them off the main thread. Key generation
 * takes a few hundred milliseconds for RSA 4096 on a phone.
 *
 * <h3>Plain Java</h3>
 * No Android imports: the folder and the encryption are handed in, which is what lets the whole
 * store run in JVM unit tests with a temp folder and an in-memory {@link Vault}. On Android,
 * {@link GitSshKeyStores#get(android.content.Context)} builds the real one.
 */
public final class GitSshKeyStore {

    /**
     * Encryption at rest for the private keys. The Android implementation wraps them with an
     * AES/GCM key that lives in the Android Keystore and cannot be exported; unit tests use an
     * in-memory double.
     * <p>
     * Implementations must be thread safe and must not log what they are given.
     */
    public interface Vault {
        /**
         * @return {@code false} when this device cannot encrypt (no usable Keystore); the store then
         * refuses to create keys instead of writing them in clear
         */
        boolean isAvailable();

        /**
         * @param plain the private key bytes; the caller keeps ownership and wipes them
         * @return an opaque blob {@link #unwrap(byte[])} turns back into {@code plain}
         */
        byte[] wrap(byte[] plain) throws GitSshKeyException;

        /**
         * @param wrapped what {@link #wrap(byte[])} returned
         * @return the private key bytes; the caller wipes them
         * @throws GitSshKeyException with {@link GitSshKeyException.Reason#CRYPTO_FAILED} when the
         *                            blob does not decrypt (Keystore key gone, restored backup)
         */
        byte[] unwrap(byte[] wrapped) throws GitSshKeyException;
    }

    /** Bits used for a generated RSA key; the size GitHub, GitLab and Gitea all accept. */
    public static final int RSA_BITS = 4096;

    /** Curve size used for a generated ECDSA key: nistp256, which JSch has without Bouncy Castle. */
    public static final int ECDSA_BITS = 256;

    /** Refusal threshold when reading an imported file; a 16 kB RSA key is about 12 kB of base64. */
    public static final int MAX_PRIVATE_KEY_BYTES = 512 * 1024;

    private static final Logger LOGGER = Logger.getLogger("net.gsantner.markor.git");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String INDEX_FILE = "index.json";
    private static final String INDEX_TEMP_FILE = "index.json.tmp";
    private static final String KEY_FILE = "key.enc";
    private static final int ID_RANDOM_BYTES = 8;

    private final File _root;
    private final Vault _vault;
    private final SecureRandom _random = new SecureRandom();

    /** Loaded lazily from {@code index.json} and kept in step with it by every mutating method. */
    private List<GitSshKey> _keys;
    private String _defaultKeyId;

    /**
     * @param root  the folder the keys live in; created on first write
     * @param vault encryption at rest for the private keys
     */
    public GitSshKeyStore(final File root, final Vault vault) {
        if (root == null || vault == null) {
            throw new IllegalArgumentException("root and vault are required");
        }
        _root = root;
        _vault = vault;
    }

    /**
     * @return {@code false} when this device has no usable Keystore. Listing, exporting and deleting
     * still work; {@link #generate} and {@link #importKey} refuse with
     * {@link GitSshKeyException.Reason#CRYPTO_UNAVAILABLE}, and so does
     * {@link #loadPrivateKey(String)}.
     */
    public boolean isUsable() {
        return _vault.isAvailable();
    }

    /** @return the folder the keys live in; app-private on Android */
    public File getRoot() {
        return _root;
    }

    // ---------------------------------------------------------------- reading

    /**
     * @return every stored key, oldest first, without private material. Entries whose private key
     * file has disappeared are left out. Never null.
     */
    public synchronized List<GitSshKey> list() {
        loadIndex();
        final List<GitSshKey> result = new ArrayList<>(_keys.size());
        for (final GitSshKey key : _keys) {
            if (keyFile(key.getId()) != null && keyFile(key.getId()).isFile()) {
                result.add(key);
            } else {
                LOGGER.log(Level.WARNING, "SSH key " + key.getId() + " has no key file and is not listed");
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @param id a key id, may be null
     * @return the key with that id, or {@code null} when there is none (or its file is gone)
     */
    public synchronized GitSshKey get(final String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        final String wanted = id.trim();
        for (final GitSshKey key : list()) {
            if (key.getId().equals(wanted)) {
                return key;
            }
        }
        return null;
    }

    /**
     * @return the key repositories use unless they name another one, or {@code null} when there is
     * none. The first usable key created becomes the default by itself; after that it only changes
     * through {@link #setDefault(String)}, so no deletion can silently move the app to another
     * identity.
     */
    public synchronized GitSshKey getDefault() {
        final List<GitSshKey> keys = list();
        if (_defaultKeyId != null) {
            for (final GitSshKey key : keys) {
                if (key.getId().equals(_defaultKeyId)) {
                    return key;
                }
            }
        }
        return null;
    }

    /**
     * Makes {@code id} the default key.
     *
     * @param id id of a stored key, or {@code null} to have no default at all
     * @return {@code false} when there is no such key, when it is of a type this build cannot
     * authenticate with ({@link GitSshKey#canAuthenticate()}), or when the index could not be written
     */
    public synchronized boolean setDefault(final String id) {
        loadIndex();
        if (id == null || id.trim().isEmpty()) {
            _defaultKeyId = null;
            return writeIndexQuietly();
        }
        final GitSshKey key = get(id);
        if (key == null || !key.canAuthenticate()) {
            return false;
        }
        _defaultKeyId = key.getId();
        return writeIndexQuietly();
    }

    /**
     * @param id a key id
     * @return the OpenSSH public-key line to paste into a hosting service, or {@code null} when
     * there is no such key
     */
    public synchronized String exportPublicKey(final String id) {
        final GitSshKey key = get(id);
        return key == null ? null : key.getPublicKeyLine();
    }

    /**
     * The private key of {@code id}, decrypted from the Keystore-wrapped file but still in whatever
     * format it was stored in - so for a key with {@link GitSshKey#hasPassphrase()} these bytes need
     * that passphrase before JSch can use them.
     * <p>
     * <b>Wipe after use.</b> The caller owns the array and must overwrite it
     * ({@code java.util.Arrays.fill(bytes, (byte) 0)}) as soon as the operation is done, the way
     * {@link GitSshSessionFactory.Identity#wipe()} does for the transport.
     *
     * @param id a key id
     * @return the private key bytes, never null
     * @throws GitSshKeyException {@link GitSshKeyException.Reason#NOT_FOUND},
     *                            {@link GitSshKeyException.Reason#IO},
     *                            {@link GitSshKeyException.Reason#CRYPTO_UNAVAILABLE} or
     *                            {@link GitSshKeyException.Reason#CRYPTO_FAILED}
     */
    public synchronized byte[] loadPrivateKey(final String id) throws GitSshKeyException {
        final GitSshKey key = get(id);
        if (key == null) {
            throw new GitSshKeyException(GitSshKeyException.Reason.NOT_FOUND, "No SSH key with this id");
        }
        if (!_vault.isAvailable()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE,
                    "No usable Keystore, the stored key cannot be decrypted");
        }
        final byte[] wrapped = readFile(keyFile(key.getId()));
        try {
            return _vault.unwrap(wrapped);
        } finally {
            Arrays.fill(wrapped, (byte) 0);
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Creates a new key pair in the app, with no passphrase (the file is encrypted at rest instead).
     * RSA gets {@link #RSA_BITS} bits, ECDSA the nistp256 curve.
     *
     * @param type {@link GitSshKey.Type#RSA} or {@link GitSshKey.Type#ECDSA}; ed25519 needs Bouncy
     *             Castle, which this build does not ship (ADR 0002, decision 2)
     * @param name what to call it in the UI; also becomes the public key's comment
     * @return the new key, already stored; it becomes the default when it is the first one
     * @throws GitSshKeyException with {@link GitSshKeyException.Reason#INVALID_REQUEST},
     *                            {@link GitSshKeyException.Reason#CRYPTO_UNAVAILABLE},
     *                            {@link GitSshKeyException.Reason#GENERATE_FAILED} or
     *                            {@link GitSshKeyException.Reason#IO}
     */
    public synchronized GitSshKey generate(final GitSshKey.Type type, final String name) throws GitSshKeyException {
        return generate(type, name, type == GitSshKey.Type.ECDSA ? ECDSA_BITS : RSA_BITS);
    }

    /** Same as {@link #generate(GitSshKey.Type, String)} with an explicit size; tests use 2048 bits. */
    synchronized GitSshKey generate(final GitSshKey.Type type, final String name, final int bits) throws GitSshKeyException {
        if (type == null || !type.isOfferedForGeneration()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.INVALID_REQUEST,
                    "This build cannot generate a key of type " + type);
        }
        if (name == null || name.trim().isEmpty()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.INVALID_REQUEST, "A key name is required");
        }
        requireVault();

        final JSch jsch = new JSch();
        final KeyPair pair;
        try {
            pair = KeyPair.genKeyPair(jsch, type == GitSshKey.Type.ECDSA ? KeyPair.ECDSA : KeyPair.RSA, bits);
        } catch (final JSchException e) {
            throw new GitSshKeyException(GitSshKeyException.Reason.GENERATE_FAILED,
                    "Key generation failed: " + e.getClass().getSimpleName(), e);
        }
        byte[] privateKey = null;
        try {
            pair.setPublicKeyComment(GitSshPublicKeys.sanitizeComment(name));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                // OpenSSH v1 without a passphrase. JSch's legacy PEM writer with a passphrase
                // produces a file JSch itself cannot read back (ADR 0002, defect 1).
                pair.writeOpenSSHv1PrivateKey(out, null);
            } catch (final JSchException e) {
                throw new GitSshKeyException(GitSshKeyException.Reason.GENERATE_FAILED,
                        "The generated key could not be encoded: " + e.getClass().getSimpleName(), e);
            }
            privateKey = out.toByteArray();
            return store(name, describeKeyPair(pair, name), privateKey, false);
        } finally {
            if (privateKey != null) {
                Arrays.fill(privateKey, (byte) 0);
            }
            pair.dispose();
        }
    }

    /**
     * Imports a private key file. The bytes are kept exactly as they are, once it is established
     * that JSch can read them - so a passphrase-protected file stays protected and the passphrase is
     * not stored anywhere.
     * <p>
     * A file this build cannot <i>sign</i> with, an ed25519 key, is still imported: it gets its real
     * type and fingerprint so the UI can explain why it cannot be selected, instead of failing with
     * a stack trace (ADR 0002, decision 2).
     *
     * @param name            what to call it in the UI
     * @param privateKeyBytes the file's content; the caller keeps ownership and should wipe it
     * @param passphrase      UTF-8 bytes of the passphrase, or {@code null} when the file has none;
     *                        used to check the file can be opened and then forgotten. The caller
     *                        keeps ownership and should wipe it.
     * @return the stored key
     * @throws GitSshKeyException {@link GitSshKeyException.Reason#UNREADABLE_KEY} for bytes that are
     *                            no private key, {@link GitSshKeyException.Reason#PASSPHRASE_REQUIRED}
     *                            when it is encrypted and no passphrase was given,
     *                            {@link GitSshKeyException.Reason#BAD_PASSPHRASE} when the passphrase
     *                            is wrong, plus {@code INVALID_REQUEST}, {@code CRYPTO_UNAVAILABLE}
     *                            and {@code IO}
     */
    public synchronized GitSshKey importKey(final String name, final byte[] privateKeyBytes, final byte[] passphrase)
            throws GitSshKeyException {
        if (name == null || name.trim().isEmpty()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.INVALID_REQUEST, "A key name is required");
        }
        if (privateKeyBytes == null || privateKeyBytes.length == 0) {
            throw new GitSshKeyException(GitSshKeyException.Reason.UNREADABLE_KEY, "The file is empty");
        }
        if (privateKeyBytes.length > MAX_PRIVATE_KEY_BYTES) {
            throw new GitSshKeyException(GitSshKeyException.Reason.UNREADABLE_KEY,
                    "The file is far too large to be a private key");
        }
        requireVault();

        // JSch parses in place and zeroes buffers as it goes, so it never sees the array that is
        // stored, and a failed attempt cannot damage it.
        final byte[] forStorage = privateKeyBytes.clone();
        final byte[] forParsing = privateKeyBytes.clone();
        KeyPair pair = null;
        try {
            try {
                pair = KeyPair.load(new JSch(), forParsing, null);
            } catch (final JSchException e) {
                throw new GitSshKeyException(GitSshKeyException.Reason.UNREADABLE_KEY,
                        "Not a private key this build can read: " + e.getClass().getSimpleName(), e);
            }
            final boolean encrypted = pair.isEncrypted();
            if (encrypted) {
                if (passphrase == null || passphrase.length == 0) {
                    throw new GitSshKeyException(GitSshKeyException.Reason.PASSPHRASE_REQUIRED,
                            "The key file is encrypted");
                }
                // decrypt() consumes the array it is given; hand it a copy of our copy.
                final byte[] attempt = passphrase.clone();
                final boolean opened;
                try {
                    opened = pair.decrypt(attempt);
                } finally {
                    Arrays.fill(attempt, (byte) 0);
                }
                if (!opened) {
                    throw new GitSshKeyException(GitSshKeyException.Reason.BAD_PASSPHRASE,
                            "The passphrase does not open this key file");
                }
            }
            final GitSshKey described = describeKeyPair(pair, name);
            return store(name, described, forStorage, encrypted);
        } finally {
            Arrays.fill(forStorage, (byte) 0);
            Arrays.fill(forParsing, (byte) 0);
            if (pair != null) {
                pair.dispose();
            }
        }
    }

    /**
     * Removes a key and its private half. The file is overwritten before it is unlinked, which is
     * the most a normal app can do about a key that was on flash storage.
     *
     * @param id a key id
     * @return {@code false} when there was no such key
     */
    public synchronized boolean delete(final String id) {
        loadIndex();
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        final String wanted = id.trim();
        GitSshKey removed = null;
        for (final GitSshKey key : _keys) {
            if (key.getId().equals(wanted)) {
                removed = key;
                break;
            }
        }
        if (removed == null) {
            return false;
        }
        _keys.remove(removed);
        if (wanted.equals(_defaultKeyId)) {
            // No silent promotion of another key: which identity is used must stay a decision the
            // user made, not a consequence of a deletion.
            _defaultKeyId = null;
        }
        writeIndexQuietly();
        shredAndDelete(keyFile(wanted));
        final File dir = keyDir(wanted);
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
        return true;
    }

    // ---------------------------------------------------------------- internals

    private void requireVault() throws GitSshKeyException {
        if (!_vault.isAvailable()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.CRYPTO_UNAVAILABLE,
                    "No usable Keystore, so no private key can be stored safely");
        }
    }

    /** Reads type, size, public-key line and fingerprint off a loaded (and decrypted) key pair. */
    private GitSshKey describeKeyPair(final KeyPair pair, final String name) throws GitSshKeyException {
        final byte[] blob = pair.getPublicKeyBlob();
        final String line = GitSshPublicKeys.lineFor(blob, comment(pair, name));
        final String fingerprint = GitSshPublicKeys.fingerprintSha256(blob);
        if (line == null || fingerprint == null) {
            throw new GitSshKeyException(GitSshKeyException.Reason.UNREADABLE_KEY,
                    "The key carries no usable public key");
        }
        return new GitSshKey("", name, typeOf(pair), Math.max(0, pair.getKeySize()), line, fingerprint,
                System.currentTimeMillis(), false);
    }

    private static String comment(final KeyPair pair, final String name) {
        final String stored = GitSshPublicKeys.sanitizeComment(pair.getPublicKeyComment());
        return stored.isEmpty() ? GitSshPublicKeys.sanitizeComment(name) : stored;
    }

    private static GitSshKey.Type typeOf(final KeyPair pair) {
        switch (pair.getKeyType()) {
            case KeyPair.RSA:
                return GitSshKey.Type.RSA;
            case KeyPair.ECDSA:
                return GitSshKey.Type.ECDSA;
            case KeyPair.ED25519:
                return GitSshKey.Type.ED25519;
            default:
                // DSA, ed448 and anything a newer JSch adds: readable, named, but not offered.
                return GitSshKey.Type.UNKNOWN;
        }
    }

    /**
     * Writes the private key and adds the entry to the index. The key file goes first: if the index
     * write fails there is an orphaned file, which {@link #list()} ignores, whereas the other order
     * would leave an entry with no key.
     */
    private GitSshKey store(final String name, final GitSshKey described, final byte[] privateKey,
                            final boolean hasPassphrase) throws GitSshKeyException {
        loadIndex();
        final String id = newId();
        final GitSshKey key = new GitSshKey(id, name, described.getType(), described.getBits(),
                described.getPublicKeyLine(), described.getFingerprintSha256(),
                described.getCreatedEpoch(), hasPassphrase);

        final byte[] wrapped = _vault.wrap(privateKey);
        try {
            final File dir = keyDir(id);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Cannot create the key folder");
            }
            writeFile(keyFile(id), wrapped);
        } finally {
            Arrays.fill(wrapped, (byte) 0);
        }

        _keys.add(key);
        final boolean firstUsableKey = _defaultKeyId == null && key.canAuthenticate();
        if (firstUsableKey) {
            _defaultKeyId = id;
        }
        try {
            writeIndex();
        } catch (final GitSshKeyException e) {
            _keys.remove(key);
            if (firstUsableKey) {
                _defaultKeyId = null;
            }
            shredAndDelete(keyFile(id));
            throw e;
        }
        return key;
    }

    /** @return a short id that is safe as a folder name and unique among the stored keys */
    private String newId() {
        final byte[] bytes = new byte[ID_RANDOM_BYTES];
        for (int attempt = 0; attempt < 100; attempt++) {
            _random.nextBytes(bytes);
            final StringBuilder sb = new StringBuilder(1 + bytes.length * 2);
            sb.append('k');
            for (final byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            final String id = sb.toString();
            boolean taken = false;
            for (final GitSshKey key : _keys) {
                taken |= key.getId().equals(id);
            }
            if (!taken && !keyDir(id).exists()) {
                return id;
            }
        }
        throw new IllegalStateException("Could not find a free SSH key id");
    }

    /**
     * @return the folder of {@code id}, or {@code null} when the id is not one this store hands out.
     * Ids reach this class from the stored repository list as well, and a path is never built from
     * one without this check (see the 7.5 security review: anything read back off storage is
     * re-checked at the point of use).
     */
    private File keyDir(final String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            final char c = id.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return new File(_root, id);
    }

    private File keyFile(final String id) {
        final File dir = keyDir(id);
        return dir == null ? null : new File(dir, KEY_FILE);
    }

    // ---------------------------------------------------------------- index file

    private void loadIndex() {
        if (_keys != null) {
            return;
        }
        String json = "";
        final File index = new File(_root, INDEX_FILE);
        if (index.isFile()) {
            try {
                json = new String(readFile(index), UTF8);
            } catch (final GitSshKeyException e) {
                LOGGER.log(Level.WARNING, "The SSH key index could not be read (" + e.getReason() + ")");
            }
        }
        final GitSshKeyIndexCodec.Index parsed = GitSshKeyIndexCodec.fromJson(json);
        _keys = new ArrayList<>(parsed.getKeys());
        _defaultKeyId = parsed.getDefaultKeyId();
    }

    private void writeIndex() throws GitSshKeyException {
        if (!_root.isDirectory() && !_root.mkdirs()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Cannot create the SSH key folder");
        }
        final byte[] json = GitSshKeyIndexCodec.toJson(_keys, _defaultKeyId).getBytes(UTF8);
        final File temp = new File(_root, INDEX_TEMP_FILE);
        final File index = new File(_root, INDEX_FILE);
        writeFile(temp, json);
        // Replace in one step, so a process death cannot leave a half-written index behind.
        //noinspection ResultOfMethodCallIgnored
        index.delete();
        if (!temp.renameTo(index)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Cannot replace the SSH key index");
        }
    }

    private boolean writeIndexQuietly() {
        try {
            writeIndex();
            return true;
        } catch (final GitSshKeyException e) {
            LOGGER.log(Level.WARNING, "The SSH key index could not be written (" + e.getReason() + ")");
            return false;
        }
    }

    // ---------------------------------------------------------------- files

    private static byte[] readFile(final File file) throws GitSshKeyException {
        if (file == null || !file.isFile()) {
            throw new GitSshKeyException(GitSshKeyException.Reason.NOT_FOUND, "No such file");
        }
        final long length = file.length();
        if (length > MAX_PRIVATE_KEY_BYTES) {
            throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Stored file is implausibly large");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(64L, length));
        final byte[] buffer = new byte[8192];
        try (final InputStream in = new FileInputStream(file)) {
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
        } catch (final IOException e) {
            throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Cannot read " + file.getName(), e);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        return out.toByteArray();
    }

    private static void writeFile(final File file, final byte[] content) throws GitSshKeyException {
        try (final OutputStream out = new FileOutputStream(file)) {
            out.write(content);
            out.flush();
        } catch (final IOException e) {
            throw new GitSshKeyException(GitSshKeyException.Reason.IO, "Cannot write " + file.getName(), e);
        }
    }

    /** Overwrites the file with zeroes, then unlinks it. Best effort: flash storage may copy blocks. */
    private static void shredAndDelete(final File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        try (final RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            final long length = raf.length();
            final byte[] zeroes = new byte[4096];
            for (long written = 0; written < length; written += zeroes.length) {
                raf.write(zeroes, 0, (int) Math.min(zeroes.length, length - written));
            }
            raf.getFD().sync();
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "An SSH key file could not be overwritten before deletion");
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
