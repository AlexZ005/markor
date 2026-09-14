/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classifies the lines of a unified diff so the diff viewer can style them, and caps very large
 * diffs so rendering them never freezes the UI.
 * <p>
 * Plain Java with no Android types: the interesting part - which line is an addition, a deletion, a
 * hunk header, a file header or plain context - is decided here and unit tested on the JVM, while
 * {@link DiffViewerActivity} only turns the resulting {@link Line}s into spans.
 * <p>
 * The formatter never reflows or re-indents: {@link Formatted#getText()} joined with '\n' is exactly
 * the input (up to the truncation point), so the offsets of every {@link Line} address the text the
 * viewer displays.
 */
public final class DiffTextFormatter {

    /** How one line of a unified diff should be displayed. */
    public enum LineKind {
        /** {@code diff --git ...}, {@code --- a/x}, {@code +++ b/x} - the start of a file's diff. */
        FILE_HEADER,
        /** {@code index ab12..cd34}, {@code new file mode ...}, {@code rename from ...}, {@code Binary files ...}. */
        META,
        /** {@code @@ -1,7 +1,9 @@ ...} - the start of a hunk. */
        HUNK_HEADER,
        /** An added line ({@code +}). */
        ADDED,
        /** A removed line ({@code -}). */
        REMOVED,
        /** An unchanged line (leading space, or empty). */
        CONTEXT,
        /** The footer appended in place of the lines that were dropped; never part of the diff itself. */
        TRUNCATION_FOOTER
    }

    /**
     * Lines rendered before the diff is cut off and the truncation footer is appended.
     * <p>
     * The cost is the TextView's, not this class's: the whole diff becomes one {@code StaticLayout},
     * which measures every line on the main thread. Measured on an API 26 emulator (12sp monospace),
     * building and setting the spannable took ~100ms at 5000 lines, 236ms at 8000, 473ms at 20000 and
     * 1712ms at 40000, and the layout after it janked for seconds at the top two. Everything still
     * renders correctly at 20000 - this is a responsiveness limit, not a rendering one - but a diff
     * that long is past the point of being read in a viewer, so 5000 buys a screen that appears at
     * once and a footer that says what was left out.
     */
    public static final int DEFAULT_MAX_LINES = 5000;

    /**
     * Fallback footer template, used when the caller passes none. Arguments are the number of lines
     * shown and the number of lines the diff has in total, in that order.
     */
    public static final String DEFAULT_TRUNCATION_FOOTER = "Diff truncated: showing %1$d of %2$d lines";

    /** One classified line and where it sits in {@link Formatted#getText()}. Immutable. */
    public static final class Line {
        private final LineKind _kind;
        private final int _start;
        private final int _end;

        Line(final LineKind kind, final int start, final int end) {
            _kind = kind;
            _start = start;
            _end = end;
        }

        public LineKind getKind() {
            return _kind;
        }

        /** @return offset of the first character of the line in {@link Formatted#getText()} */
        public int getStart() {
            return _start;
        }

        /** @return offset one past the last character of the line, excluding the line break */
        public int getEnd() {
            return _end;
        }

        public int length() {
            return _end - _start;
        }

        @Override
        public String toString() {
            return _kind + "[" + _start + "," + _end + "]";
        }
    }

    /** Result of {@link #format}: the text to display plus one {@link Line} per line of it. */
    public static final class Formatted {
        private final String _text;
        private final List<Line> _lines;
        private final boolean _truncated;
        private final int _shownLines;
        private final int _totalLines;

        Formatted(final String text, final List<Line> lines, final boolean truncated,
                  final int shownLines, final int totalLines) {
            _text = text;
            _lines = Collections.unmodifiableList(lines);
            _truncated = truncated;
            _shownLines = shownLines;
            _totalLines = totalLines;
        }

        /** @return the text to put into the TextView; '\n' separated, without a trailing line break */
        public String getText() {
            return _text;
        }

        /** @return one entry per line of {@link #getText()}, including the truncation footer */
        public List<Line> getLines() {
            return _lines;
        }

        /** @return {@code true} when the diff was cut off and a {@link LineKind#TRUNCATION_FOOTER} was appended */
        public boolean isTruncated() {
            return _truncated;
        }

        /** @return number of diff lines actually rendered, footer excluded */
        public int getShownLines() {
            return _shownLines;
        }

        /** @return number of lines the input diff has */
        public int getTotalLines() {
            return _totalLines;
        }

        public boolean isEmpty() {
            return _text.isEmpty();
        }
    }

    private DiffTextFormatter() {
    }

    /** Formats with {@link #DEFAULT_MAX_LINES} and {@link #DEFAULT_TRUNCATION_FOOTER}. */
    public static Formatted format(final String unified) {
        return format(unified, DEFAULT_MAX_LINES, DEFAULT_TRUNCATION_FOOTER);
    }

    /**
     * Splits {@code unified} into lines, classifies each of them and stops after {@code maxLines}.
     *
     * @param unified         unified diff text as {@code GitDiff#getUnified()} returns it; {@code null} is treated as empty
     * @param maxLines        maximum number of diff lines to render, &gt; 0
     * @param footerTemplate  {@link java.util.Formatter} template for the truncation footer, taking the
     *                        number of shown lines and the total number of lines; {@code null} uses
     *                        {@link #DEFAULT_TRUNCATION_FOOTER}
     * @return the classified text, never {@code null}
     */
    public static Formatted format(final String unified, final int maxLines, final String footerTemplate) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be > 0, was " + maxLines);
        }

        final String diff = unified == null ? "" : unified;
        if (diff.isEmpty()) {
            return new Formatted("", new ArrayList<Line>(), false, 0, 0);
        }

        final List<Line> lines = new ArrayList<>();
        final StringBuilder text = new StringBuilder(diff.length());
        int shown = 0;
        int total = 0;
        int from = 0;
        boolean inFileHeader = false;

        // Walk the input line by line. A trailing '\n' does not start another (empty) line.
        while (from <= diff.length()) {
            final int nl = diff.indexOf('\n', from);
            final int to = nl < 0 ? diff.length() : nl;
            total++;

            final LineKind kind = classify(diff, from, to, inFileHeader);
            inFileHeader = nextInFileHeader(inFileHeader, kind, diff, from, to);

            if (shown < maxLines) {
                if (shown > 0) {
                    text.append('\n');
                }
                final int start = text.length();
                text.append(diff, from, to);
                lines.add(new Line(kind, start, text.length()));
                shown++;
            }

            if (nl < 0) {
                break;
            }
            from = nl + 1;
            if (from == diff.length()) {
                break; // trailing newline, no further line
            }
        }

        final boolean truncated = total > shown;
        if (truncated) {
            text.append('\n');
            final int start = text.length();
            final String template = footerTemplate == null ? DEFAULT_TRUNCATION_FOOTER : footerTemplate;
            text.append(String.format(template, shown, total));
            lines.add(new Line(LineKind.TRUNCATION_FOOTER, start, text.length()));
        }

        return new Formatted(text.toString(), lines, truncated, shown, total);
    }

    /**
     * Cuts the section of one file out of a multi-file unified diff.
     * <p>
     * Needed because {@code GitService.diffForCommit} has no path argument: the commit detail screen
     * asks for the whole commit and the viewer shows the file the user tapped.
     *
     * @param unified full unified diff
     * @param path    repository-relative path; leading slashes and backslashes are tolerated
     * @return the {@code diff --git} section belonging to {@code path} (without a trailing line break),
     * or an empty string when the diff does not touch that file
     */
    public static String sliceFile(final String unified, final String path) {
        if (unified == null || unified.isEmpty() || path == null) {
            return "";
        }
        final String wanted = normalizePath(path);
        if (wanted.isEmpty()) {
            return "";
        }

        final String[] lines = unified.split("\n", -1);
        final StringBuilder section = new StringBuilder();
        boolean sectionMatches = false;
        boolean inHeader = false;
        final StringBuilder out = new StringBuilder();

        for (int i = 0; i <= lines.length; i++) {
            final String line = i < lines.length ? lines[i] : null;
            final boolean startsFile = line != null && (line.startsWith("diff --git ") || line.startsWith("diff --cc "));

            if (line == null || startsFile) {
                if (sectionMatches && section.length() > 0) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(section);
                }
                section.setLength(0);
                sectionMatches = false;
                inHeader = startsFile;
                if (line == null) {
                    break;
                }
            }

            if (startsFile) {
                sectionMatches = mentionsPath(line.substring(line.indexOf(' ', 5) + 1), wanted);
            } else if (inHeader && line.startsWith("@@")) {
                inHeader = false;
            } else if (inHeader && (line.startsWith("--- ") || line.startsWith("+++ "))) {
                sectionMatches |= mentionsPath(line.substring(4), wanted);
            }

            if (section.length() > 0) {
                section.append('\n');
            }
            section.append(line);
        }

        // Trailing empty line from the split of a diff ending in '\n'
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.substring(0, end);
    }

    /** @return {@code true} when {@code candidates} (a/x b/y, or a single ---/+++ path) names {@code wanted} */
    private static boolean mentionsPath(final String candidates, final String wanted) {
        for (final String raw : candidates.split(" ")) {
            final String candidate = stripDiffPrefix(raw);
            if (!candidate.isEmpty() && candidate.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Strips git's {@code a/} / {@code b/} working-tree prefixes and surrounding quotes. */
    private static String stripDiffPrefix(final String raw) {
        String s = raw.trim();
        if (s.length() > 1 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        if (s.equals("/dev/null")) {
            return "";
        }
        if (s.startsWith("a/") || s.startsWith("b/")) {
            s = s.substring(2);
        }
        return normalizePath(s);
    }

    /** The path spelling the slicer compares on: forward slashes, no leading separator. */
    static String normalizePath(final String path) {
        String s = path == null ? "" : path.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Convenience for tests and callers holding a single line. */
    static LineKind classify(final String line, final boolean inFileHeader) {
        return classify(line, 0, line.length(), inFileHeader);
    }

    /**
     * Classifies the line {@code diff[from, to)}.
     *
     * @param inFileHeader {@code true} while between a {@code diff --git} line and the first {@code @@}
     *                     of that file. Only there do {@code ---} and {@code +++} mean "file header";
     *                     inside a hunk they are a removed {@code --} and an added {@code ++} line,
     *                     which markdown front matter and rulers produce all the time.
     */
    static LineKind classify(final CharSequence diff, final int from, final int to, final boolean inFileHeader) {
        if (to <= from) {
            return LineKind.CONTEXT; // empty line inside a hunk (git writes context lines as " x", but be lenient)
        }

        if (startsWith(diff, from, to, "diff --git ") || startsWith(diff, from, to, "diff --cc ")) {
            return LineKind.FILE_HEADER;
        }
        if (startsWith(diff, from, to, "@@")) {
            return LineKind.HUNK_HEADER;
        }
        if (inFileHeader && (startsWith(diff, from, to, "+++") || startsWith(diff, from, to, "---"))) {
            return LineKind.FILE_HEADER;
        }

        switch (diff.charAt(from)) {
            case '+':
                return LineKind.ADDED;
            case '-':
                return LineKind.REMOVED;
            case '\\':
                return LineKind.META; // "\ No newline at end of file"
            default:
                break;
        }

        for (final String prefix : META_PREFIXES) {
            if (startsWith(diff, from, to, prefix)) {
                return LineKind.META;
            }
        }
        return LineKind.CONTEXT;
    }

    /**
     * The header section starts at {@code diff --git} and ends at the first hunk of that file, so
     * that {@code ---}/{@code +++} are only read as file headers where git writes them.
     */
    private static boolean nextInFileHeader(final boolean current, final LineKind kind,
                                            final CharSequence diff, final int from, final int to) {
        if (kind == LineKind.HUNK_HEADER) {
            return false;
        }
        if (kind == LineKind.FILE_HEADER && (startsWith(diff, from, to, "diff --git ") || startsWith(diff, from, to, "diff --cc "))) {
            return true;
        }
        return current;
    }

    private static final String[] META_PREFIXES = {
            "index ", "new file mode ", "deleted file mode ", "old mode ", "new mode ",
            "similarity index ", "dissimilarity index ", "rename from ", "rename to ",
            "copy from ", "copy to ", "Binary files ", "GIT binary patch",
    };

    private static boolean startsWith(final CharSequence s, final int from, final int to, final String prefix) {
        final int n = prefix.length();
        if (to - from < n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (s.charAt(from + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
