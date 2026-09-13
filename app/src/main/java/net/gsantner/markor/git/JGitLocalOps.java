/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local repository operations behind {@link JGitService}: init, status, log, diff, commit.
 * Owned by lane <b>git-core-local</b> (roadmap task 2.2). The contract for each method is the
 * Javadoc of the corresponding {@link GitService} method.
 * <p>
 * Helpers available from this package: {@link JGitRepos#open(File)} opens the repository from any
 * path inside it (throws {@code RepositoryNotFoundException} otherwise), {@link JGitRepos#describe}
 * builds a {@link GitRepoInfo}, {@link JGitErrors#map} turns a caught exception into the right
 * {@link GitResult}, and {@link JGitProgressMonitor} adapts a {@link GitProgress} for JGit commands.
 */
final class JGitLocalOps {

    /** Order in which two classifications for the same path are resolved; the higher rank wins. */
    private static int rank(final GitStatusEntry.Kind kind) {
        switch (kind) {
            case UNTRACKED:
                return 0;
            case MODIFIED:
                return 1;
            case ADDED:
                return 2;
            case DELETED:
                return 3;
            default: // CONFLICT
                return 4;
        }
    }

    private static final Comparator<GitStatusEntry> BY_PATH = new Comparator<GitStatusEntry>() {
        @Override
        public int compare(final GitStatusEntry a, final GitStatusEntry b) {
            return a.getPath().compareTo(b.getPath());
        }
    };

    GitResult<GitRepoInfo> init(final File dir, final GitProgress rawProgress) {
        final GitProgress progress = orNone(rawProgress);
        if (dir == null) {
            return GitResult.failed("No folder given");
        }
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        if (new File(dir, Constants.DOT_GIT).exists()) {
            return GitResult.failed("Already a git repository: " + dir.getPath());
        }
        if (dir.exists() && !dir.isDirectory()) {
            return GitResult.failed("Not a folder: " + dir.getPath());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return GitResult.failed("Cannot create folder: " + dir.getPath());
        }
        progress.onTaskBegin("Initializing repository", GitProgress.UNKNOWN);
        try (Git git = Git.init().setDirectory(dir).call()) {
            return GitResult.ok(JGitRepos.describe(git.getRepository()));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, dir);
        } finally {
            progress.onTaskEnd("Initializing repository");
        }
    }

    GitResult<List<GitStatusEntry>> status(final File repoDir, final GitProgress rawProgress) {
        final GitProgress progress = orNone(rawProgress);
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        progress.onTaskBegin("Reading status", GitProgress.UNKNOWN);
        try (Repository repo = JGitRepos.open(repoDir); Git git = new Git(repo)) {
            return GitResult.ok(toEntries(git.status().setProgressMonitor(new JGitProgressMonitor(progress)).call()));
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        } finally {
            progress.onTaskEnd("Reading status");
        }
    }

    /** Classifies the paths of a JGit {@link Status}; a path appears exactly once, conflicts win. */
    private static List<GitStatusEntry> toEntries(final Status status) {
        final Map<String, GitStatusEntry.Kind> byPath = new HashMap<>();
        classify(byPath, status.getUntracked(), GitStatusEntry.Kind.UNTRACKED);
        classify(byPath, status.getModified(), GitStatusEntry.Kind.MODIFIED);
        classify(byPath, status.getChanged(), GitStatusEntry.Kind.MODIFIED);
        classify(byPath, status.getAdded(), GitStatusEntry.Kind.ADDED);
        classify(byPath, status.getRemoved(), GitStatusEntry.Kind.DELETED);
        classify(byPath, status.getMissing(), GitStatusEntry.Kind.DELETED);
        classify(byPath, status.getConflicting(), GitStatusEntry.Kind.CONFLICT);

        final List<GitStatusEntry> entries = new ArrayList<>(byPath.size());
        for (final Map.Entry<String, GitStatusEntry.Kind> e : byPath.entrySet()) {
            entries.add(new GitStatusEntry(e.getKey(), e.getValue()));
        }
        Collections.sort(entries, BY_PATH);
        return entries;
    }

    private static void classify(final Map<String, GitStatusEntry.Kind> byPath, final Collection<String> paths, final GitStatusEntry.Kind kind) {
        if (paths == null) {
            return;
        }
        for (final String path : paths) {
            final GitStatusEntry.Kind previous = byPath.get(path);
            if (previous == null || rank(kind) > rank(previous)) {
                byPath.put(path, kind);
            }
        }
    }

    GitResult<List<GitCommitInfo>> log(final File repoDir, final int limit, final int skip, final GitProgress rawProgress) {
        final GitProgress progress = orNone(rawProgress);
        if (limit <= 0) {
            return GitResult.failed("limit must be greater than 0, was " + limit);
        }
        if (skip < 0) {
            return GitResult.failed("skip must not be negative, was " + skip);
        }
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        progress.onTaskBegin("Reading history", GitProgress.UNKNOWN);
        try (Repository repo = JGitRepos.open(repoDir)) {
            final ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) {
                return GitResult.ok(Collections.<GitCommitInfo>emptyList()); // repository without commits
            }
            final List<GitCommitInfo> commits = new ArrayList<>(Math.min(limit, 64));
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(head));
                int seen = 0;
                for (final RevCommit commit : walk) {
                    if (isCancelled(progress)) {
                        return GitResult.cancelled();
                    }
                    if (seen++ < skip) {
                        continue;
                    }
                    commits.add(toCommitInfo(commit));
                    if (commits.size() >= limit) {
                        break;
                    }
                }
            }
            return GitResult.ok(commits);
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        } finally {
            progress.onTaskEnd("Reading history");
        }
    }

    static GitCommitInfo toCommitInfo(final RevCommit commit) {
        final String full = commit.getFullMessage() == null ? "" : commit.getFullMessage();
        final String[] lines = full.split("\n", -1);
        final String subject = lines[0].trim();
        String body = "";
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                final StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    sb.append(lines[j]).append('\n');
                }
                body = sb.toString().trim();
                break;
            }
        }
        final PersonIdent author = commit.getAuthorIdent();
        return new GitCommitInfo(commit.name(), subject, body,
                author == null ? "" : author.getName(),
                author == null ? "" : author.getEmailAddress(),
                author == null ? commit.getCommitTime() : author.getWhen().getTime() / 1000L);
    }

    GitResult<GitDiff> diffWorkingTree(final File repoDir, final String path, final GitProgress progress) {
        return GitResult.failed("not implemented (task 2.2)");
    }

    GitResult<GitDiff> diffForCommit(final File repoDir, final String sha, final GitProgress progress) {
        return GitResult.failed("not implemented (task 2.2)");
    }

    GitResult<GitCommitInfo> commit(final File repoDir, final String message, final Collection<String> paths,
                                    final GitAuthor author, final GitProgress progress) {
        return GitResult.failed("not implemented (task 2.2)");
    }

    private static GitProgress orNone(final GitProgress progress) {
        return progress == null ? GitProgress.NONE : progress;
    }

    private static boolean isCancelled(final GitProgress progress) {
        return progress != null && progress.isCancelled();
    }
}
