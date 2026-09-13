/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.Objects;

/**
 * One row of the "Changes" list: a file whose working-tree content differs from HEAD, or a file in
 * conflict. The index (staging area) is deliberately not exposed; the app commits by path.
 * Immutable.
 */
public final class GitStatusEntry {

    /** Classification of the change, in the letters the UI shows. */
    public enum Kind {
        /** M: tracked file whose content changed (staged or not). */
        MODIFIED,
        /** A: file added to the index that is not in HEAD. */
        ADDED,
        /** D: tracked file deleted from the working tree (staged or not). */
        DELETED,
        /** ?: file not tracked and not ignored. */
        UNTRACKED,
        /** Unmerged path after a conflicting merge or rebase; the file contains conflict markers. */
        CONFLICT
    }

    private final String _path;
    private final Kind _kind;

    /**
     * @param path repository-relative path with '/' separators, never starting with '/'
     * @param kind classification
     */
    public GitStatusEntry(final String path, final Kind kind) {
        _path = Objects.requireNonNull(path, "path");
        _kind = Objects.requireNonNull(kind, "kind");
    }

    /** @return repository-relative path, '/' separated */
    public String getPath() {
        return _path;
    }

    public Kind getKind() {
        return _kind;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitStatusEntry)) return false;
        final GitStatusEntry that = (GitStatusEntry) o;
        return _path.equals(that._path) && _kind == that._kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(_path, _kind);
    }

    @Override
    public String toString() {
        return _kind + " " + _path;
    }
}
