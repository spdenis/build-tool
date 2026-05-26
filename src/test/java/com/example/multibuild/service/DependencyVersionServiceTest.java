package com.example.multibuild.service;

import com.example.multibuild.git.JGitService;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.Module;
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

class DependencyVersionServiceTest {

    @TempDir
    Path tempDir;

    private static final String INTEGRATION_BRANCH = "test-integration";

    private JGitService gitService;
    private DependencyVersionService dependencyVersionService;
    private CommitMessageFormatter formatter;

    @BeforeEach
    void setUp() {
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

        formatter = new CommitMessageFormatter();
        ReflectionTestUtils.setField(formatter, "prefix", "");

        dependencyVersionService = new DependencyVersionService(
                new DependencyVersionUpdater(), gitService, formatter);
        ReflectionTestUtils.setField(dependencyVersionService, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(dependencyVersionService, "dryMode", false);
        ReflectionTestUtils.setField(dependencyVersionService, "defaultBuildService", BuildServiceType.LOCAL);
    }

    // ── apply() — standard (LOCAL) repos ─────────────────────────────────────

    @Test
    void apply_localRepo_updatesConsumerVersionAndCommits() throws Exception {
        // repo-b depends on lib-a from repo-a; apply() must update that version in repo-b's pom
        Path remoteA = initRemoteRepo("repo-a");
        Path remoteB = initRemoteRepo("repo-b");
        Path workA = cloneAndPrepare(remoteA, "repo-a");
        Path workB = cloneAndPrepare(remoteB, "repo-b");

        Artifact libA = new Artifact("com.example", "lib-a", "1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        Module libAModule = new Module(libA, List.of(), workA, workA);
        Map<Artifact, Module> moduleMap = Map.of(libA, libAModule);
        Map<Path, RepoConfig> configMap = Map.of(
                workA, new RepoConfig(),
                workB, new RepoConfig());

        dependencyVersionService.apply(moduleMap, List.of(workA, workB), configMap, INTEGRATION_BRANCH, false);

        String pomB = Files.readString(workB.resolve("pom.xml"));
        assertThat(pomB)
                .contains("<artifactId>lib-a</artifactId>")
                .contains("<version>1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT</version>");

        // A commit must have been made in repo-b (the consumer)
        try (Git git = Git.open(workB.toFile())) {
            Iterable<org.eclipse.jgit.revwalk.RevCommit> log = git.log().setMaxCount(1).call();
            org.eclipse.jgit.revwalk.RevCommit head = log.iterator().next();
            assertThat(head.getFullMessage()).contains("dependency versions");
        }
    }

    @Test
    void apply_noVersionChange_noCommitMade() throws Exception {
        // When dependency versions are already up-to-date no commit should be created
        Path remoteB = initRemoteRepo("repo-b");
        Path workB = cloneAndPrepare(remoteB, "repo-b");

        // Use the version that repo-b's pom.xml already carries (1.0.0-SNAPSHOT)
        Artifact libA = new Artifact("com.example", "lib-a", "1.0.0-SNAPSHOT");
        Module libAModule = new Module(libA, List.of(), workB, workB);
        Map<Artifact, Module> moduleMap = Map.of(libA, libAModule);
        Map<Path, RepoConfig> configMap = Map.of(workB, new RepoConfig());

        long commitsBefore;
        try (Git git = Git.open(workB.toFile())) {
            commitsBefore = count(git.log().call());
        }

        dependencyVersionService.apply(moduleMap, List.of(workB), configMap, INTEGRATION_BRANCH, false);

        try (Git git = Git.open(workB.toFile())) {
            long commitsAfter = count(git.log().call());
            assertThat(commitsAfter).isEqualTo(commitsBefore);
        }
    }

    @Test
    void apply_dryMode_commitsButSkipsPush() throws Exception {
        Path remoteB = initRemoteRepo("repo-b");
        Path workB = cloneAndPrepare(remoteB, "repo-b");

        Artifact libA = new Artifact("com.example", "lib-a", "1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        Module libAModule = new Module(libA, List.of(), workB, workB);
        Map<Artifact, Module> moduleMap = Map.of(libA, libAModule);
        Map<Path, RepoConfig> configMap = Map.of(workB, new RepoConfig());

        dependencyVersionService.apply(moduleMap, List.of(workB), configMap, INTEGRATION_BRANCH, true);

        // Local change exists
        String pomB = Files.readString(workB.resolve("pom.xml"));
        assertThat(pomB).contains("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");

        // But the remote must NOT have been updated
        Path workBVerify = tempDir.resolve("work-verify").resolve("repo-b");
        gitService.cloneRepo(remoteB.toUri().toString(), workBVerify);
        String remotePom = Files.readString(workBVerify.resolve("pom.xml"));
        assertThat(remotePom).doesNotContain("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
    }

    // ── apply() — Lightspeed repos (bare version expansion) ──────────────────

    @Test
    void apply_lightspeedProducer_expandsBranchIntoConsumerVersion() throws Exception {
        // Lightspeed repos carry a bare -SNAPSHOT version in their pom, but publish
        // with branch qualifier: 1.0.0-SNAPSHOT -> 1.0.0-<branch>-SNAPSHOT in the repo.
        // Consumers must reference the expanded version.
        Path remoteA = initRemoteRepo("repo-a");
        Path remoteB = initRemoteRepo("repo-b");
        Path workA = cloneAndPrepare(remoteA, "repo-a");
        Path workB = cloneAndPrepare(remoteB, "repo-b");

        // lib-a module lives in a Lightspeed repo — pom carries 1.0.0-SNAPSHOT (bare)
        Artifact libA = new Artifact("com.example", "lib-a", "1.0.0-SNAPSHOT");
        Module libAModule = new Module(libA, List.of(), workA, workA);
        Map<Artifact, Module> moduleMap = Map.of(libA, libAModule);

        RepoConfig lightspeedConfig = new RepoConfig();
        lightspeedConfig.setBuildService(BuildServiceType.LIGHTSPEED);

        Map<Path, RepoConfig> configMap = Map.of(
                workA, lightspeedConfig,
                workB, new RepoConfig());

        dependencyVersionService.apply(moduleMap, List.of(workA, workB), configMap, INTEGRATION_BRANCH, false);

        // Consumer repo-b must reference the expanded version
        String pomB = Files.readString(workB.resolve("pom.xml"));
        assertThat(pomB).contains("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
    }

    @Test
    void apply_lightspeedProducer_blankIntegrationBranch_keepsBarVersion() throws Exception {
        // When integrationBranch is blank the expansion must not run and the bare version
        // is forwarded as-is to consumers.
        Path remoteB = initRemoteRepo("repo-b");
        Path workA = tempDir.resolve("work").resolve("repo-a-virtual"); // not cloned, just a key
        Path workB = cloneAndPrepare(remoteB, "repo-b");

        Artifact libA = new Artifact("com.example", "lib-a", "1.0.0-SNAPSHOT");
        Module libAModule = new Module(libA, List.of(), workA, workA);
        Map<Artifact, Module> moduleMap = Map.of(libA, libAModule);

        RepoConfig lightspeedConfig = new RepoConfig();
        lightspeedConfig.setBuildService(BuildServiceType.LIGHTSPEED);

        Map<Path, RepoConfig> configMap = Map.of(
                workA, lightspeedConfig,
                workB, new RepoConfig());

        dependencyVersionService.apply(moduleMap, List.of(workB), configMap, "", false);

        String pomB = Files.readString(workB.resolve("pom.xml"));
        // Version stays as the original 1.0.0-SNAPSHOT (no expansion without branch)
        assertThat(pomB).contains("<version>1.0.0-SNAPSHOT</version>");
    }

    // ── Explicit-param overload: branch name used as passed, not from @Value ──────

    @Test
    void apply_explicitIntegrationBranch_usedForLightspeedVersionExpansion() throws Exception {
        // @Value-injected field is "test-integration"; passing "explicit-branch" explicitly
        // must cause Lightspeed expansion to use "explicit-branch", not "test-integration".
        Path remoteB = initRemoteRepo("repo-b");
        Path workA = tempDir.resolve("work").resolve("repo-a-ls");
        Path workB = cloneAndPrepare(remoteB, "repo-b");
        Files.createDirectories(workA); // virtual Lightspeed producer root (no git needed)

        Artifact libA = new Artifact("com.example", "lib-a", "1.0.0-SNAPSHOT");
        Module libAModule = new Module(libA, List.of(), workA, workA);
        Map<Artifact, Module> moduleMap = Map.of(libA, libAModule);

        RepoConfig lightspeedConfig = new RepoConfig();
        lightspeedConfig.setBuildService(BuildServiceType.LIGHTSPEED);
        Map<Path, RepoConfig> configMap = Map.of(
                workA, lightspeedConfig,
                workB, new RepoConfig());

        dependencyVersionService.apply(moduleMap, List.of(workB), configMap, "explicit-branch", false);

        String pomB = Files.readString(workB.resolve("pom.xml"));
        assertThat(pomB).contains("1.0.0-explicit-branch-SNAPSHOT");
        assertThat(pomB).doesNotContain("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
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

    private Path cloneAndPrepare(Path remote, String name) {
        Path work = workPath(name);
        gitService.cloneRepo(remote.toUri().toString(), work);
        return work;
    }

    private Path workPath(String name) {
        return tempDir.resolve("work").resolve(name);
    }

    private static Path resourceReposDir() {
        try {
            URI uri = DependencyVersionServiceTest.class.getClassLoader().getResource("repos").toURI();
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

    private static long count(Iterable<?> iterable) {
        long n = 0;
        for (Object ignored : iterable) n++;
        return n;
    }
}
