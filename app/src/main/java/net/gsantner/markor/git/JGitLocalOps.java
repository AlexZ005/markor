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
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    GitResult<GitDiff> diffWorkingTree(final File repoDir, final String path, final GitProgress rawProgress) {
        final GitProgress progress = orNone(rawProgress);
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        progress.onTaskBegin(TASK_DIFF, GitProgress.UNKNOWN);
        try (Repository repo = JGitRepos.open(repoDir); ObjectReader reader = repo.newObjectReader()) {
            // HEAD (or the empty tree in a repository without commits) against the working tree, so that
            // staged and unstaged changes are shown together and untracked files appear as additions.
            final ObjectId headTree = repo.resolve(Constants.HEAD + "^{tree}");
            final AbstractTreeIterator oldTree = headTree == null ? new EmptyTreeIterator() : treeParser(reader, headTree);

            // The working tree is the second tree of the walk, hence index 1: without this, files matched
            // by .gitignore would show up as additions even though status() does not list them.
            TreeFilter filter = new NotIgnoredFilter(1);
            final String relative = normalizePath(path);
            if (relative != null) {
                filter = AndTreeFilter.create(PathFilter.create(relative), filter);
            }
            final GitDiff diff = computeDiff(repo, oldTree, new FileTreeIterator(repo), filter, false, progress);
            return diff == null ? GitResult.<GitDiff>cancelled() : GitResult.ok(diff);
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        } finally {
            progress.onTaskEnd(TASK_DIFF);
        }
    }

    GitResult<GitDiff> diffForCommit(final File repoDir, final String sha, final GitProgress rawProgress) {
        final GitProgress progress = orNone(rawProgress);
        if (sha == null || sha.trim().isEmpty()) {
            return GitResult.failed("No commit id given");
        }
        if (isCancelled(progress)) {
            return GitResult.cancelled();
        }
        progress.onTaskBegin(TASK_DIFF, GitProgress.UNKNOWN);
        try (Repository repo = JGitRepos.open(repoDir);
             ObjectReader reader = repo.newObjectReader();
             RevWalk walk = new RevWalk(reader)) {
            final ObjectId id = repo.resolve(sha.trim());
            if (id == null) {
                return GitResult.failed("No such commit: " + sha.trim());
            }
            final RevCommit commit;
            try {
                commit = walk.parseCommit(id);
            } catch (IncorrectObjectTypeException | MissingObjectException e) {
                return GitResult.failed("Not a commit: " + sha.trim());
            }
            final AbstractTreeIterator oldTree = commit.getParentCount() == 0
                    ? new EmptyTreeIterator() // root commit: compared with the empty tree, like "git show"
                    : treeParser(reader, walk.parseCommit(commit.getParent(0).getId()).getTree());
            final GitDiff diff = computeDiff(repo, oldTree, treeParser(reader, commit.getTree()), null, true, progress);
            return diff == null ? GitResult.<GitDiff>cancelled() : GitResult.ok(diff);
        } catch (Exception e) {
            return JGitErrors.map(e, progress, repoDir);
        } finally {
            progress.onTaskEnd(TASK_DIFF);
        }
    }

    /**
     * Formats the unified diff between two trees and collects the per-file summary.
     *
     * @return the diff, or {@code null} when {@code progress} was cancelled while formatting
     */
    private static GitDiff computeDiff(final Repository repo, final AbstractTreeIterator oldTree, final AbstractTreeIterator newTree,
                                       final TreeFilter filter, final boolean detectRenames, final GitProgress progress) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<GitDiff.FileChange> files = new ArrayList<>();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repo);
            formatter.setDetectRenames(detectRenames);
            if (filter != null) {
                formatter.setPathFilter(filter);
            }
            for (final DiffEntry entry : formatter.scan(oldTree, newTree)) {
                if (isCancelled(progress)) {
                    return null;
                }
                // toFileHeader() gives the edit list and tells binary content apart; format() writes the
                // hunks (or the "Binary files differ" line) for the same entry.
                files.add(toFileChange(entry, formatter.toFileHeader(entry)));
                formatter.format(entry);
            }
            formatter.flush();
        }
        return new GitDiff(new String(out.toByteArray(), StandardCharsets.UTF_8), files);
    }

    private static GitDiff.FileChange toFileChange(final DiffEntry entry, final FileHeader header) {
        final boolean binary = header.getPatchType() != FileHeader.PatchType.UNIFIED;
        int added = 0;
        int deleted = 0;
        if (!binary) {
            for (final Edit edit : header.toEditList()) {
                added += edit.getEndB() - edit.getBeginB();
                deleted += edit.getEndA() - edit.getBeginA();
            }
        }
        final GitDiff.ChangeKind kind;
        switch (entry.getChangeType()) {
            case ADD:
                kind = GitDiff.ChangeKind.ADDED;
                break;
            case DELETE:
                kind = GitDiff.ChangeKind.DELETED;
                break;
            case RENAME:
                kind = GitDiff.ChangeKind.RENAMED;
                break;
            case COPY:
                kind = GitDiff.ChangeKind.COPIED;
                break;
            default:
                kind = GitDiff.ChangeKind.MODIFIED;
                break;
        }
        final String path = kind == GitDiff.ChangeKind.DELETED ? entry.getOldPath() : entry.getNewPath();
        final String oldPath = kind == GitDiff.ChangeKind.RENAMED || kind == GitDiff.ChangeKind.COPIED ? entry.getOldPath() : null;
        return new GitDiff.FileChange(path, oldPath, kind, added, deleted, binary);
    }

    private static CanonicalTreeParser treeParser(final ObjectReader reader, final ObjectId treeId) throws IOException {
        final CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, treeId);
        return parser;
    }

    /** @return the repository-relative path with '/' separators, or {@code null} for "the whole tree" */
    private static String normalizePath(final String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? null : p;
    }

    GitResult<GitCommitInfo> commit(final File repoDir, final String message, final Collection<String> paths,
                                    final GitAuthor author, final GitProgress progress) {
        return GitResult.failed("not implemented (task 2.2)");
    }

    private static final String TASK_DIFF = "Computing diff";

    private static GitProgress orNone(final GitProgress progress) {
        return progress == null ? GitProgress.NONE : progress;
    }

    private static boolean isCancelled(final GitProgress progress) {
        return progress != null && progress.isCancelled();
    }
}
