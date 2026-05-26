package com.example.multibuild.service;

import com.example.multibuild.git.JGitService;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.RepoConfig;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchServiceTest {

    @TempDir
    Path tempDir;

    private static final String INTEGRATION_BRANCH = "test-integration";

    private JGitService gitService;
    private PomVersionUpdater pomVersionUpdater;
    private BranchService branchService;

    @BeforeEach
    void setUp() {
        pomVersionUpdater = new PomVersionUpdater();

        gitService = new JGitService();
        ReflectionTestUtils.setField(gitService, "githubToken", "");
        ReflectionTestUtils.setField(gitService, "sshKeyPath", "");
        ReflectionTestUtils.setField(gitService, "sshPassphrase", "");
        ReflectionTestUtils.setField(gitService, "sshStrictHostKeyChecking", true);
        ReflectionTestUtils.setField(gitService, "cloneDepth", 0);
        ReflectionTestUtils.setField(gitService, "gitTimeoutSeconds", 120);
        ReflectionTestUtils.setField(gitService, "proxyHost", "");
        ReflectionTestUtils.setField(gitService, "proxyPort", 8080);
        ReflectionTestUtils.setField(gitService, "proxyUsername", "");
        ReflectionTestUtils.setField(gitService, "proxyPassword", "");
        ReflectionTestUtils.setField(gitService, "proxyDomains", "");
        ReflectionTestUtils.setField(gitService, "proxyBypass", "");

        CommitMessageFormatter formatter = new CommitMessageFormatter();
        ReflectionTestUtils.setField(formatter, "prefix", "");

        branchService = new BranchService(gitService, pomVersionUpdater, formatter);
        ReflectionTestUtils.setField(branchService, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(branchService, "dryMode", false);
        ReflectionTestUtils.setField(branchService, "defaultBuildService", BuildServiceType.LOCAL);
        ReflectionTestUtils.setField(branchService, "versionMismatchMode", "fail");
    }

    // ── apply() ───────────────────────────────────────────────────────────────

    @Test
    void apply_newBranch_updatesVersionAndPushes() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        branchService.apply(work, "main", new RepoConfig(), INTEGRATION_BRANCH, false);

        // Integration branch is checked out locally
        try (Git git = Git.open(work.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo(INTEGRATION_BRANCH);
        }
        // Version was stamped with branch qualifier
        assertThat(pomVersionUpdater.getRootVersion(work))
                .isEqualTo("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        // Push propagated to the remote — branch must exist there
        try (Git git = Git.open(remote.toFile())) {
            boolean remoteHasBranch = git.branchList().call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/heads/" + INTEGRATION_BRANCH));
            assertThat(remoteHasBranch).isTrue();
        }
    }

    @Test
    void apply_existingBranch_pullsAndKeepsVersion() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        // First application creates the branch and stamps the version
        branchService.apply(work, "main", new RepoConfig(), INTEGRATION_BRANCH, false);
        String versionAfterFirst = pomVersionUpdater.getRootVersion(work);

        // Second application should not modify the version again
        branchService.apply(work, "main", new RepoConfig(), INTEGRATION_BRANCH, false);

        assertThat(pomVersionUpdater.getRootVersion(work)).isEqualTo(versionAfterFirst);
    }

    @Test
    void apply_dryMode_commitHappensButPushIsSkipped() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        branchService.apply(work, "main", new RepoConfig(), INTEGRATION_BRANCH, true);

        // Version commit exists locally
        assertThat(pomVersionUpdater.getRootVersion(work))
                .isEqualTo("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        // But the branch was NOT pushed to the remote
        try (Git git = Git.open(remote.toFile())) {
            boolean remoteHasBranch = git.branchList().call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/heads/" + INTEGRATION_BRANCH));
            assertThat(remoteHasBranch).isFalse();
        }
    }

    @Test
    void apply_preserveVersion_skipsVersionUpdateButStillPushes() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        RepoConfig config = new RepoConfig();
        config.setPreserveVersion(true);
        branchService.apply(work, "main", config, INTEGRATION_BRANCH, false);

        // Version must remain as-is in the fixture (no branch qualifier)
        assertThat(pomVersionUpdater.getRootVersion(work)).isEqualTo("1.0.0-SNAPSHOT");
        // Branch must still be pushed to the remote
        try (Git git = Git.open(remote.toFile())) {
            boolean remoteHasBranch = git.branchList().call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/heads/" + INTEGRATION_BRANCH));
            assertThat(remoteHasBranch).isTrue();
        }
    }

    // ── validateVersions() ────────────────────────────────────────────────────

    @Test
    void validateVersions_correctSuffix_passes() throws Exception {
        Path work = cloneFixture("repo-a");
        // Stamp the correct suffix first
        branchService.apply(work, "main", new RepoConfig(), INTEGRATION_BRANCH, false);
        RepoConfig config = new RepoConfig();

        // Must not throw
        branchService.validateVersions(List.of(work), Map.of(work, config), INTEGRATION_BRANCH, false);
    }

    @Test
    void validateVersions_wrongSuffix_throwsWithRepoNameAndExpectedSuffix() throws Exception {
        // repo-a has version 1.0.0-SNAPSHOT without the integration-branch qualifier
        Path work = cloneFixture("repo-a");
        RepoConfig config = new RepoConfig();

        assertThatThrownBy(() -> branchService.validateVersions(List.of(work), Map.of(work, config), INTEGRATION_BRANCH, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("repo-a")
                .hasMessageContaining("-" + INTEGRATION_BRANCH + "-SNAPSHOT");
    }

    @Test
    void validateVersions_fixMode_correctsVersionAndPushes() throws Exception {
        // Use fix mode: wrong version gets auto-corrected, committed and pushed
        ReflectionTestUtils.setField(branchService, "versionMismatchMode", "fix");

        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        // Ensure we are on the integration branch so push has a remote to push to
        gitService.checkoutOrCreateBranch(work, INTEGRATION_BRANCH, "main");
        gitService.push(work);

        // Version is still the bare fixture version — does not satisfy the required suffix
        assertThat(pomVersionUpdater.getRootVersion(work)).isEqualTo("1.0.0-SNAPSHOT");

        RepoConfig config = new RepoConfig();
        branchService.validateVersions(List.of(work), Map.of(work, config), INTEGRATION_BRANCH, false);

        // After fix, version ends with the required suffix
        assertThat(pomVersionUpdater.getRootVersion(work))
                .isEqualTo("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        // And it was pushed to the remote
        Path workClone = workPath("repo-a-verify");
        gitService.cloneRepo(remote.toUri().toString(), workClone);
        gitService.checkoutOrCreateBranch(workClone, INTEGRATION_BRANCH, "main");
        assertThat(pomVersionUpdater.getRootVersion(workClone))
                .isEqualTo("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
    }

    @Test
    void validateVersions_preserveVersionRepo_isSkipped() throws Exception {
        // Repos with preserveVersion=true must never fail validation regardless of version
        Path work = cloneFixture("repo-a");
        RepoConfig config = new RepoConfig();
        config.setPreserveVersion(true);

        // Must not throw even though the version has no branch qualifier
        branchService.validateVersions(List.of(work), Map.of(work, config), INTEGRATION_BRANCH, false);
    }

    // ── Explicit-param overload: verify branch name is used as passed, not from @Value ──

    @Test
    void apply_explicitIntegrationBranch_usesThatBranchInsteadOfValueField() throws Exception {
        // The @Value-injected field is INTEGRATION_BRANCH; passing "custom-branch" explicitly
        // must cause apply() to create "custom-branch" and stamp the right version.
        String customBranch = "custom-branch";

        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        branchService.apply(work, "main", new RepoConfig(), customBranch, false);

        try (Git git = Git.open(work.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo(customBranch);
        }
        assertThat(pomVersionUpdater.getRootVersion(work))
                .isEqualTo("1.0.0-" + customBranch + "-SNAPSHOT");
    }

    @Test
    void validateVersions_explicitIntegrationBranch_checksAgainstThatBranch() throws Exception {
        // Passing an explicit branch validates against it, not the @Value-injected one.
        String customBranch = "explicit-branch";
        Path work = cloneFixture("repo-a");

        branchService.apply(work, "main", new RepoConfig(), customBranch, false);
        RepoConfig config = new RepoConfig();

        // Passes for the exact branch used
        branchService.validateVersions(List.of(work), Map.of(work, config), customBranch, false);

        // Fails for a different branch
        assertThatThrownBy(() -> branchService.validateVersions(
                        List.of(work), Map.of(work, config), "other-branch", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("-other-branch-SNAPSHOT");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path initRemoteRepo(String name) throws Exception {
        Path source = resourceReposDir().resolve(name);
        Path remote = tempDir.resolve("remotes").resolve(name);
        copyDirectory(source, remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial commit").call();
        }
        return remote;
    }

    private Path cloneFixture(String name) throws Exception {
        Path remote = initRemoteRepo(name);
        Path work = workPath(name);
        gitService.cloneRepo(remote.toUri().toString(), work);
        return work;
    }

    private Path workPath(String name) {
        return tempDir.resolve("work").resolve(name);
    }

    private static Path resourceReposDir() {
        try {
            URI uri = BranchServiceTest.class.getClassLoader().getResource("repos").toURI();
            return Paths.get(uri);
        } catch (Exception e) {
            throw new RuntimeException("Cannot locate test resource repos/", e);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path dst = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        }
    }
}
