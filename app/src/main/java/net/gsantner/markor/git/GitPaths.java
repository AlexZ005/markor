/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;
import java.io.IOException;

/**
 * Path normalization and containment checks shared by the git classes.
 * <p>
 * Repositories are identified by their working folder path. The same folder must map to the same
 * key no matter how it was spelled (trailing separator, relative path), so that the registry does
 * not hold duplicates and {@link GitTaskRunner} serializes operations on one repository.
 * <p>
 * {@link #normalize(String)} deliberately does not use {@link File#getCanonicalPath()}: it touches the
 * file system, can throw and resolves symlinks, which would make two legitimately different user
 * selections collapse. {@link #resolveInside(File, String)} must resolve {@code ..}, so it does.
 */
public final class GitPaths {
    private GitPaths() {
    }

    /**
     * @return the absolute, separator-normalized form of {@code path}, or an empty string for
     * {@code null}, empty or blank input. Never returns null.
     */
    static String normalize(final String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return "";
        }
        try {
            p = new File(p).getAbsolutePath();
        } catch (final Exception ignored) {
            // Keep the trimmed input if the platform cannot build an absolute path
        }
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith(File.separator))) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Resolves a repository-relative path against the working folder, refusing anything that would
     * leave it.
     * <p>
     * Paths handed to the UI come out of the repository - {@code status}, a tree walk, a diff - and a
     * repository can be crafted: nothing in the object format stops a tree entry from being named
     * {@code ..}, and the repositories this app opens sit in the notebook folder on shared storage
     * where any app can write. Every place that turns such a path into a {@link File} it then opens,
     * writes or deletes goes through here, so a hostile repository cannot reach
     * {@code /data/data/net.gsantner.markor} or the rest of the notebook folder.
     * <p>
     * Both sides are canonicalized, so {@code /sdcard} being a symlink to {@code /storage/emulated/0}
     * does not by itself fail the check.
     *
     * @param root the repository working folder
     * @param path a repository-relative path, as git spells it (forward slashes); used verbatim
     * @return the file inside {@code root}, or {@code null} when the path is empty, absolute, or
     * resolves to {@code root} itself or anywhere outside it. A {@code null} is <b>not</b> a licence
     * to fall through to a destructive branch: callers skip the path.
     */
    public static File resolveInside(final File root, final String path) {
        if (root == null || path == null) {
            return null;
        }
        // The path is never trimmed: a leading or trailing space is legal in a git path name, and
        // rewriting it here would hand the caller a different file than the repository named.
        if (path.isEmpty() || new File(path).isAbsolute()) {
            return null;
        }
        final File candidate = new File(root, path);
        try {
            final String rootPath = root.getCanonicalPath();
            final String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            return candidate.getCanonicalPath().startsWith(prefix) ? candidate : null;
        } catch (final IOException | SecurityException e) {
            return null;
        }
    }
}
