/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.GitRepoConfig;

import org.junit.Before;
import org.junit.Test;

/**
 * Which key a repository authenticates with (roadmap task 8.1b): the one it names, otherwise the
 * app default - and nothing at all when the key it names is gone.
 */
public class GitSshKeySelectionTest {

    private GitSshKeyStore _store;
    private GitSshKey _defaultKey;
    private GitSshKey _otherKey;

    @Before
    public void setUp() throws Exception {
        _store = GitSshKeyStoreTestFixtures.emptyStore();
        _defaultKey = _store.generate(GitSshKey.Type.ECDSA, "Default");
        _otherKey = _store.generate(GitSshKey.Type.ECDSA, "Work");
    }

    @Test
    public void aRepositoryWithoutASelectionUsesTheDefault() {
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes");

        assertThat(repo.getSshKeyId()).isNull();
        assertThat(GitSshKeySelection.resolve(repo, _store)).isEqualTo(_defaultKey);
        assertThat(GitSshKeySelection.isSelectedKeyMissing(repo, _store)).isFalse();
    }

    @Test
    public void aRepositoryThatNamesAKeyUsesThatOne() {
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes").setSshKeyId(_otherKey.getId());

        assertThat(GitSshKeySelection.resolve(repo, _store)).isEqualTo(_otherKey);
        assertThat(GitSshKeySelection.isSelectedKeyMissing(repo, _store)).isFalse();
    }

    @Test
    public void aSelectionPointingAtADeletedKeyResolvesToNothing() {
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes").setSshKeyId(_otherKey.getId());
        assertThat(_store.delete(_otherKey.getId())).isTrue();

        // Not the default key: substituting another identity behind the user's back is exactly what
        // must not happen. The caller reports that the selected key is gone.
        assertThat(GitSshKeySelection.resolve(repo, _store)).isNull();
        assertThat(GitSshKeySelection.isSelectedKeyMissing(repo, _store)).isTrue();
    }

    @Test
    public void withoutAnySelectionOrDefaultThereIsNoKey() throws Exception {
        final GitSshKeyStore empty = GitSshKeyStoreTestFixtures.emptyStore();
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes");

        assertThat(GitSshKeySelection.resolve(repo, empty)).isNull();
        assertThat(GitSshKeySelection.resolve(repo, null)).isNull();
        assertThat(GitSshKeySelection.resolve(null, _store)).isEqualTo(_defaultKey);
        assertThat(GitSshKeySelection.isSelectedKeyMissing(null, _store)).isFalse();
    }

    @Test
    public void anEmptySelectionIsTreatedAsNoSelection() {
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes").setSshKeyId("   ");

        assertThat(repo.getSshKeyId()).isNull();
        assertThat(GitSshKeySelection.resolve(repo, _store)).isEqualTo(_defaultKey);
    }
}
