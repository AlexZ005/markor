/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.util.Objects;

/** One commit as shown in the History list and the commit detail screen. Immutable. */
public final class GitCommitInfo {
    /** Length of {@link #getShortSha()}. */
    public static final int SHORT_SHA_LENGTH = 7;

    private final String _sha;
    private final String _subject;
    private final String _body;
    private final String _authorName;
    private final String _authorEmail;
    private final long _epochSeconds;

    /**
     * @param sha          full 40-hex object id
     * @param subject      first line of the message, trimmed (may be empty)
     * @param body         rest of the message after the first blank line, trimmed; empty when none
     * @param authorName   author name
     * @param authorEmail  author e-mail
     * @param epochSeconds author time as seconds since 1970-01-01T00:00:00Z
     */
    public GitCommitInfo(final String sha, final String subject, final String body,
                         final String authorName, final String authorEmail, final long epochSeconds) {
        _sha = Objects.requireNonNull(sha, "sha");
        _subject = subject == null ? "" : subject;
        _body = body == null ? "" : body;
        _authorName = authorName == null ? "" : authorName;
        _authorEmail = authorEmail == null ? "" : authorEmail;
        _epochSeconds = epochSeconds;
    }

    /** @return full 40-character hex id */
    public String getSha() {
        return _sha;
    }

    /** @return the first {@value #SHORT_SHA_LENGTH} characters of the id */
    public String getShortSha() {
        return _sha.length() <= SHORT_SHA_LENGTH ? _sha : _sha.substring(0, SHORT_SHA_LENGTH);
    }

    /** @return first message line, trimmed */
    public String getSubject() {
        return _subject;
    }

    /** @return message body without the subject, trimmed; empty string when there is none */
    public String getBody() {
        return _body;
    }

    public String getAuthorName() {
        return _authorName;
    }

    public String getAuthorEmail() {
        return _authorEmail;
    }

    /** @return author time in seconds since the Unix epoch (multiply by 1000 for {@code java.util.Date}) */
    public long getEpochSeconds() {
        return _epochSeconds;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof GitCommitInfo)) return false;
        final GitCommitInfo that = (GitCommitInfo) o;
        return _sha.equals(that._sha) && _subject.equals(that._subject) && _body.equals(that._body)
                && _authorName.equals(that._authorName) && _authorEmail.equals(that._authorEmail)
                && _epochSeconds == that._epochSeconds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(_sha, _subject, _body, _authorName, _authorEmail, _epochSeconds);
    }

    @Override
    public String toString() {
        return getShortSha() + " " + _subject + " (" + _authorName + ")";
    }
}
