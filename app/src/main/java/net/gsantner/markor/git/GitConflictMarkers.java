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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The one definition of "this file still carries git conflict markers", shared by the core layer
 * ({@code continueAfterConflictResolution} refuses to commit such a file) and by the Git tab's
 * <i>Mark resolved and commit</i> button (which names the files before anything is touched).
 * <p>
 * A marker line is exactly seven {@code <} or {@code >} at the start of a line, followed by a space, a
 * tab or the end of the line — the form git writes ({@code <<<<<<< HEAD}, {@code >>>>>>> origin/main}).
 * The separator {@code =======} is deliberately <b>not</b> a marker: Markor edits Markdown, where a
 * line of equals signs is the setext underline of a heading, and a note with such a heading must be
 * committable. Once both {@code <<<<<<<} and {@code >>>>>>>} are gone the file is considered resolved.
 * <p>
 * Plain Java, tested on the JVM.
 */
public final class GitConflictMarkers {

    /** Files larger than this are not scanned; they are assumed resolved (a note is never this big). */
    public static final long MAX_SCAN_BYTES = 8L * 1024 * 1024;

    private GitConflictMarkers() {
    }

    /**
     * @param line one line of a file, without its line terminator
     * @return {@code true} when the line is a {@code <<<<<<<} or {@code >>>>>>>} conflict marker
     */
    public static boolean isMarkerLine(final String line) {
        if (line == null || line.length() < 7) {
            return false;
        }
        final char c = line.charAt(0);
        if (c != '<' && c != '>') {
            return false;
        }
        for (int i = 1; i < 7; i++) {
            if (line.charAt(i) != c) {
                return false;
            }
        }
        if (line.length() == 7) {
            return true;
        }
        final char next = line.charAt(7);
        return next == ' ' || next == '\t';
    }

    /**
     * @param text the whole content of a file
     * @return {@code true} when any line of {@code text} {@link #isMarkerLine is a marker}
     */
    public static boolean containsMarkers(final CharSequence text) {
        if (text == null) {
            return false;
        }
        final int length = text.length();
        int lineStart = 0;
        while (lineStart <= length) {
            int lineEnd = lineStart;
            while (lineEnd < length && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            if (isMarkerLine(text.subSequence(lineStart, lineEnd).toString())) {
                return true;
            }
            if (lineEnd >= length) {
                break;
            }
            // Skip the terminator; treat \r\n as one.
            lineStart = lineEnd + (text.charAt(lineEnd) == '\r' && lineEnd + 1 < length && text.charAt(lineEnd + 1) == '\n' ? 2 : 1);
        }
        return false;
    }

    /**
     * Scans the given files of a working tree for marker lines.
     *
     * @param workTree root folder of the working tree
     * @param paths    repository-relative paths, typically the conflicted files of a stopped merge or rebase
     * @return those of {@code paths} whose file still has a marker line, in the given order. A path that
     * is no longer a file (the user resolved the conflict by deleting it) or that exceeds
     * {@link #MAX_SCAN_BYTES} is not reported.
     * @throws IOException when a file cannot be read; the caller decides whether that blocks the commit
     */
    public static List<String> scan(final File workTree, final Collection<String> paths) throws IOException {
        final List<String> marked = new ArrayList<>();
        if (workTree == null || paths == null) {
            return marked;
        }
        for (final String path : paths) {
            if (path == null) {
                continue;
            }
            final File file = GitPaths.resolveInside(workTree, path);
            if (file == null || !file.isFile() || file.length() > MAX_SCAN_BYTES) {
                continue;
            }
            if (fileHasMarkers(file)) {
                marked.add(path);
            }
        }
        return marked;
    }

    private static boolean fileHasMarkers(final File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isMarkerLine(line)) {
                    return true;
                }
            }
        }
        return false;
    }
}
