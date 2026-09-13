/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Everything the Git tab does with a repository, expressed as blocking calls that return typed
 * {@link GitResult}s. Pure Java: no Android types, so the implementation is tested on the JVM.
 * <p>
 * <b>Threading.</b> Every method blocks until the operation is finished and must not be called on the
 * main thread; the caller ({@code GitTaskRunner}) supplies the worker thread. The implementation is
 * stateless and safe to use from several threads for <i>different</i> repositories. Calls on the
 * <i>same</i> repository must be serialized by the caller (one queue per repository), otherwise
 * JGit lock files ({@code index.lock}) collide and calls fail.
 * <p>
 * <b>Repository argument.</b> Methods taking a {@code repoDir} accept any file or folder inside the
 * working tree; the implementation walks up to the folder containing {@code .git}. Bare repositories
 * are not supported and are reported as {@link GitResult.Kind#NOT_A_REPO}.
 * <p>
 * <b>Results.</b> Methods never throw for git or I/O failures; they return a non-OK {@link GitResult}.
 * Every method can return {@link GitResult.Kind#FAILED} (unexpected error, message explains) and, when
 * the given {@link GitProgress} reports cancellation, {@link GitResult.Kind#CANCELLED}. Methods that
 * take a repository can return {@link GitResult.Kind#NOT_A_REPO}. Further kinds are listed per method.
 * <p>
 * <b>Credentials.</b> Remote methods take a {@link GitCredentialsSource}; credentials are requested
 * per call, handed to JGit for the duration of the call only, and never written to the repository
 * configuration or to logs. URLs containing a password are rejected with {@link GitResult.Kind#FAILED}.
 * <p>
 * <b>Progress.</b> Long operations report tasks and percentages through {@link GitProgress}; pass
 * {@link GitProgress#NONE} when not interested.
 */
public interface GitService {

    // ---------------------------------------------------------------- repository discovery

    /**
     * Finds the repository containing the given path and describes it.
     * Cheap: reads HEAD, the configuration and the merge/rebase state, does not walk the tree.
     *
     * @param anyPathInsideRepo a file or folder inside the working tree (the work tree root itself is fine)
     * @param progress          progress/cancellation (not used for a lot here, but accepted for uniformity)
     * @return OK with the {@link GitRepoInfo}; NOT_A_REPO when no {@code .git} is found walking up, or the repository is bare
     */
    GitResult<GitRepoInfo> open(File anyPathInsideRepo, GitProgress progress);

    /**
     * @param anyPath a file or folder
     * @return {@code true} when the path lies inside a non-bare git repository. Never throws. Cheap enough for the UI thread.
     */
    boolean isRepository(File anyPath);

    /**
     * @param anyPath a file or folder
     * @return the working-tree root (folder containing {@code .git}) of the repository containing {@code anyPath}, or {@code null}
     */
    File findRepositoryRoot(File anyPath);

    // ---------------------------------------------------------------- local operations (JGitLocalOps)

    /**
     * Creates a new, empty repository in {@code dir} ({@code git init}); the folder is created when missing.
     * The initial branch name follows the user's git configuration and defaults to {@code master}.
     *
     * @return OK with the new repository's info; FAILED when the folder already is a repository or cannot be written
     */
    GitResult<GitRepoInfo> init(File dir, GitProgress progress);

    /**
     * Lists changes between HEAD and the working tree, plus conflicted paths ({@code git status}).
     * Ignored files are omitted. After a conflicting pull every unmerged path is reported as
     * {@link GitStatusEntry.Kind#CONFLICT} (and not additionally as MODIFIED).
     *
     * @return OK with the entries sorted by path (empty list when clean); NOT_A_REPO
     */
    GitResult<List<GitStatusEntry>> status(File repoDir, GitProgress progress);

    /**
     * Pages through the history of HEAD, newest first ({@code git log --skip=skip -n limit}).
     *
     * @param limit maximum number of commits to return, &gt; 0
     * @param skip  number of commits to skip from the top, &gt;= 0
     * @return OK with up to {@code limit} commits (empty for a repository without commits or when {@code skip} passes the end); NOT_A_REPO
     */
    GitResult<List<GitCommitInfo>> log(File repoDir, int limit, int skip, GitProgress progress);

    /**
     * Diff between HEAD and the working tree ({@code git diff HEAD}), staged and unstaged combined.
     * Untracked files appear as additions so that every row of {@link #status} has a diff.
     *
     * @param path repository-relative path to restrict the diff to one file, or {@code null} for the whole tree
     * @return OK with the diff ({@link GitDiff#EMPTY} when nothing changed); NOT_A_REPO
     */
    GitResult<GitDiff> diffWorkingTree(File repoDir, String path, GitProgress progress);

    /**
     * Diff of one commit against its first parent ({@code git show sha}); a root commit is compared with the empty tree.
     *
     * @param sha full or abbreviated (unique) commit id
     * @return OK with the diff; FAILED when the id does not resolve to a commit; NOT_A_REPO
     */
    GitResult<GitDiff> diffForCommit(File repoDir, String sha, GitProgress progress);

    /**
     * Stages the given paths (additions, modifications and deletions alike) and commits them
     * ({@code git add/rm ... && git commit}). Author and committer are set to {@code author}; it is not
     * persisted in the repository configuration by this call.
     *
     * @param message commit message, not blank
     * @param paths   repository-relative paths to include; {@code null} means every change reported by {@link #status}
     *                (untracked files included, conflicts excluded)
     * @param author  author and committer of the commit
     * @return OK with the new commit; FAILED when the message is blank, nothing is staged, or the repository is
     * in a MERGING/REBASING state (use {@link #continueAfterConflictResolution} then); NOT_A_REPO
     */
    GitResult<GitCommitInfo> commit(File repoDir, String message, Collection<String> paths, GitAuthor author, GitProgress progress);

    // ---------------------------------------------------------------- remote operations (JGitRemoteOps)

    /**
     * Clones {@code url} into {@code targetDir}, which must not exist or be an empty folder
     * ({@code git clone url targetDir}). The remote is named {@code origin} and the default branch is checked
     * out with its upstream configured. On failure the partially created folder is removed again.
     *
     * @param url         https:// (or http://, file://) URL without a password; a username part is allowed
     * @param credentials source of username/token for the URL's host
     * @return OK with the new repository's info; AUTH_FAILED; NETWORK; CANCELLED (folder removed);
     * FAILED (invalid URL, URL contains a password, folder not empty, remote repository not found)
     */
    GitResult<GitRepoInfo> clone(String url, File targetDir, GitCredentialsSource credentials, GitProgress progress);

    /**
     * Downloads new commits from the branch's remote (or {@code origin}) without touching the working tree
     * ({@code git fetch}). Updates {@link GitRepoInfo#getLastFetchEpochMillis()}.
     *
     * @return OK with the ahead/behind counts after the fetch; AUTH_FAILED; NETWORK; CANCELLED;
     * FAILED (no remote configured); NOT_A_REPO
     */
    GitResult<GitAheadBehind> fetch(File repoDir, GitCredentialsSource credentials, GitProgress progress);

    /**
     * Fetches, then integrates the upstream branch into the current branch according to {@code strategy}
     * ({@code git pull [--ff-only|--rebase|--no-rebase]}). A repository without commits is fast-forwarded to the
     * remote branch. Uncommitted changes to files the pull would touch stop it before anything is changed.
     * <p>
     * When {@code author} is given it is written to the repository configuration as {@code user.name}/{@code user.email}
     * (when different) so that merge commits and rebased commits get that committer. When the branch tracks nothing
     * yet, the upstream is recorded after a successful pull so that {@link #aheadBehind} works.
     *
     * @param strategy    how to integrate when local and remote diverged
     * @param author      committer for merge commits and rebased commits; {@code null} to rely on the repository/global configuration
     * @return OK with the repository info after the pull (state NORMAL);
     * NON_FAST_FORWARD (FF_ONLY and diverged; nothing changed, offer REBASE/MERGE);
     * CONFLICTS (merge or rebase stopped, repository in MERGING/REBASING state, listed files carry markers);
     * DIRTY_WORK_TREE (nothing changed, commit the listed files first);
     * AUTH_FAILED; NETWORK; CANCELLED;
     * FAILED (no remote or upstream branch, detached HEAD, already MERGING/REBASING, remote branch missing); NOT_A_REPO
     */
    GitResult<GitRepoInfo> pull(File repoDir, GitPullStrategy strategy, GitCredentialsSource credentials, GitAuthor author, GitProgress progress);

    /**
     * Uploads the current branch to its remote ({@code git push}); the remote branch gets the same name when
     * the branch has no upstream yet, and the upstream is then recorded so that {@link #aheadBehind} works.
     *
     * @return OK when the remote accepted the update or was already up to date;
     * NON_FAST_FORWARD (remote has other commits: pull first); AUTH_FAILED; NETWORK; CANCELLED;
     * FAILED (no remote configured, detached HEAD, nothing to push, remote rejected for another reason); NOT_A_REPO
     */
    GitResult<Void> push(File repoDir, GitCredentialsSource credentials, GitProgress progress);

    /**
     * Compares the current branch with its upstream using the local copy of the remote branch; no network.
     * When the branch tracks nothing but {@code <remote>/<branch>} exists, that branch is used.
     *
     * @return OK with the counts ({@link GitAheadBehind#hasUpstream()} false when there is nothing to compare with);
     * FAILED (detached HEAD); NOT_A_REPO
     */
    GitResult<GitAheadBehind> aheadBehind(File repoDir, GitProgress progress);

    /**
     * Returns the repository to the state before the conflicting pull ({@code git merge --abort} /
     * {@code git rebase --abort}). For a merge, only the paths the merge touched are restored, so unrelated
     * uncommitted edits survive; for a rebase the original branch head is checked out again.
     *
     * @return OK with the info afterwards (state NORMAL); FAILED when the repository is not MERGING/REBASING; NOT_A_REPO
     */
    GitResult<GitRepoInfo> abortMergeOrRebase(File repoDir, GitProgress progress);

    /**
     * Finishes a merge or rebase after the user edited the conflicted files ({@code git add ... && git commit} /
     * {@code git rebase --continue}). Formerly conflicted paths that still contain conflict markers
     * ({@code <<<<<<< } or {@code >>>>>>> } at a line start) make the call return CONFLICTS with those paths and
     * change nothing. All formerly conflicted paths are staged (deleted files are removed) and committed.
     *
     * @param message commit message for the merge commit; {@code null} keeps git's prepared message.
     *                Ignored for a rebase, whose commits keep their original messages.
     * @param author  committer for the merge commit or the continued rebase; {@code null} to use the configuration
     * @return OK with the info afterwards (state NORMAL);
     * CONFLICTS (markers still present, or the next rebased commit conflicts too: resolve those files and call again);
     * FAILED when the repository is not MERGING/REBASING; NOT_A_REPO
     */
    GitResult<GitRepoInfo> continueAfterConflictResolution(File repoDir, String message, GitAuthor author, GitProgress progress);

    /**
     * Sets the URL of the remote named {@code origin}, creating the remote when missing
     * ({@code git remote add|set-url origin url}). Does not contact the remote.
     *
     * @param url https:// (or http://, file://) URL without a password
     * @return OK; FAILED (invalid URL, URL contains a password); NOT_A_REPO
     */
    GitResult<Void> setRemoteUrl(File repoDir, String url, GitProgress progress);

    /**
     * Contacts a remote and lists its branches without cloning ({@code git ls-remote}), for the
     * "Test connection" button of the remote setup dialog.
     *
     * @param url         https:// (or http://, file://) URL without a password
     * @param credentials source of username/token for the URL's host
     * @return OK with the short branch names (may be empty for an empty remote); AUTH_FAILED; NETWORK; CANCELLED;
     * FAILED (invalid URL, URL contains a password, remote repository not found)
     */
    GitResult<List<String>> lsRemote(String url, GitCredentialsSource credentials, GitProgress progress);
}
