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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GitRepoRegistryCodecTest {

    @Test
    public void roundTripKeepsEveryField() {
        final GitRepoConfig repo = new GitRepoConfig("/storage/emulated/0/notes")
                .setDisplayName("Notes")
                .setRemoteUrl("https://example.org/me/notes.git")
                .setDefaultBranch("main")
                .setPullStrategy(PullStrategy.REBASE)
                .setFetchOnOpen(false)
                .setAddedEpoch(1_757_000_000_000L);

        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson(GitRepoRegistryCodec.toJson(Collections.singletonList(repo)));

        assertThat(back).hasSize(1);
        final GitRepoConfig r = back.get(0);
        assertThat(r.getPath()).isEqualTo("/storage/emulated/0/notes");
        assertThat(r.getDisplayName()).isEqualTo("Notes");
        assertThat(r.getRemoteUrl()).isEqualTo("https://example.org/me/notes.git");
        assertThat(r.getDefaultBranch()).isEqualTo("main");
        assertThat(r.getPullStrategy()).isEqualTo(PullStrategy.REBASE);
        assertThat(r.isFetchOnOpen()).isFalse();
        assertThat(r.getAddedEpoch()).isEqualTo(1_757_000_000_000L);
    }

    @Test
    public void roundTripKeepsOrderOfSeveralRepos() {
        final List<GitRepoConfig> repos = Arrays.asList(
                new GitRepoConfig("/a"), new GitRepoConfig("/b"), new GitRepoConfig("/c"));

        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson(GitRepoRegistryCodec.toJson(repos));

        assertThat(back).extracting(GitRepoConfig::getPath).containsExactly("/a", "/b", "/c");
    }

    @Test
    public void missingFieldsGetDefaults() {
        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson("{\"repositories\":[{\"path\":\"/a\"}]}");

        assertThat(back).hasSize(1);
        final GitRepoConfig r = back.get(0);
        assertThat(r.getPullStrategy()).isEqualTo(PullStrategy.FF_ONLY);
        assertThat(r.isFetchOnOpen()).isTrue();
        assertThat(r.getRemoteUrl()).isNull();
        assertThat(r.getDefaultBranch()).isNull();
        assertThat(r.getAddedEpoch()).isZero();
        assertThat(r.getDisplayName()).isEqualTo("a"); // Folder name is the fallback
    }

    @Test
    public void unknownFieldsAreIgnored() {
        final String json = "{\"version\":99,\"somethingNew\":true,\"repositories\":"
                + "[{\"path\":\"/a\",\"displayName\":\"A\",\"futureFlag\":42,\"nested\":{\"x\":[1,2]}}]}";

        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson(json);

        assertThat(back).hasSize(1);
        assertThat(back.get(0).getPath()).isEqualTo("/a");
        assertThat(back.get(0).getDisplayName()).isEqualTo("A");
    }

    @Test
    public void unknownPullStrategyFallsBackToDefault() {
        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson(
                "{\"repositories\":[{\"path\":\"/a\",\"pullStrategy\":\"CHERRY_PICK\"}]}");

        assertThat(back).hasSize(1);
        assertThat(back.get(0).getPullStrategy()).isEqualTo(PullStrategy.FF_ONLY);
    }

    @Test
    public void bareArrayIsStillReadable() {
        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson("[{\"path\":\"/a\"},{\"path\":\"/b\"}]");

        assertThat(back).extracting(GitRepoConfig::getPath).containsExactly("/a", "/b");
    }

    @Test
    public void corruptInputYieldsEmptyRegistry() {
        assertThat(GitRepoRegistryCodec.fromJson("{\"repositories\":[")).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson("garbage")).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson("{\"repositories\":\"nope\"}")).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson("42")).isEmpty();
    }

    @Test
    public void nullEmptyAndBlankYieldEmptyRegistry() {
        assertThat(GitRepoRegistryCodec.fromJson(null)).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson("")).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson("   ")).isEmpty();
    }

    @Test
    public void entriesWithoutPathAndStrayElementsAreDropped() {
        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson(
                "{\"repositories\":[{\"path\":\"\"},null,7,\"x\",{\"displayName\":\"no path\"},{\"path\":\"/a\"}]}");

        assertThat(back).extracting(GitRepoConfig::getPath).containsExactly("/a");
    }

    @Test
    public void duplicatePathsKeepTheFirstEntry() {
        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson(
                "{\"repositories\":[{\"path\":\"/a\",\"displayName\":\"first\"},{\"path\":\"/a/\",\"displayName\":\"second\"}]}");

        assertThat(back).hasSize(1);
        assertThat(back.get(0).getDisplayName()).isEqualTo("first");
    }

    @Test
    public void validObjectWithoutRepositoriesKeyIsAnEmptyRegistry() {
        assertThat(GitRepoRegistryCodec.fromJson("{\"version\":1}")).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson("{\"version\":1,\"repositories\":null}")).isEmpty();
    }

    @Test
    public void toJsonSkipsNullAndPathlessEntries() {
        final String json = GitRepoRegistryCodec.toJson(Arrays.asList(new GitRepoConfig("/a"), null, new GitRepoConfig("")));

        assertThat(GitRepoRegistryCodec.fromJson(json)).extracting(GitRepoConfig::getPath).containsExactly("/a");
    }

    @Test
    public void toJsonOfNothingIsReadableAsEmpty() {
        assertThat(GitRepoRegistryCodec.fromJson(GitRepoRegistryCodec.toJson(null))).isEmpty();
        assertThat(GitRepoRegistryCodec.fromJson(GitRepoRegistryCodec.toJson(Collections.emptyList()))).isEmpty();
    }

    @Test
    public void storedJsonUsesStableNames() {
        final String json = GitRepoRegistryCodec.toJson(Collections.singletonList(new GitRepoConfig("/a")));

        assertThat(json).contains("\"version\":1").contains("\"repositories\"").contains("\"path\":\"/a\"")
                .contains("\"pullStrategy\":\"FF_ONLY\"").contains("\"fetchOnOpen\":true");
    }

    @Test
    public void pathIsNormalizedOnRead() {
        final List<GitRepoConfig> back = GitRepoRegistryCodec.fromJson("{\"repositories\":[{\"path\":\"  /a/b/  \"}]}");

        assertThat(back).extracting(GitRepoConfig::getPath).containsExactly("/a/b");
    }
}
