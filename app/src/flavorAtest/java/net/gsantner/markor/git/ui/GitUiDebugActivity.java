/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import net.gsantner.markor.git.GitProgress;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.JGitService;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;

/**
 * Test-build-only entry point for the Git tab's read-only screens, so they can be opened from adb
 * before the Git tab itself exists. Only compiled into the {@code flavorAtest} app; not shipped.
 *
 * <pre>
 * adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.ui.GitUiDebugActivity \
 *     --es screen diff   --es repo /sdcard/repo [--es path notes.md] [--es sha HEAD]
 * adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.ui.GitUiDebugActivity \
 *     --es screen commit --es repo /sdcard/repo  --es sha HEAD
 * adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.ui.GitUiDebugActivity \
 *     --es screen probe  --es repo /sdcard/repo
 * </pre>
 * <p>
 * Note: this emulator image has no writable /sdcard, so a test repository is staged under
 * {@code /data/data/net.gsantner.markor_test/files/} with {@code run-as}.
 */
public class GitUiDebugActivity extends Activity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String repo = getIntent().getStringExtra("repo");
        final String screen = getIntent().getStringExtra("screen");
        final String path = getIntent().getStringExtra("path");
        final String sha = getIntent().getStringExtra("sha");

        if (repo != null && "probe".equals(screen)) {
            probe(new File(repo));
        } else if (repo != null) {
            if ("commit".equals(screen)) {
                CommitDetailActivity.launch(this, new File(repo), sha);
            } else {
                DiffViewerActivity.launch(this, new File(repo), path, sha);
            }
        }
        finish();
    }

    /** Prints why JGit does or does not accept a folder. Test builds only. */
    private static void probe(final File dir) {
        final File gitDir = new File(dir, ".git");
        Log.i("GitUiDebug", "dir=" + dir + " exists=" + dir.exists() + " isDir=" + dir.isDirectory()
                + " canRead=" + dir.canRead() + " list=" + java.util.Arrays.toString(dir.list()));
        Log.i("GitUiDebug", ".git exists=" + gitDir.exists() + " isDir=" + gitDir.isDirectory()
                + " canRead=" + gitDir.canRead() + " list=" + java.util.Arrays.toString(gitDir.list()));
        try {
            final FileRepositoryBuilder builder = new FileRepositoryBuilder().setMustExist(true).findGitDir(dir);
            Log.i("GitUiDebug", "findGitDir -> " + builder.getGitDir());
            Log.i("GitUiDebug", "build -> " + builder.build().getWorkTree());
        } catch (final Throwable t) {
            Log.w("GitUiDebug", "builder failed", t);
        }
        try {
            final JGitService service = new JGitService();
            Log.i("GitUiDebug", "isRepository=" + service.isRepository(dir) + " root=" + service.findRepositoryRoot(dir));
            final GitResult<?> open = service.open(dir, GitProgress.NONE);
            Log.i("GitUiDebug", "open -> " + open.getKind() + " / " + open.getMessage());
        } catch (final Throwable t) {
            Log.w("GitUiDebug", "service failed", t);
        }
    }
}
