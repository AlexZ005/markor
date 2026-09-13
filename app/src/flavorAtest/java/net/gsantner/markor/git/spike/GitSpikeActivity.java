/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.spike;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import net.gsantner.markor.BuildConfig;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Throwaway feasibility screen for the Git tab (roadmap task 1.2): runs a fixed table of JGit
 * operations on a background thread and prints PASS/FAIL per row to the screen and to logcat
 * (tag {@code GitSpike}). Exists in flavorAtest only.
 * <p>
 * The HTTPS token is read from {@code /sdcard/Download/gittab-token.txt} (pushed with adb).
 * It is never logged, never printed and never put into a URL.
 */
public class GitSpikeActivity extends AppCompatActivity {
    private static final String TAG = "GitSpike";
    private static final String REMOTE_URL = "https://github.com/AlexZ005/markor-gittab-testrepo.git";
    private static final String REMOTE_USER = "AlexZ005";
    private static final String REMOTE_BRANCH = "main";
    private static final String TOKEN_FILE = "/sdcard/Download/gittab-token.txt";
    private static final int NET_TIMEOUT_SEC = 90;

    private static final String[] OPS = {"init", "add", "commit", "log", "status", "diff", "clone", "fetch", "pull", "push", "authfail", "openexist"};

    private final Handler _main = new Handler(Looper.getMainLooper());
    private final StringBuilder _screen = new StringBuilder();
    private TextView _text;
    private String _token;
    private PersonIdent _ident;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ScrollView scroll = new ScrollView(this);
        _text = new TextView(this);
        _text.setTypeface(android.graphics.Typeface.MONOSPACE);
        _text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        _text.setTextIsSelectable(true);
        final int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        _text.setPadding(pad, pad, pad, pad);
        scroll.addView(_text);
        setContentView(scroll);
        setTitle("Git spike");

        final Thread worker = new Thread(this::runAll, "GitSpike");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------------------------------------------------------------- driver

    private void runAll() {
        _ident = new PersonIdent("Markor GitSpike", "gitspike@example.invalid");
        line(String.format(Locale.ROOT, "GitSpike  sdk=%d  buildType=%s  device=%s %s  jgitClass=%s",
                Build.VERSION.SDK_INT, BuildConfig.BUILD_TYPE, Build.MANUFACTURER, Build.MODEL, Git.class.getName()));
        line("user.home=" + System.getProperty("user.home") + "  HOME=" + System.getenv("HOME"));

        _token = readToken();
        line(_token == null ? "token: MISSING (" + TOKEN_FILE + ") -> remote rows will fail with auth errors"
                : "token: present (" + _token.length() + " chars)");

        final List<File> bases = new ArrayList<>();
        bases.add(new File(getFilesDir(), "gitspike"));
        final File ext = getExternalFilesDir(null);
        if (ext != null) {
            bases.add(new File(ext, "gitspike"));
        } else {
            line("external files dir: null (skipping external location)");
        }

        int pass = 0, fail = 0;
        final StringBuilder verdict = new StringBuilder();
        for (final File base : bases) {
            final String loc = base.getPath().startsWith(getFilesDir().getPath()) ? "internal" : "external";
            line("");
            line("== location: " + loc + "  " + base.getPath());
            deleteRecursively(base);
            if (!base.mkdirs()) {
                line("FAIL mkdirs " + base);
            }
            final boolean[] results = runTable(base);
            final StringBuilder r = new StringBuilder("RESULT sdk=" + Build.VERSION.SDK_INT + " build=" + BuildConfig.BUILD_TYPE + " loc=" + loc);
            for (int i = 0; i < OPS.length; i++) {
                r.append(' ').append(OPS[i]).append('=').append(results[i] ? "PASS" : "FAIL");
                if (results[i]) {
                    pass++;
                } else {
                    fail++;
                }
            }
            line(r.toString());
            verdict.append(r).append('\n');
        }
        line("");
        line(String.format(Locale.ROOT, "== SUMMARY sdk=%d build=%s: %d pass, %d fail", Build.VERSION.SDK_INT, BuildConfig.BUILD_TYPE, pass, fail));
        line(verdict.toString().trim());
        line("== DONE");
    }

    /** Runs the operation table in {@code base}; returns pass/fail in the order of {@link #OPS}. */
    private boolean[] runTable(final File base) {
        final boolean[] ok = new boolean[OPS.length];
        final File localDir = new File(base, "local");
        final File cloneA = new File(base, "cloneA");
        final File cloneB = new File(base, "cloneB");
        final Git[] local = new Git[1];
        final Git[] a = new Git[1];
        final Git[] b = new Git[1];
        final ObjectId[] pushedCommit = new ObjectId[1];
        final String[] pushedFile = new String[1];
        final CredentialsProvider creds = new UsernamePasswordCredentialsProvider(REMOTE_USER, _token == null ? "" : _token);

        // ---- local operations
        ok[0] = row("init", () -> {
            local[0] = Git.init().setDirectory(localDir).setInitialBranch(REMOTE_BRANCH).call();
            final File gitDir = local[0].getRepository().getDirectory();
            check(new File(gitDir, "HEAD").isFile(), "HEAD not written");
            return gitDir.getPath();
        });
        ok[1] = row("add", () -> {
            write(new File(localDir, "notes.md"), "# Notes\nline1\n");
            local[0].add().addFilepattern("notes.md").call();
            final Status st = local[0].status().call();
            check(st.getAdded().contains("notes.md"), "notes.md not in added: " + st.getAdded());
            return "added=" + st.getAdded();
        });
        ok[2] = row("commit", () -> {
            final RevCommit c = local[0].commit().setMessage("first").setAuthor(_ident).setCommitter(_ident).setSign(false).call();
            check(c != null && c.getParentCount() == 0, "unexpected commit");
            return c.abbreviate(7).name();
        });
        ok[3] = row("log", () -> {
            final List<RevCommit> commits = new ArrayList<>();
            for (final RevCommit c : local[0].log().call()) {
                commits.add(c);
            }
            check(commits.size() == 1, "expected 1 commit, got " + commits.size());
            check("first".equals(commits.get(0).getFullMessage()), "message=" + commits.get(0).getFullMessage());
            check(_ident.getEmailAddress().equals(commits.get(0).getAuthorIdent().getEmailAddress()), "author=" + commits.get(0).getAuthorIdent());
            return commits.size() + " commit(s), branch=" + local[0].getRepository().getBranch();
        });
        ok[4] = row("status", () -> {
            write(new File(localDir, "notes.md"), "# Notes\nline1\nline2\n");
            write(new File(localDir, "untracked.txt"), "x\n");
            final Status st = local[0].status().call();
            check(st.getModified().contains("notes.md"), "modified=" + st.getModified());
            check(st.getUntracked().contains("untracked.txt"), "untracked=" + st.getUntracked());
            check(!st.isClean(), "status reports clean");
            return "modified=" + st.getModified() + " untracked=" + st.getUntracked();
        });
        ok[5] = row("diff", () -> {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final List<DiffEntry> entries = local[0].diff().setOutputStream(out).call();
            final String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            // JGit's work-tree diff lists untracked files as ADD, so expect notes.md=MODIFY plus untracked.txt=ADD
            DiffEntry notes = null;
            final StringBuilder names = new StringBuilder();
            for (final DiffEntry e : entries) {
                names.append(e.getChangeType().name().charAt(0)).append(' ').append(e.getNewPath()).append("; ");
                if ("notes.md".equals(e.getNewPath())) {
                    notes = e;
                }
            }
            check(notes != null && notes.getChangeType() == DiffEntry.ChangeType.MODIFY, "no MODIFY entry for notes.md: " + names);
            check(text.contains("+line2"), "diff text lacks +line2:\n" + text);
            return entries.size() + " entries [" + names + "] " + out.size() + " bytes, first line: " + text.split("\n")[0];
        });

        // ---- remote operations (HTTPS + token). Two clones so that fetch/pull see a real remote change.
        ok[6] = row("clone", () -> {
            a[0] = Git.cloneRepository().setURI(REMOTE_URL).setDirectory(cloneA).setBranch(REMOTE_BRANCH)
                    .setCredentialsProvider(creds).setTimeout(NET_TIMEOUT_SEC).call();
            check(new File(cloneA, "README.md").isFile(), "README.md missing after clone");
            final Ref head = a[0].getRepository().exactRef("HEAD");
            b[0] = Git.cloneRepository().setURI(REMOTE_URL).setDirectory(cloneB).setBranch(REMOTE_BRANCH)
                    .setCredentialsProvider(creds).setTimeout(NET_TIMEOUT_SEC).call();
            check(new File(cloneB, "README.md").isFile(), "README.md missing after second clone");
            return "HEAD=" + head.getObjectId().abbreviate(7).name() + " branch=" + a[0].getRepository().getBranch() + " (x2 clones)";
        });
        // push from clone A first, so that fetch and pull in clone B have something to receive
        final boolean pushOk = row("push", () -> {
            final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            pushedFile[0] = "devices/sdk" + Build.VERSION.SDK_INT + "-" + BuildConfig.BUILD_TYPE + "-" + stamp + ".txt";
            write(new File(cloneA, pushedFile[0]), "sdk=" + Build.VERSION.SDK_INT + " build=" + BuildConfig.BUILD_TYPE
                    + " device=" + Build.MANUFACTURER + " " + Build.MODEL + " time=" + stamp + "\n");
            a[0].add().addFilepattern("devices").call();
            pushedCommit[0] = a[0].commit().setMessage("GitSpike sdk" + Build.VERSION.SDK_INT + " " + BuildConfig.BUILD_TYPE + " " + stamp)
                    .setAuthor(_ident).setCommitter(_ident).setSign(false).call().getId();
            final Iterable<PushResult> results = a[0].push().setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/" + REMOTE_BRANCH + ":refs/heads/" + REMOTE_BRANCH))
                    .setCredentialsProvider(creds).setTimeout(NET_TIMEOUT_SEC).call();
            final StringBuilder sb = new StringBuilder();
            for (final PushResult pr : results) {
                for (final RemoteRefUpdate u : pr.getRemoteUpdates()) {
                    sb.append(u.getRemoteName()).append(':').append(u.getStatus());
                    check(u.getStatus() == RemoteRefUpdate.Status.OK, "push status " + u.getStatus() + " " + u.getMessage());
                }
            }
            return sb + " " + pushedCommit[0].abbreviate(7).name();
        });
        ok[7] = row("fetch", () -> {
            final FetchResult fr = b[0].fetch().setRemote("origin").setCredentialsProvider(creds).setTimeout(NET_TIMEOUT_SEC).call();
            final TrackingRefUpdate tru = fr.getTrackingRefUpdate("refs/remotes/origin/" + REMOTE_BRANCH);
            if (pushOk) {
                check(tru != null, "no tracking ref update for origin/" + REMOTE_BRANCH + " (updates=" + fr.getTrackingRefUpdates().size() + ")");
                check(pushedCommit[0].equals(tru.getNewObjectId()), "fetched " + tru.getNewObjectId().abbreviate(7).name() + " but pushed " + pushedCommit[0].abbreviate(7).name());
            }
            return "advertised=" + fr.getAdvertisedRefs().size() + " updates=" + fr.getTrackingRefUpdates().size()
                    + (tru == null ? "" : " origin/" + REMOTE_BRANCH + "=" + tru.getResult());
        });
        ok[8] = row("pull", () -> {
            final PullResult pr = b[0].pull().setRemote("origin").setRemoteBranchName(REMOTE_BRANCH)
                    .setCredentialsProvider(creds).setTimeout(NET_TIMEOUT_SEC).call();
            check(pr.isSuccessful(), "pull not successful: " + pr);
            final MergeResult.MergeStatus ms = pr.getMergeResult() == null ? null : pr.getMergeResult().getMergeStatus();
            if (pushOk) {
                check(ms == MergeResult.MergeStatus.FAST_FORWARD, "expected FAST_FORWARD, got " + ms);
                check(new File(cloneB, pushedFile[0]).isFile(), "pushed file not in working tree after pull");
            }
            return "merge=" + ms + " HEAD=" + b[0].getRepository().exactRef("HEAD").getObjectId().abbreviate(7).name();
        });
        ok[9] = pushOk;
        // negative test: a wrong token must yield a TransportException with a readable message. This exercises JGit's
        // error path (JGitText resource bundle loaded by reflection), which R8 could break without the happy path noticing.
        ok[10] = row("authfail", () -> {
            final CredentialsProvider bad = new UsernamePasswordCredentialsProvider(REMOTE_USER, "invalid-token-for-spike");
            try {
                Git.cloneRepository().setURI(REMOTE_URL).setDirectory(new File(base, "cloneBad")).setBranch(REMOTE_BRANCH)
                        .setCredentialsProvider(bad).setTimeout(NET_TIMEOUT_SEC).call().close();
                throw new AssertionError("clone with a wrong token succeeded");
            } catch (final org.eclipse.jgit.api.errors.TransportException e) {
                final String msg = String.valueOf(e.getMessage());
                check(!msg.isEmpty() && !"null".equals(msg), "empty message");
                check(!msg.contains("Translation") && !msg.contains("MissingResource"), "NLS broken: " + msg);
                check(msg.toLowerCase(Locale.ROOT).contains("not authorized") || msg.contains("401") || msg.toLowerCase(Locale.ROOT).contains("auth"),
                        "unexpected message: " + msg);
                return "expected TransportException: " + msg;
            }
        });

        // diagnostic: a repository whose .git skeleton is written by hand, so that the desugared java.nio.file path
        // is probed beyond FileRepository.create()'s filemode probe (does per-file attribute reading, index writing
        // and object writing work on API < 26 if init/clone were avoided?).
        ok[11] = row("openexist", () -> {
            final File dir = new File(base, "existing");
            final File gitDir = new File(dir, ".git");
            write(new File(gitDir, "HEAD"), "ref: refs/heads/" + REMOTE_BRANCH + "\n");
            write(new File(gitDir, "config"), "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n\tlogallrefupdates = true\n");
            for (final String d : new String[]{"objects/info", "objects/pack", "refs/heads", "refs/tags"}) {
                check(new File(gitDir, d).mkdirs(), "mkdirs " + d);
            }
            write(new File(dir, "a.md"), "hello\n");
            try (Git g = Git.open(dir)) {
                final Status st = g.status().call();
                check(st.getUntracked().contains("a.md"), "untracked=" + st.getUntracked());
                g.add().addFilepattern("a.md").call();
                final RevCommit c = g.commit().setMessage("existing").setAuthor(_ident).setCommitter(_ident).setSign(false).call();
                check(g.status().call().isClean(), "not clean after commit");
                return "open+status+add+commit ok " + c.abbreviate(7).name();
            }
        });

        for (final Git g : new Git[]{local[0], a[0], b[0]}) {
            if (g != null) {
                try {
                    g.close();
                } catch (final Throwable ignored) {
                }
            }
        }
        return ok;
    }

    // ---------------------------------------------------------------- helpers

    private interface Op {
        String run() throws Throwable;
    }

    private boolean row(final String name, final Op op) {
        final long t0 = SystemClock.elapsedRealtime();
        try {
            final String detail = op.run();
            line(String.format(Locale.ROOT, "PASS %-7s %6d ms  %s", name, SystemClock.elapsedRealtime() - t0, sanitize(detail)));
            return true;
        } catch (final Throwable t) {
            final long ms = SystemClock.elapsedRealtime() - t0;
            line(String.format(Locale.ROOT, "FAIL %-7s %6d ms  %s", name, ms, sanitize(describe(t))));
            Throwable cause = t.getCause();
            int depth = 0;
            while (cause != null && depth++ < 5) {
                line("      caused by " + sanitize(describe(cause)));
                cause = cause.getCause();
            }
            final StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                line("        at " + st[i]);
            }
            Log.e(TAG, "FAIL " + name, t);
            return false;
        }
    }

    private static String describe(final Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Never let the token reach the screen or logcat, even through an exception message. */
    private String sanitize(final String s) {
        if (s == null) {
            return "null";
        }
        return (_token == null || _token.isEmpty()) ? s : s.replace(_token, "<token>");
    }

    private void line(final String s) {
        Log.i(TAG, s);
        _main.post(() -> {
            _screen.append(s).append('\n');
            _text.setText(_screen);
        });
    }

    private String readToken() {
        for (final File f : new File[]{new File(TOKEN_FILE), new File(getExternalFilesDir(null), "gittab-token.txt")}) {
            if (!f.isFile()) {
                continue;
            }
            try (InputStream in = new FileInputStream(f)) {
                final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                final byte[] tmp = new byte[512];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
                final String token = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            } catch (final IOException e) {
                line("token file " + f + " unreadable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static void write(final File f, final String content) throws IOException {
        final File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(final File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (final File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
