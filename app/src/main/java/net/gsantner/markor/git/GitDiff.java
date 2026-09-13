/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A diff: the full unified text (what {@code git diff} prints, for the diff viewer) plus one
 * summary row per changed file (for the commit detail screen). Immutable.
 */
public final class GitDiff {

    /** An empty diff (no changes). */
    public static final GitDiff EMPTY = new GitDiff("", Collections.<FileChange>emptyList());

    /** How one file changed. */
    public enum ChangeKind {ADDED, MODIFIED, DELETED, RENAMED, COPIED}

    /** Per-file summary line. Immutable. */
    public static final class FileChange {
        private final String _path;
        private final String _oldPath;
        private final ChangeKind _kind;
        private final int _linesAdded;
        private final int _linesDeleted;
        private final boolean _binary;

        /**
         * @param path         repository-relative path after the change (for DELETED: the deleted path)
         * @param oldPath      previous path for RENAMED/COPIED, otherwise {@code null}
         * @param kind         change kind
         * @param linesAdded   number of '+' lines (0 for binary)
         * @param linesDeleted number of '-' lines (0 for binary)
         * @param binary       {@code true} when the content is binary and no text diff is available
         */
        public FileChange(final String path, final String oldPath, final ChangeKind kind,
                          final int linesAdded, final int linesDeleted, final boolean binary) {
            _path = Objects.requireNonNull(path, "path");
            _oldPath = oldPath;
            _kind = Objects.requireNonNull(kind, "kind");
            _linesAdded = linesAdded;
            _linesDeleted = linesDeleted;
            _binary = binary;
        }

        public String getPath() {
            return _path;
        }

        /** @return previous path for renames and copies, otherwise {@code null} */
        public String getOldPath() {
            return _oldPath;
        }

        public ChangeKind getKind() {
            return _kind;
        }

        public int getLinesAdded() {
            return _linesAdded;
        }

        public int getLinesDeleted() {
            return _linesDeleted;
        }

        public boolean isBinary() {
            return _binary;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof FileChange)) return false;
            final FileChange that = (FileChange) o;
            return _path.equals(that._path) && Objects.equals(_oldPath, that._oldPath) && _kind == that._kind
                    && _linesAdded == that._linesAdded && _linesDeleted == that._linesDeleted && _binary == that._binary;
        }

        @Override
        public int hashCode() {
            return Objects.hash(_path, _oldPath, _kind, _linesAdded, _linesDeleted, _binary);
        }

        @Override
        public String toString() {
            return _kind + " " + (_oldPath != null ? _oldPath + " -> " : "") + _path
                    + (_binary ? " (binary)" : " +" + _linesAdded + " -" + _linesDeleted);
        }
    }

    private final String _unified;
    private final List<FileChange> _files;

    /**
     * @param unified full unified diff text with {@code diff --git} headers, '\n' line endings; empty when nothing changed
     * @param files   one entry per file, in the order they appear in the unified text
     */
    public GitDiff(final String unified, final Collection<FileChange> files) {
        _unified = unified == null ? "" : unified;
        _files = files == null || files.isEmpty()
                ? Collections.<FileChange>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(files));
    }

    /** @return the unified diff text; empty string when there are no changes */
    public String getUnified() {
        return _unified;
    }

    /** @return per-file summary, possibly empty */
    public List<FileChange> getFiles() {
        return _files;
    }

    public boolean isEmpty() {
        return _files.isEmpty() && _unified.isEmpty();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitDiff)) return false;
        final GitDiff that = (GitDiff) o;
        return _unified.equals(that._unified) && _files.equals(that._files);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_unified, _files);
    }

    @Override
    public String toString() {
        return "GitDiff{" + _files.size() + " files, " + _unified.length() + " chars}";
    }
}
