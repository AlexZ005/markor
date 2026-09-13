/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;

/**
 * Path normalization shared by the git core classes.
 * <p>
 * Repositories are identified by their working folder path. The same folder must map to the same
 * key no matter how it was spelled (trailing separator, relative path), so that the registry does
 * not hold duplicates and {@link GitTaskRunner} serializes operations on one repository.
 * <p>
 * This deliberately does not use {@link File#getCanonicalPath()}: it touches the file system, can
 * throw and resolves symlinks, which would make two legitimately different user selections collapse.
 */
final class GitPaths {
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
}
