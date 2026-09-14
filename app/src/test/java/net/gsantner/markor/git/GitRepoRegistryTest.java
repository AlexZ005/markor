/*#######################################################
 *
 *   Maintained 2025 by the Markor git-tab fork
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import static org.assertj.core.api.Assertions.assertThat;

import net.gsantner.markor.git.GitRepoConfig.PullStrategy;

import org.junit.Before;
import org.junit.Test;

public class GitRepoRegistryTest {

    /**
     * In-memory {@link GitRepoRegistry.Store}, standing in for AppSettings.
     */
    private static class FakeStore implements GitRepoRegistry.Store {
        String repositoriesJson;
        String activePath;

        @Override
        public String getGitRepositoriesJson() {
            return repositoriesJson;
        }

        @Override
        public void setGitRepositoriesJson(final String json) {
            repositoriesJson = json;
        }

        @Override
        public String getGitActiveRepoPath() {
            return activePath;
        }

        @Override
        public void setGitActiveRepoPath(final String path) {
            activePath = path;
        }
    }

    private FakeStore _store;
    private GitRepoRegistry _registry;

    @Before
    public void setUp() {
        _store = new FakeStore();
        _registry = new GitRepoRegistry(_store);
    }

    @Test
    public void emptyStoreIsAnEmptyRegistry() {
        assertThat(_registry.list()).isEmpty();
        assertThat(_registry.isEmpty()).isTrue();
        assertThat(_registry.size()).isZero();
        assertThat(_registry.getActive()).isNull();
        assertThat(_registry.getActivePath()).isEmpty();
    }

    @Test
    public void corruptStoreIsAnEmptyRegistry() {
        _store.repositoriesJson = "}{ not json";

        assertThat(_registry.list()).isEmpty();
        assertThat(_registry.getActive()).isNull();
    }

    @Test
    public void addPersistsAndIsReadBackByAnotherInstance() {
        _registry.add(new GitRepoConfig("/notes").setDisplayName("Notes").setPullStrategy(PullStrategy.MERGE));

        final GitRepoRegistry other = new GitRepoRegistry(_store);
        assertThat(other.list()).hasSize(1);
        assertThat(other.get("/notes").getDisplayName()).isEqualTo("Notes");
        assertThat(other.get("/notes").getPullStrategy()).isEqualTo(PullStrategy.MERGE);
    }

    @Test
    public void addRejectsNullAndPathlessConfigs() {
        assertThat(_registry.add(null)).isNull();
        assertThat(_registry.add(new GitRepoConfig("   "))).isNull();
        assertThat(_registry.list()).isEmpty();
    }

    @Test
    public void addOfAKnownPathReplacesTheEntryInPlace() {
        _registry.add(new GitRepoConfig("/a").setDisplayName("first"));
        _registry.add(new GitRepoConfig("/b"));
        _registry.add(new GitRepoConfig("/a/").setDisplayName("second"));

        assertThat(_registry.list()).extracting(GitRepoConfig::getPath).containsExactly("/a", "/b");
        assertThat(_registry.get("/a").getDisplayName()).isEqualTo("second");
    }

    @Test
    public void storedConfigIsACopySoLaterEditsDoNotLeakIn() {
        final GitRepoConfig repo = new GitRepoConfig("/a").setDisplayName("Notes");
        _registry.add(repo);

        repo.setDisplayName("changed");

        assertThat(_registry.get("/a").getDisplayName()).isEqualTo("Notes");
    }

    @Test
    public void getNormalizesThePath() {
        _registry.add(new GitRepoConfig("/a/b"));

        assertThat(_registry.get("/a/b/")).isNotNull();
        assertThat(_registry.get("  /a/b  ")).isNotNull();
        assertThat(_registry.contains("/a/b/")).isTrue();
        assertThat(_registry.get("/a")).isNull();
        assertThat(_registry.get(null)).isNull();
    }

    @Test
    public void updateChangesAKnownRepoAndIgnoresAnUnknownOne() {
        _registry.add(new GitRepoConfig("/a").setFetchOnOpen(true));

        assertThat(_registry.update(new GitRepoConfig("/a").setFetchOnOpen(false))).isTrue();
        assertThat(_registry.get("/a").isFetchOnOpen()).isFalse();

        assertThat(_registry.update(new GitRepoConfig("/unknown"))).isFalse();
        assertThat(_registry.update(null)).isFalse();
        assertThat(_registry.list()).hasSize(1);
    }

    @Test
    public void firstAddedRepoBecomesActive() {
        _registry.add(new GitRepoConfig("/a"));

        assertThat(_registry.getActivePath()).isEqualTo("/a");
        assertThat(_registry.getActive().getPath()).isEqualTo("/a");
    }

    @Test
    public void laterAddsDoNotStealTheActiveSelection() {
        _registry.add(new GitRepoConfig("/a"));
        _registry.add(new GitRepoConfig("/b"));

        assertThat(_registry.getActivePath()).isEqualTo("/a");
    }

    @Test
    public void setActiveOnlyAcceptsRegisteredRepos() {
        _registry.add(new GitRepoConfig("/a"));
        _registry.add(new GitRepoConfig("/b"));

        assertThat(_registry.setActive("/b/")).isTrue();
        assertThat(_registry.getActive().getPath()).isEqualTo("/b");

        assertThat(_registry.setActive("/nope")).isFalse();
        assertThat(_registry.getActive().getPath()).isEqualTo("/b");
    }

    @Test
    public void setActiveWithNullClearsTheSelection() {
        _registry.add(new GitRepoConfig("/a"));

        assertThat(_registry.setActive(null)).isTrue();
        assertThat(_registry.getActive()).isNull();
        assertThat(_registry.getActivePath()).isEmpty();
    }

    @Test
    public void activeRepoSurvivesRemovalOfAnotherRepo() {
        _registry.add(new GitRepoConfig("/a"));
        _registry.add(new GitRepoConfig("/b"));
        _registry.setActive("/b");

        assertThat(_registry.remove("/a")).isTrue();

        assertThat(_registry.list()).extracting(GitRepoConfig::getPath).containsExactly("/b");
        assertThat(_registry.getActive().getPath()).isEqualTo("/b");
    }

    @Test
    public void removingTheActiveRepoClearsActive() {
        _registry.add(new GitRepoConfig("/a"));
        _registry.add(new GitRepoConfig("/b"));
        _registry.setActive("/b");

        assertThat(_registry.remove("/b/")).isTrue();

        assertThat(_registry.getActive()).isNull();
        assertThat(_registry.getActivePath()).isEmpty();
        assertThat(_registry.list()).extracting(GitRepoConfig::getPath).containsExactly("/a");
    }

    @Test
    public void removeOfAnUnknownPathChangesNothing() {
        _registry.add(new GitRepoConfig("/a"));

        assertThat(_registry.remove("/nope")).isFalse();
        assertThat(_registry.remove(null)).isFalse();
        assertThat(_registry.list()).hasSize(1);
        assertThat(_registry.getActivePath()).isEqualTo("/a");
    }

    @Test
    public void activePathPointingAtAnUnregisteredRepoYieldsNoActiveRepo() {
        _store.activePath = "/gone";

        assertThat(_registry.getActivePath()).isEqualTo("/gone");
        assertThat(_registry.getActive()).isNull();
    }

    @Test
    public void clearForgetsEverything() {
        _registry.add(new GitRepoConfig("/a"));
        _registry.add(new GitRepoConfig("/b"));

        _registry.clear();

        assertThat(_registry.list()).isEmpty();
        assertThat(_registry.getActive()).isNull();
        assertThat(_registry.getActivePath()).isEmpty();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void listIsUnmodifiable() {
        _registry.add(new GitRepoConfig("/a"));

        _registry.list().add(new GitRepoConfig("/b"));
    }
}
