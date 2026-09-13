/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.Objects;

/** Name and e-mail used as author and committer of commits made by this app. Immutable. */
public final class GitAuthor {
    private final String _name;
    private final String _email;

    /**
     * @param name  display name, not blank
     * @param email e-mail address, not blank (git requires one; it is not validated further)
     * @throws IllegalArgumentException when either is null or blank
     */
    public GitAuthor(final String name, final String email) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Author name must not be blank");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Author e-mail must not be blank");
        }
        _name = name.trim();
        _email = email.trim();
    }

    public String getName() {
        return _name;
    }

    public String getEmail() {
        return _email;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitAuthor)) return false;
        final GitAuthor that = (GitAuthor) o;
        return _name.equals(that._name) && _email.equals(that._email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_name, _email);
    }

    @Override
    public String toString() {
        return _name + " <" + _email + ">";
    }
}
