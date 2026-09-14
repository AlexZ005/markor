/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Which activity is in front right now, for the two SSH dialogs that have to appear over whatever the
 * user is looking at (roadmap task 8.1c).
 * <p>
 * They cannot ask a fragment for its activity, because the operation that raises them outlives the
 * view that started it: a clone keeps running while the screen is rotated and its dialog is destroyed
 * and rebuilt, and a fingerprint question arriving in that gap would otherwise find no window and be
 * answered with "no" — turning a rotation into a failed clone. Registered once from
 * {@code ApplicationObject}, cleared as soon as the activity is paused, and held weakly so nothing
 * here can keep a destroyed activity alive.
 * <p>
 * Nothing but the SSH prompts uses this. Everything else in the Git tab has a fragment and should ask
 * it, which is the safer habit.
 */
public final class GitForegroundActivity {

    private static volatile WeakReference<Activity> _current = new WeakReference<>(null);

    private GitForegroundActivity() {
    }

    /** Called once, from {@code ApplicationObject.onCreate}. */
    public static void install(final Application application) {
        if (application == null) {
            return;
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull final Activity activity) {
                _current = new WeakReference<>(activity);
            }

            @Override
            public void onActivityPaused(@NonNull final Activity activity) {
                if (_current.get() == activity) {
                    _current = new WeakReference<>(null);
                }
            }

            @Override
            public void onActivityCreated(@NonNull final Activity activity, @Nullable final Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull final Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull final Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull final Activity activity, @NonNull final Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull final Activity activity) {
                if (_current.get() == activity) {
                    _current = new WeakReference<>(null);
                }
            }
        });
    }

    /** @return the resumed activity, or {@code null} when the app is not in the foreground */
    public static Activity get() {
        final Activity activity = _current.get();
        return activity == null || activity.isFinishing() ? null : activity;
    }
}
