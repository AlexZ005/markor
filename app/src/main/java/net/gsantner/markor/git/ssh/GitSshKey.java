/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import com.google.gson.annotations.SerializedName;

import java.util.Locale;

/**
 * One SSH key known to the app, without its private half (roadmap task 8.1b).
 * <p>
 * This is the public half plus the metadata the UI shows: everything here may be displayed, logged,
 * shared and copied to a clipboard. The private key never appears in this class; it is read from
 * {@link GitSshKeyStore#loadPrivateKey(String)} by the code that is about to authenticate with it.
 * <p>
 * Plain Java (no Android imports) so the store is unit tested on the JVM. Persisted as JSON inside
 * the store's index by {@link GitSshKeyIndexCodec}; the JSON names are pinned with
 * {@link SerializedName} so R8 field renaming cannot change the stored format, the same way
 * {@link net.gsantner.markor.git.GitRepoConfig} does it.
 */
public class GitSshKey {

    /**
     * The key algorithm. Which of these can actually authenticate is a property of the build, not
     * of the key: this build has no Bouncy Castle, so ed25519 keys can be read, named and
     * fingerprinted but cannot sign (doc/adr/0002-ssh-on-android.md, decision 2).
     */
    public enum Type {
        /** RSA; generated with 4096 bits, imported at whatever size the file has. */
        RSA(true, true),
        /** ECDSA nistp256/384/521. Works, and is the small modern alternative to RSA here. */
        ECDSA(true, true),
        /** Imported ed25519. Parses and fingerprints, but cannot sign in this build. */
        ED25519(false, false),
        /** Anything else JSch could read but this app does not name (DSA, ed448, …). */
        UNKNOWN(false, false);

        private final boolean _canAuthenticate;
        private final boolean _offeredForGeneration;

        Type(final boolean canAuthenticate, final boolean offeredForGeneration) {
            _canAuthenticate = canAuthenticate;
            _offeredForGeneration = offeredForGeneration;
        }

        /**
         * @return {@code true} when a key of this type can sign in this build, i.e. may be selected
         * for a repository. {@code false} for ed25519 — see the class comment.
         */
        public boolean canAuthenticate() {
            return _canAuthenticate;
        }

        /** @return {@code true} when the <i>Generate</i> dialog offers this type. */
        public boolean isOfferedForGeneration() {
            return _offeredForGeneration;
        }

        /**
         * @return the type named by {@code value} (case-insensitive), or {@link #UNKNOWN} for null,
         * empty or unrecognised input. Used for migration-safe reads.
         */
        public static Type fromString(final String value) {
            if (value != null) {
                for (final Type t : values()) {
                    if (t.name().equalsIgnoreCase(value.trim())) {
                        return t;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    @SerializedName("id")
    private String _id = "";

    @SerializedName("name")
    private String _name = "";

    @SerializedName("type")
    private Type _type = Type.UNKNOWN;

    @SerializedName("bits")
    private int _bits = 0;

    @SerializedName("publicKeyLine")
    private String _publicKeyLine = "";

    @SerializedName("fingerprintSha256")
    private String _fingerprintSha256 = "";

    @SerializedName("createdEpoch")
    private long _createdEpoch = 0L;

    @SerializedName("hasPassphrase")
    private boolean _hasPassphrase = false;

    /** Required by Gson; the field initializers above are the defaults for keys missing from JSON. */
    public GitSshKey() {
    }

    /**
     * @param id                store-assigned identifier, also the name of the key's folder
     * @param name              what the user typed; shown in every list
     * @param type              key algorithm
     * @param bits              key size in bits, 0 when unknown
     * @param publicKeyLine     the OpenSSH one-liner {@code ssh-rsa AAAA… comment}
     * @param fingerprintSha256 {@code SHA256:…}, the spelling GitHub and {@code ssh-keygen -lf} show
     * @param createdEpoch      when the key was generated or imported, milliseconds since epoch
     * @param hasPassphrase     {@code true} when the <i>stored</i> private key needs a passphrase
     *                          before it can be used
     */
    public GitSshKey(final String id, final String name, final Type type, final int bits,
                     final String publicKeyLine, final String fingerprintSha256,
                     final long createdEpoch, final boolean hasPassphrase) {
        _id = id == null ? "" : id;
        _name = name == null ? "" : name.trim();
        _type = type == null ? Type.UNKNOWN : type;
        _bits = Math.max(0, bits);
        _publicKeyLine = publicKeyLine == null ? "" : publicKeyLine.trim();
        _fingerprintSha256 = fingerprintSha256 == null ? "" : fingerprintSha256.trim();
        _createdEpoch = Math.max(0L, createdEpoch);
        _hasPassphrase = hasPassphrase;
    }

    /** The identifier {@link net.gsantner.markor.git.GitRepoConfig#getSshKeyId()} refers to. */
    public String getId() {
        return _id == null ? "" : _id;
    }

    /** The name shown in the UI; falls back to the fingerprint when it was never set. */
    public String getName() {
        if (_name != null && !_name.trim().isEmpty()) {
            return _name.trim();
        }
        return getFingerprintSha256();
    }

    public Type getType() {
        return _type == null ? Type.UNKNOWN : _type;
    }

    /** @return the key size in bits, or 0 when the format did not say */
    public int getBits() {
        return Math.max(0, _bits);
    }

    /** @return the OpenSSH public-key line to paste into a hosting service, never empty for a stored key */
    public String getPublicKeyLine() {
        return _publicKeyLine == null ? "" : _publicKeyLine;
    }

    /** @return {@code SHA256:…}; comparable by eye with what GitHub shows next to the key */
    public String getFingerprintSha256() {
        return _fingerprintSha256 == null ? "" : _fingerprintSha256;
    }

    public long getCreatedEpoch() {
        return Math.max(0L, _createdEpoch);
    }

    /**
     * @return {@code true} when the stored private key is encrypted with a passphrase, so an
     * operation using it has to be given that passphrase as well. Keys generated in the app are
     * not; imported ones keep whatever protection their file had.
     */
    public boolean hasPassphrase() {
        return _hasPassphrase;
    }

    /** @return {@code true} when this key can sign in this build; see {@link Type#canAuthenticate()} */
    public boolean canAuthenticate() {
        return getType().canAuthenticate();
    }

    /** @return {@code true} when the entry has the parts every stored key must have */
    public boolean isValid() {
        return !getId().isEmpty() && !getPublicKeyLine().isEmpty() && !getFingerprintSha256().isEmpty();
    }

    /**
     * @return {@code RSA 4096 · SHA256:…}, the one-line description the key lists and the settings
     * summary show
     */
    public String describe() {
        final String size = getBits() > 0 ? " " + getBits() : "";
        return getType().name() + size + " · " + getFingerprintSha256();
    }

    /**
     * Repair an entry that came out of JSON written by another version: replace nulls with the
     * documented defaults and trim the strings.
     */
    GitSshKey normalize() {
        _id = _id == null ? "" : _id.trim();
        _name = _name == null ? "" : _name.trim();
        _type = _type == null ? Type.UNKNOWN : _type;
        _bits = Math.max(0, _bits);
        _publicKeyLine = _publicKeyLine == null ? "" : _publicKeyLine.trim();
        _fingerprintSha256 = _fingerprintSha256 == null ? "" : _fingerprintSha256.trim();
        _createdEpoch = Math.max(0L, _createdEpoch);
        return this;
    }

    /** Identity is the id: two entries with the same id are the same key. */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GitSshKey)) {
            return false;
        }
        return getId().equals(((GitSshKey) o).getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    /** Safe to log: id, type and fingerprint are all public information. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "GitSshKey{id='%s', type=%s, bits=%d, fingerprint='%s', hasPassphrase=%s}",
                getId(), getType(), getBits(), getFingerprintSha256(), hasPassphrase());
    }
}
