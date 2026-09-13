/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps Markor's own data folder out of the user's repository. Markor stores snippets and templates in
 * {@code .app/} under the notebook folder (see {@code AppSettings#getSnippetsDirectory()}); when the
 * notebook folder — or a folder above it — is a git repository, that folder would otherwise be committed
 * along with the notes.
 * <p>
 * Plain Java on {@code java.io.File} so it is unit tested on the JVM, and deliberately textual: it looks
 * at the lines of the repository's own {@code .gitignore} rather than asking git whether a path is
 * ignored. That keeps "is it already handled?" and "what would I write?" the same question, and a rule
 * inherited from {@code .git/info/exclude} or a parent folder simply means the suggestion is shown once
 * and declined.
 */
public final class GitIgnoreHelper {

    /** Name of the file this helper reads and writes, in the repository root. */
    public static final String GITIGNORE = ".gitignore";

    /** The folder Markor keeps its own data in, relative to the notebook folder. */
    public static final String APP_FOLDER = ".app";

    /** The line written for a repository whose root is the notebook folder. */
    public static final String DEFAULT_ENTRY = ".app/";

    /** The comment written above the entry so the user can tell where the line came from. */
    public static final String COMMENT = "# Markor application data (snippets, templates)";

    private GitIgnoreHelper() {
    }

    /** @return the {@code .gitignore} of this repository, existing or not */
    public static File getGitIgnoreFile(final File repoRoot) {
        return new File(repoRoot, GITIGNORE);
    }

    /**
     * Works out the line that would ignore {@code appFolder} in the repository at {@code repoRoot}.
     *
     * @param repoRoot  working-tree root of the repository
     * @param appFolder Markor's {@code .app} folder, usually {@code <notebook>/.app}
     * @return a repository-relative pattern ending in {@code /}, e.g. {@code .app/} or
     * {@code notes/.app/}; {@code null} when the folder is not inside the repository, when either
     * argument is {@code null}, or when the folder <i>is</i> the repository root
     */
    public static String entryFor(final File repoRoot, final File appFolder) {
        if (repoRoot == null || appFolder == null) {
            return null;
        }
        final String root = canonical(repoRoot);
        final String app = canonical(appFolder);
        if (root == null || app == null || root.equals(app)) {
            return null;
        }
        final String prefix = root.endsWith(File.separator) ? root : root + File.separator;
        if (!app.startsWith(prefix)) {
            return null;
        }
        final String relative = app.substring(prefix.length()).replace(File.separatorChar, '/');
        return relative.isEmpty() ? null : relative + "/";
    }

    /**
     * @param repoRoot working-tree root of the repository
     * @param entry    a pattern as produced by {@link #entryFor(File, File)}
     * @return {@code true} when the repository's {@code .gitignore} already carries a line that ignores
     * this folder. Matching tolerates the spellings git treats alike for a folder — with and without a
     * leading {@code /}, with and without the trailing {@code /} — and ignores comments, blank lines and
     * surrounding whitespace. A negation ({@code !.app/}) does not count as ignored.
     */
    public static boolean isIgnored(final File repoRoot, final String entry) {
        final String wanted = normalizePattern(entry);
        if (repoRoot == null || wanted == null) {
            return false;
        }
        for (final String line : readLines(getGitIgnoreFile(repoRoot))) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (wanted.equals(normalizePattern(trimmed))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends the commented entry to the repository's {@code .gitignore}, creating the file when it does
     * not exist. Never writes the same entry twice, and never disturbs what is already in the file: a
     * missing final newline is added first so the existing last line stays intact, and a file that
     * already ends in a newline does not gain a blank line.
     *
     * @param repoRoot working-tree root of the repository
     * @param entry    a pattern as produced by {@link #entryFor(File, File)}
     * @return {@code true} when the entry was written, {@code false} when it was already there
     * @throws IOException when the file cannot be read or written
     */
    public static boolean append(final File repoRoot, final String entry) throws IOException {
        if (repoRoot == null || normalizePattern(entry) == null) {
            throw new IOException("No repository or no entry to append");
        }
        if (isIgnored(repoRoot, entry)) {
            return false;
        }
        final File file = getGitIgnoreFile(repoRoot);
        final StringBuilder sb = new StringBuilder();
        if (file.isFile() && file.length() > 0) {
            if (!endsWithNewline(file)) {
                sb.append('\n');
            }
            sb.append('\n');
        }
        sb.append(COMMENT).append('\n').append(entry.trim()).append('\n');
        try (OutputStream out = new FileOutputStream(file, true)) {
            out.write(sb.toString().getBytes(UTF8));
        }
        return true;
    }

    /**
     * The one-call form for the dialog: nothing to do when the folder is outside the repository or
     * already ignored.
     *
     * @return {@code true} when the user should be asked to add the entry
     */
    public static boolean shouldSuggest(final File repoRoot, final File appFolder) {
        final String entry = entryFor(repoRoot, appFolder);
        return entry != null && !isIgnored(repoRoot, entry);
    }

    // ---------------------------------------------------------------- internals

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Reduces a pattern to the form two spellings of the same folder share: no surrounding whitespace,
     * no leading {@code /}, no trailing {@code /}, {@code \} as {@code /}.
     *
     * @return {@code null} when there is nothing left, so callers can use it as a validity check
     */
    private static String normalizePattern(final String pattern) {
        if (pattern == null) {
            return null;
        }
        String p = pattern.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? null : p;
    }

    private static List<String> readLines(final File file) {
        final List<String> lines = new ArrayList<>();
        if (file == null || !file.isFile()) {
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Exception ignored) {
            // An unreadable .gitignore is treated as "no entry"; append() then fails visibly instead
        }
        return lines;
    }

    private static boolean endsWithNewline(final File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            final long length = raf.length();
            if (length == 0) {
                return true;
            }
            raf.seek(length - 1);
            final int last = raf.read();
            return last == '\n' || last == '\r';
        }
    }

    private static String canonical(final File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
