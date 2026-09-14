/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ui;

import net.gsantner.markor.R;
import net.gsantner.markor.activity.MarkorBaseFragment;

/**
 * The "Git" bottom-navigation tab (replaces the former "More" tab).
 * <p>
 * Stub: only the pieces {@code MainActivity} needs to host the tab. The real
 * content (repository header, changes, history) is filled in by task 3.4.
 */
public class GitFragment extends MarkorBaseFragment {
    public static final String FRAGMENT_TAG = "GitFragment";

    public static GitFragment newInstance() {
        return new GitFragment();
    }

    public GitFragment() {
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.git__fragment;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }
}
