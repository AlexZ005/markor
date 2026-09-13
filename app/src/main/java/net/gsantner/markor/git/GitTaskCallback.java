/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * Receives the outcome of a {@link GitTask}, on the runner's callback executor - the Android main
 * thread in the app. Not called at all when the owner registered with the task reports it is gone.
 *
 * @param <T> the task's result type
 */
public interface GitTaskCallback<T> {
    void onGitTaskResult(GitTaskResult<T> result);
}
