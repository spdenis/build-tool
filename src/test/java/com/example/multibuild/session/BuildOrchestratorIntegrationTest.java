package com.example.multibuild.session;

import com.example.multibuild.build.DispatchingBuildService;
import com.example.multibuild.build.DummyBuildService;
import com.example.multibuild.git.JGitService;
import com.example.multibuild.logging.SessionLogAppender;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.pipeline.BuildContext;
import com.example.multibuild.service.BranchService;
import com.example.multibuild.service.CommitMessageFormatter;
import com.example.multibuild.service.DependencyVersionService;
import com.example.multibuild.service.ProjectAggregator;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level tests for BuildOrchestrator.
 *
 * Covers the full phase sequence: clone → branch → version-stamp →
 * validate versions → dependency-version-update → build.
 */
class BuildOrchestratorIntegrationTest {

    @TempDir
    Path tempDir;

    private static final String INTEGRATION_BRANCH = "test-integration";

    private JGitService gitService;
    private PomVersionUpdater pomVersionUpdater;
    private BranchService branchService;
    private BuildOrchestrator orchestrator;
    private SessionLogAppender logAppender;

    @BeforeEach
    void setUp() throws Exception {
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

        pomVersionUpdater = new PomVersionUpdater();
        CommitMessageFormatter formatter = new CommitMessageFormatter();
        ReflectionTestUtils.setField(formatter, "prefix", "");

        branchService = new BranchService(gitService, pomVersionUpdater, formatter);
        ReflectionTestUtils.setField(branchService, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(branchService, "dryMode", false);
        ReflectionTestUtils.setField(branchService, "defaultBuildService", BuildServiceType.LOCAL);
        ReflectionTestUtils.setField(branchService, "versionMismatchMode", "fail");

        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", false);

        ProjectAggregator aggregator = new ProjectAggregator(mavenParser);

        DummyBuildService dummyService = new DummyBuildService();
        DispatchingBuildService dispatchingService = new DispatchingBuildService(
                dummyService, dummyService, dummyService, dummyService);
        ReflectionTestUtils.setField(dispatchingService, "defaultBuildService", BuildServiceType.DUMMY);

        logAppender = new SessionLogAppender();
        logAppender.init();

        ApplicationEventPublisher noopPublisher = event -> {};

        DependencyVersionService dependencyVersionService = new DependencyVersionService(
                new DependencyVersionUpdater(), gitService, formatter);
        ReflectionTestUtils.setField(dependencyVersionService, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(dependencyVersionService, "dryMode", false);
        ReflectionTestUtils.setField(dependencyVersionService, "defaultBuildService", BuildServiceType.LOCAL);

        orchestrator = new BuildOrchestrator(aggregator, dispatchingService, logAppender,
                gitService, noopPublisher, branchService, dependencyVersionService);
    }

    @Test
    void start_skipGitMode_parsesExistingReposAndCompletesSuccessfully() throws Exception {
        // With skipGit=true the orchestrator skips cloning and uses already-present dirs.
        // Pre-populate two repos in the clone dir so the orchestrator can find them.
        Path cloneDir = tempDir.resolve("clones");
        Files.createDirectories(cloneDir);

        Path workA = cloneDir.resolve("repo-a");
        Path workB = cloneDir.resolve("repo-b");
        prepareLocalRepo("repo-a", workA);
        prepareLocalRepo("repo-b", workB);

        RepoConfig configA = repoConfig(workA);
        RepoConfig configB = repoConfig(workB);

        BuildContext context = new BuildContext(
                List.of(configA, configB),
                cloneDir,
                INTEGRATION_BRANCH,
                BuildMode.SNAPSHOT,
                BuildServiceType.DUMMY,
                true,       // buildEnabled
                true,       // dryMode
                true,       // skipGit
                null,
                false,
                "main",
                "",
                false,
                null
        );

        BuildSession session = new BuildSession("test-session");
        orchestrator.start(session, context);

        // Wait for the background thread to finish (up to 30 s)
        waitForCompletion(session, 30);

        assertThat(session.getStatus())
                .as("build should succeed with dummy service on local repos")
                .isEqualTo(BuildSession.Status.SUCCESS);
    }

    @Test
    void start_snapshotBuild_createsIntegrationBranchWithVersionSuffix() throws Exception {
        // Full pipeline: clone from local remotes → branch → version stamp → build.
        Path cloneDir = tempDir.resolve("clones");
        Files.createDirectories(cloneDir);

        Path remoteA = initRemoteRepo("repo-a");
        Path remoteB = initRemoteRepo("repo-b");

        // Use file:// URLs without trailing slash so repoName() extracts "repo-a"/"repo-b"
        RepoConfig configA = new RepoConfig();
        configA.setUrl("file://" + remoteA.toAbsolutePath());

        RepoConfig configB = new RepoConfig();
        configB.setUrl("file://" + remoteB.toAbsolutePath());

        BuildContext context = new BuildContext(
                List.of(configA, configB),
                cloneDir,
                INTEGRATION_BRANCH,
                BuildMode.SNAPSHOT,
                BuildServiceType.DUMMY,
                true,       // buildEnabled
                false,      // dryMode — push happens
                false,      // skipGit — full clone + branch
                null,
                false,
                "main",
                "",
                false,
                null
        );

        BuildSession session = new BuildSession("test-full-pipeline");
        orchestrator.start(session, context);
        waitForCompletion(session, 30);

        assertThat(session.getStatus())
                .as("build should complete successfully: " + session.getErrorMessage())
                .isEqualTo(BuildSession.Status.SUCCESS);

        // Integration branch was created and pom versions were stamped
        Path workA = cloneDir.resolve("repo-a");
        Path workB = cloneDir.resolve("repo-b");
        assertThat(pomVersionUpdater.getRootVersion(workA))
                .isEqualTo("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        assertThat(pomVersionUpdater.getRootVersion(workB))
                .isEqualTo("2.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");

        // Dependency version in repo-b pom was updated to point at the stamped lib-a version
        String pomB = Files.readString(workB.resolve("pom.xml"));
        assertThat(pomB).contains("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates a bare-like remote repo in a temp dir seeded from the named fixture. */
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

    /** Creates a real git repo in {@code targetDir} seeded from the named fixture. */
    private void prepareLocalRepo(String fixtureName, Path targetDir) throws Exception {
        Path source = resourceReposDir().resolve(fixtureName);
        copyDirectory(source, targetDir);
        try (Git git = Git.init().setDirectory(targetDir.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial commit").call();
        }
    }

    private static RepoConfig repoConfig(Path workDir) {
        RepoConfig c = new RepoConfig();
        c.setUrl("file://" + workDir.toAbsolutePath());
        return c;
    }

    private static void waitForCompletion(BuildSession session, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            BuildSession.Status s = session.getStatus();
            if (s == BuildSession.Status.SUCCESS
                    || s == BuildSession.Status.FAILED
                    || s == BuildSession.Status.CANCELLED) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static Path resourceReposDir() {
        try {
            URI uri = BuildOrchestratorIntegrationTest.class.getClassLoader()
                    .getResource("repos").toURI();
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
