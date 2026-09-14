/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JSON index of {@link GitSshKeyStore}: which keys exist and which one is the default
 * (roadmap task 8.1b). Plain Java, unit tested on the JVM, written the same way
 * {@link net.gsantner.markor.git.GitRepoRegistryCodec} writes the repository list.
 * <pre>{"version":1,"defaultKeyId":"k1a2b3","keys":[{"id":"k1a2b3","type":"RSA", ...}]}</pre>
 * The index holds public information only - names, types, public-key lines and fingerprints. The
 * private keys are separate files next to it, each encrypted with a Keystore-wrapped key.
 * <p>
 * Reading is migration-safe by contract: unknown keys are ignored, missing ones fall back to the
 * defaults declared in {@link GitSshKey}, incomplete entries are dropped, duplicate ids keep the
 * first entry, a default id naming no entry is cleared, and anything that does not parse yields an
 * empty index instead of throwing.
 */
public final class GitSshKeyIndexCodec {

    /** Version written into new payloads. Bump only together with a documented migration. */
    public static final int VERSION = 1;

    private static final Logger LOGGER = Logger.getLogger("net.gsantner.markor.git");

    private GitSshKeyIndexCodec() {
    }

    /** What the index file contains, after reading: the keys in stored order plus the default id. */
    public static final class Index {
        private final List<GitSshKey> _keys;
        private final String _defaultKeyId;

        public Index(final List<GitSshKey> keys, final String defaultKeyId) {
            _keys = keys == null ? new ArrayList<>() : keys;
            _defaultKeyId = defaultKeyId == null || defaultKeyId.trim().isEmpty() ? null : defaultKeyId.trim();
        }

        /** @return the keys, in stored order; a mutable list owned by the caller */
        public List<GitSshKey> getKeys() {
            return _keys;
        }

        /** @return the id of the default key, or {@code null} when there is none */
        public String getDefaultKeyId() {
            return _defaultKeyId;
        }
    }

    /** Envelope Gson writes and reads; an unknown key added by a newer version is ignored here. */
    private static final class Envelope {
        @SerializedName("version")
        int version = VERSION;

        @SerializedName("defaultKeyId")
        String defaultKeyId = null;

        @SerializedName("keys")
        List<GitSshKey> keys = new ArrayList<>();
    }

    /**
     * @param keys         keys to persist; null and incomplete entries are skipped
     * @param defaultKeyId id of the default key; dropped when no kept entry has it
     * @return a JSON string that {@link #fromJson(String)} reads back, never null
     */
    public static String toJson(final List<GitSshKey> keys, final String defaultKeyId) {
        final Envelope envelope = new Envelope();
        final Set<String> ids = new HashSet<>();
        if (keys != null) {
            for (final GitSshKey key : keys) {
                if (key != null && key.isValid() && ids.add(key.getId())) {
                    envelope.keys.add(key);
                }
            }
        }
        envelope.defaultKeyId = defaultKeyId != null && ids.contains(defaultKeyId.trim()) ? defaultKeyId.trim() : null;
        return new Gson().toJson(envelope);
    }

    /**
     * @param json a string previously written by {@link #toJson(List, String)}, or anything at all
     * @return the keys it contains and the default id; an empty index for null, empty or unreadable
     * input. Never throws.
     */
    public static Index fromJson(final String json) {
        final List<GitSshKey> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return new Index(result, null);
        }

        final JsonObject root;
        try {
            final JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) {
                return logCorrupt(result, "root is not an object");
            }
            root = parsed.getAsJsonObject();
        } catch (final RuntimeException e) {
            return logCorrupt(result, e.getClass().getSimpleName());
        }

        final JsonElement keys = root.get("keys");
        if (keys != null && !keys.isJsonNull()) {
            if (!keys.isJsonArray()) {
                return logCorrupt(result, "'keys' is not an array");
            }
            final Gson gson = new Gson();
            final Set<String> seen = new HashSet<>();
            final JsonArray array = keys.getAsJsonArray();
            for (final JsonElement element : array) {
                if (element == null || !element.isJsonObject()) {
                    continue; // Tolerate a stray null or scalar inside the list
                }
                GitSshKey key = null;
                try {
                    key = gson.fromJson(element, GitSshKey.class);
                } catch (final RuntimeException e) {
                    LOGGER.log(Level.WARNING, "An SSH key entry could not be read and was ignored ("
                            + e.getClass().getSimpleName() + ")");
                }
                if (key == null) {
                    continue;
                }
                key.normalize();
                if (key.isValid() && seen.add(key.getId())) {
                    result.add(key);
                }
            }
        }

        String defaultKeyId = null;
        final JsonElement stored = root.get("defaultKeyId");
        if (stored != null && stored.isJsonPrimitive()) {
            final String id = stored.getAsString() == null ? "" : stored.getAsString().trim();
            for (final GitSshKey key : result) {
                if (key.getId().equals(id)) {
                    defaultKeyId = id;
                    break;
                }
            }
        }
        return new Index(result, defaultKeyId);
    }

    /** Logs the reason, never the stored string: it is small, but it is still user data. */
    private static Index logCorrupt(final List<GitSshKey> empty, final String reason) {
        LOGGER.log(Level.WARNING, "The stored SSH key index is unreadable and was ignored (" + reason + ")");
        empty.clear();
        return new Index(empty, null);
    }
}
