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

/**
 * The checklist behind the commit dialog: the rows of a {@link GitService#status} result plus which of
 * them are checked. Plain Java so the selection rules are unit tested without a RecyclerView.
 * <p>
 * Rows keep the order {@code status} produced (sorted by path). Every row that can be committed starts
 * out checked. {@link GitStatusEntry.Kind#CONFLICT} rows are shown but can never be checked: the commit
 * contract refuses a repository in a MERGING/REBASING state, so offering them would only produce a
 * failure ({@code continueAfterConflictResolution} is the way out of that state, not this dialog).
 */
public final class GitCommitSelection {

    private final List<GitStatusEntry> _entries;
    private final boolean[] _checked;

    /**
     * @param entries the status rows to show, {@code null} treated as empty; the list is copied
     */
    public GitCommitSelection(final List<GitStatusEntry> entries) {
        _entries = entries == null ? Collections.<GitStatusEntry>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(entries));
        _checked = new boolean[_entries.size()];
        for (int i = 0; i < _entries.size(); i++) {
            _checked[i] = isSelectable(i);
        }
    }

    /** @return the rows to render, in status order */
    public List<GitStatusEntry> getEntries() {
        return _entries;
    }

    public int size() {
        return _entries.size();
    }

    public boolean isEmpty() {
        return _entries.isEmpty();
    }

    public GitStatusEntry getEntry(final int position) {
        return _entries.get(position);
    }

    /** @return {@code true} when the row may be checked, i.e. it is not a conflict */
    public boolean isSelectable(final int position) {
        return _entries.get(position).getKind() != GitStatusEntry.Kind.CONFLICT;
    }

    public boolean isChecked(final int position) {
        return _checked[position];
    }

    /**
     * Checks or unchecks one row. A conflicting row stays unchecked whatever is asked of it.
     *
     * @return {@code true} when the state actually changed
     */
    public boolean setChecked(final int position, final boolean checked) {
        final boolean effective = checked && isSelectable(position);
        if (_checked[position] == effective) {
            return false;
        }
        _checked[position] = effective;
        return true;
    }

    /** Flips one row; a conflicting row stays unchecked. @return the state afterwards */
    public boolean toggle(final int position) {
        setChecked(position, !_checked[position]);
        return _checked[position];
    }

    /** Checks or unchecks every selectable row ("select all" / "select none"). */
    public void setAllChecked(final boolean checked) {
        for (int i = 0; i < _checked.length; i++) {
            setChecked(i, checked);
        }
    }

    public int getSelectedCount() {
        int count = 0;
        for (final boolean c : _checked) {
            if (c) {
                count++;
            }
        }
        return count;
    }

    /** @return how many rows could be checked at all (everything but the conflicts) */
    public int getSelectableCount() {
        int count = 0;
        for (int i = 0; i < _entries.size(); i++) {
            if (isSelectable(i)) {
                count++;
            }
        }
        return count;
    }

    /** @return {@code true} when every selectable row is checked (and there is at least one) */
    public boolean isAllSelected() {
        final int selectable = getSelectableCount();
        return selectable > 0 && getSelectedCount() == selectable;
    }

    public int getConflictCount() {
        return _entries.size() - getSelectableCount();
    }

    public boolean hasConflicts() {
        return getConflictCount() > 0;
    }

    /**
     * @return the repository-relative paths to hand to {@link GitService#commit}, in status order.
     * Never {@code null}; an empty list means "commit nothing", which validation rejects — it must not
     * be passed to {@code commit}, where {@code null}/empty means "everything".
     */
    public List<String> getSelectedPaths() {
        final List<String> paths = new ArrayList<>(_checked.length);
        for (int i = 0; i < _entries.size(); i++) {
            if (_checked[i]) {
                paths.add(_entries.get(i).getPath());
            }
        }
        return paths;
    }

    /**
     * Restores a previous set of checked paths, for example after a rotation: every row whose path is in
     * {@code paths} and that is selectable becomes checked, every other row unchecked. Paths that are no
     * longer in the status are ignored.
     */
    public void restoreSelection(final Collection<String> paths) {
        for (int i = 0; i < _entries.size(); i++) {
            setChecked(i, paths != null && paths.contains(_entries.get(i).getPath()));
        }
    }
}
