package com.example.multibuild.git;

import com.example.multibuild.testutil.TestGit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.stream.Stream;

import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

// commitsAhead / deleteBranch / createFreshBranch behave the same with JGit and the native git CLI.
class BranchResetContractTest {

    @TempDir
    Path tempDir;

    static Stream<String> implementations() {
        return Stream.of("jgit", "native");
    }

    static GitService git(String impl) {
        if (impl.equals("jgit")) return TestGit.jgit();
        NativeGitService g = new NativeGitService();
        ReflectionTestUtils.setField(g, "cloneDepth", 0);
        ReflectionTestUtils.setField(g, "proxyHost", "");
        ReflectionTestUtils.setField(g, "proxyPort", 8080);
        ReflectionTestUtils.setField(g, "proxyUsername", "");
        ReflectionTestUtils.setField(g, "proxyPassword", "");
        ReflectionTestUtils.setField(g, "proxyBypass", "");
        ReflectionTestUtils.setField(g, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(g, "retryInitialBackoffMs", 0L);
        ReflectionTestUtils.setField(g, "retryBackoffMultiplier", 1.0);
        return g;
    }

    private static boolean remoteHas(Path remote, String branch) throws Exception {
        try (Git r = Git.open(remote.toFile())) {
            return r.getRepository().resolve("refs/heads/" + branch) != null;
        }
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void branchLifecycle(String impl) throws Exception {
        GitService git = git(impl);
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));

        assertThat(git.commitsAhead(work, "feature", "main")).isEmpty();

        git.checkoutOrCreateBranch(work, "feature", "main");
        write(work.resolve("a.txt"), "a");
        git.commitAllIfDirty(work, "first");
        write(work.resolve("b.txt"), "b");
        git.commitAllIfDirty(work, "second");
        assertThat(git.commitsAhead(work, "feature", "main")).containsExactly("second", "first");   // local only
        assertThat(git.recentCommits(work, 2)).hasSize(2)
                .allMatch(c -> c.matches("[0-9a-f]{7} .*")).extracting(c -> c.substring(8)).containsExactly("second", "first");
        git.push(work);
        assertThat(git.commitsAhead(work, "feature", "main")).containsExactly("second", "first");   // from origin

        write(work.resolve("untracked.txt"), "x");
        git.createFreshBranch(work, "main", "main");
        assertThat(work.resolve("a.txt")).doesNotExist();
        assertThat(work.resolve("untracked.txt")).doesNotExist();

        assertThat(git.deleteBranch(work, "feature", false)).isFalse();     // local only
        assertThat(remoteHas(remote, "feature")).isTrue();
        assertThat(git.hasRemoteBranch(work, "feature")).isFalse();         // tracking ref dropped

        assertThat(git.deleteBranch(work, "feature", true)).isTrue();
        assertThat(remoteHas(remote, "feature")).isFalse();
        assertThat(git.deleteBranch(work, "feature", true)).isFalse();      // idempotent

        git.createFreshBranch(work, "feature", "main");
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.getRepository().getBranch()).isEqualTo("feature");
        }
        assertThat(git.commitsAhead(work, "feature", "main")).isEmpty();
    }

    private static String upstreamOf(Path work, String branch) throws Exception {
        try (Git g = Git.open(work.toFile())) {
            return g.getRepository().getConfig().getString("branch", branch, "merge");
        }
    }

    private static org.eclipse.jgit.lib.ObjectId remoteRef(Path remote, String branch) throws Exception {
        try (Git r = Git.open(remote.toFile())) {
            return r.getRepository().resolve("refs/heads/" + branch);
        }
    }

    // push.default=upstream would send a branch tracking origin/main onto main.
    @ParameterizedTest
    @MethodSource("implementations")
    void newBranch_doesNotTrackSourceBranch_andPushNeverLandsOnIt(String impl) throws Exception {
        GitService git = git(impl);
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));
        try (Git g = Git.open(work.toFile())) {
            var config = g.getRepository().getConfig();
            config.setString("push", null, "default", "upstream");
            config.save();
        }
        var mainBefore = remoteRef(remote, "main");

        git.checkoutOrCreateBranch(work, "gradle-migration", "main");
        assertThat(upstreamOf(work, "gradle-migration")).isNull();
        write(work.resolve("a.txt"), "a");
        git.commitAllIfDirty(work, "migrate");
        git.push(work);

        assertThat(remoteRef(remote, "main")).isEqualTo(mainBefore);
        assertThat(remoteRef(remote, "gradle-migration")).isNotNull().isNotEqualTo(mainBefore);
        assertThat(upstreamOf(work, "gradle-migration")).isEqualTo("refs/heads/gradle-migration");

        git.createFreshBranch(work, "gradle-migration", "main");
        assertThat(upstreamOf(work, "gradle-migration")).isNull();
    }

    // A clone left by an earlier version: the branch tracks origin/main.
    @ParameterizedTest
    @MethodSource("implementations")
    void push_repairsBranchThatTracksAnotherBranch(String impl) throws Exception {
        GitService git = git(impl);
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));
        try (Git g = Git.open(work.toFile())) {
            g.checkout().setCreateBranch(true).setName("gradle-migration").setStartPoint("refs/remotes/origin/main")
                    .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK).call();
        }
        assertThat(upstreamOf(work, "gradle-migration")).isEqualTo("refs/heads/main");
        var mainBefore = remoteRef(remote, "main");
        write(work.resolve("a.txt"), "a");
        git.commitAllIfDirty(work, "migrate");

        git.push(work);

        assertThat(remoteRef(remote, "main")).isEqualTo(mainBefore);
        assertThat(remoteRef(remote, "gradle-migration")).isNotNull();
        assertThat(upstreamOf(work, "gradle-migration")).isEqualTo("refs/heads/gradle-migration");
    }
}
