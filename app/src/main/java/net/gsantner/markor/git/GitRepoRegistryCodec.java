/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the repository list into the single JSON string that is persisted in the app settings,
 * and back. Plain Java (no Android imports) so it is unit testable on the JVM.
 * <p>
 * The stored form is an object, so that future keys can be added next to the list:
 * <pre>{"version":1,"repositories":[{"path":"/storage/emulated/0/notes", ...}]}</pre>
 * A bare JSON array is also accepted when reading, so a format written before the envelope
 * existed still loads.
 * <p>
 * Reading is migration-safe by contract: unknown keys are ignored, missing keys fall back to the
 * defaults declared in {@link GitRepoConfig}, entries without a path are dropped, duplicate paths
 * keep the first entry, and anything that does not parse yields an empty list instead of throwing.
 * A corrupt string is logged once per process and never with its content, because a repository URL
 * may carry credentials.
 */
public final class GitRepoRegistryCodec {

    /**
     * Version written into new payloads. Bump only together with a documented migration.
     */
    public static final int VERSION = 1;

    private static final Logger LOGGER = Logger.getLogger("net.gsantner.markor.git");
    private static final AtomicBoolean CORRUPT_LOGGED = new AtomicBoolean(false);

    private GitRepoRegistryCodec() {
    }

    /**
     * Envelope written by {@link #toJson(Collection)}. Gson reads it back field by field, so an
     * unknown key added by a newer version is simply ignored here.
     */
    private static final class Envelope {
        @SerializedName("version")
        int version = VERSION;

        @SerializedName("repositories")
        List<GitRepoConfig> repositories = new ArrayList<>();
    }

    /**
     * @param repos repositories to persist; null entries and entries without a path are skipped
     * @return a JSON string that {@link #fromJson(String)} reads back, never null
     */
    public static String toJson(final Collection<GitRepoConfig> repos) {
        final Envelope envelope = new Envelope();
        if (repos != null) {
            for (final GitRepoConfig repo : repos) {
                if (repo != null && repo.isValid()) {
                    envelope.repositories.add(repo);
                }
            }
        }
        return new Gson().toJson(envelope);
    }

    /**
     * @param json a string previously written by {@link #toJson(Collection)}, a bare JSON array of
     *             repositories, or anything at all
     * @return the repositories it contains, in stored order; an empty mutable list if the string is
     * null, empty, not JSON, or does not contain a repository list. Never throws.
     */
    public static List<GitRepoConfig> fromJson(final String json) {
        final List<GitRepoConfig> result = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }

        final JsonArray array;
        try {
            final JsonElement root = JsonParser.parseString(json);
            if (root != null && root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root != null && root.isJsonObject()) {
                final JsonObject obj = root.getAsJsonObject();
                final JsonElement repos = obj.get("repositories");
                if (repos == null || repos.isJsonNull()) {
                    // A valid object without the list: an empty registry, not corruption
                    return result;
                }
                if (!repos.isJsonArray()) {
                    return logCorruptAndReturnEmpty(result, "'repositories' is not an array");
                }
                array = repos.getAsJsonArray();
            } else {
                return logCorruptAndReturnEmpty(result, "root is not an object or array");
            }
        } catch (final RuntimeException e) {
            return logCorruptAndReturnEmpty(result, e.getClass().getSimpleName());
        }

        final Gson gson = new Gson();
        final Set<String> seenPaths = new HashSet<>();
        for (final JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue; // Tolerate a stray null or scalar inside the list
            }
            final GitRepoConfig repo;
            try {
                repo = gson.fromJson(element, GitRepoConfig.class);
            } catch (final RuntimeException e) {
                logCorrupt("entry could not be read: " + e.getClass().getSimpleName());
                continue;
            }
            if (repo == null) {
                continue;
            }
            repo.normalize();
            if (repo.isValid() && seenPaths.add(repo.getPath())) {
                result.add(repo);
            }
        }
        return result;
    }

    private static List<GitRepoConfig> logCorruptAndReturnEmpty(final List<GitRepoConfig> empty, final String reason) {
        logCorrupt(reason);
        empty.clear();
        return empty;
    }

    /**
     * Logs at most once per process, and never the stored string itself.
     */
    private static void logCorrupt(final String reason) {
        if (CORRUPT_LOGGED.compareAndSet(false, true)) {
            LOGGER.log(Level.WARNING, "Stored git repository list is unreadable and was ignored (" + reason + ")");
        }
    }
}
