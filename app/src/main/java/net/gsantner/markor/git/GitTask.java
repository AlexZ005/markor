/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

/**
 * One unit of git work, run on {@link GitTaskRunner}'s worker thread for a repository.
 * <p>
 * Implementations may block, must not touch views, and should poll the given token at their own
 * checkpoints so cancellation takes effect promptly. Anything thrown is caught by the runner and
 * delivered as an error result.
 *
 * @param <T> the result type handed to the callback
 */
public interface GitTask<T> {
    T run(GitCancelToken cancelToken) throws Exception;
}
