package com.example.multibuild.functional;

import com.example.multibuild.build.DummyBuildService;
import com.example.multibuild.build.LightspeedProperties;
import com.example.multibuild.build.MavenRepositoryClient;
import com.example.multibuild.buildsystem.BuildSystem;
import com.example.multibuild.buildsystem.ProjectDependencyUpdater;
import com.example.multibuild.buildsystem.ProjectParserDispatcher;
import com.example.multibuild.buildsystem.ProjectVersionUpdater;
import com.example.multibuild.git.JGitService;
import com.example.multibuild.gradle.GradleDependencyUpdater;
import com.example.multibuild.gradle.GradleMigrationService;
import com.example.multibuild.gradle.GradleWrapperGenerator;
import com.example.multibuild.gradle.GradleParser;
import com.example.multibuild.gradle.GradleProperties;
import com.example.multibuild.gradle.GradleVersionUpdater;
import com.example.multibuild.gradle.MavenToGradleConverter;
import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.*;
import com.example.multibuild.model.Module;
import com.example.multibuild.service.*;
import com.example.multibuild.testutil.TestGit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.example.multibuild.testutil.Fixtures.read;
import static org.assertj.core.api.Assertions.assertThat;

// End-to-end: repos migrated with migrate-gradle go through the same branch, dependency-update,
// graph and release flows as Maven repos — alone and mixed with repos still on Maven.
class GradleMigrationFunctionalTest {

    private static final String INTEGRATION_BRANCH = "test-integration";
    private static final List<String> ESTATE = List.of("repo-a", "repo-a-parent", "repo-b", "repo-b-property",
            "repo-b-with-parent", "repo-c", "repo-multi", "repo-multi-child", "repo-multi-parent-dep");

    @TempDir
    Path tempDir;

    private JGitService git;
    private ProjectVersionUpdater versionUpdater;
    private ProjectDependencyUpdater dependencyUpdater;
    private ProjectAggregator aggregator;
    private GradleMigrationService migration;
    private CommitMessageFormatter formatter;

    @BeforeEach
    void setUp() {
        git = TestGit.jgit();
        GradleParser gradleParser = new GradleParser();
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        versionUpdater = new ProjectVersionUpdater(new PomVersionUpdater(), new GradleVersionUpdater(gradleParser));
        dependencyUpdater = new ProjectDependencyUpdater(new DependencyVersionUpdater(), new GradleDependencyUpdater());
        aggregator = new ProjectAggregator(new ProjectParserDispatcher(mavenParser, gradleParser));
        formatter = new CommitMessageFormatter();
        ReflectionTestUtils.setField(formatter, "prefix", "");
        migration = new GradleMigrationService(new MavenToGradleConverter(), mavenParser, gradleParser, git, formatter, new GradleWrapperGenerator());
        ReflectionTestUtils.setField(migration, "dsl", "groovy");
        ReflectionTestUtils.setField(migration, "generateWrapper", false);
        ReflectionTestUtils.setField(migration, "dryMode", false);
        ReflectionTestUtils.setField(migration, "keepMaven", false);
    }

    // ── graph ────────────────────────────────────────────────────────────────

    @Test
    void migratedEstate_hasSameDependencyGraphAndBuildOrder() {
        List<Path> repos = cloneAll(ESTATE);
        Map<String, List<String>> mavenEdges = edges(repos);
        List<List<String>> mavenLayers = layers(repos);

        for (Path repo : repos) {
            assertThat(migration.migrate(repo, null, true).status())
                    .as(repo.getFileName().toString())
                    .isEqualTo(GradleMigrationService.Status.MIGRATED);
        }

        assertThat(repos).allMatch(r -> BuildSystem.detect(r) == BuildSystem.GRADLE);
        assertThat(edges(repos)).isEqualTo(mavenEdges);
        assertThat(layers(repos)).isEqualTo(mavenLayers);
        assertThat(mavenEdges.get("repo-b-with-parent")).containsExactly("repo-a-parent");
        assertThat(mavenEdges.get("repo-multi")).containsExactly("repo-a");
    }

    @Test
    void kotlinMigratedEstate_hasSameDependencyGraph() {
        ReflectionTestUtils.setField(migration, "dsl", "kotlin");
        List<Path> repos = cloneAll(ESTATE);
        Map<String, List<String>> mavenEdges = edges(repos);

        repos.forEach(r -> migration.migrate(r, null, false));

        assertThat(edges(repos)).isEqualTo(mavenEdges);
    }

    @Test
    void partiallyMigratedEstate_keepsCrossBuildSystemEdges() {
        List<Path> repos = cloneAll(List.of("repo-a", "repo-b", "repo-multi", "repo-c"));
        Map<String, List<String>> mavenEdges = edges(repos);

        migration.migrate(repos.get(0), null, false);   // only the upstream lib moves to Gradle

        assertThat(BuildSystem.detect(repos.get(0))).isEqualTo(BuildSystem.GRADLE);
        assertThat(BuildSystem.detect(repos.get(1))).isEqualTo(BuildSystem.MAVEN);
        assertThat(edges(repos)).isEqualTo(mavenEdges);
        assertThat(layers(repos).get(0)).contains("repo-a", "repo-c");
    }

    // ── integration branch ───────────────────────────────────────────────────

    @Test
    void integrationBranch_onMigratedRepo_setsBranchVersionInGradleProperties() throws Exception {
        Path work = migratedOnMain("repo-multi");

        branchService(BuildServiceType.LOCAL).apply(work, "main", new RepoConfig());

        try (Git g = Git.open(work.toFile())) {
            assertThat(g.getRepository().getBranch()).isEqualTo(INTEGRATION_BRANCH);
            assertThat(g.status().call().isClean()).isTrue();
        }
        String expected = "3.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT";
        assertThat(GradleProperties.read(work.resolve("gradle.properties"))).containsEntry("version", expected);
        assertThat(aggregator.aggregate(List.of(work)).get(0).getModules())
                .allMatch(m -> m.getArtifact().getVersion().equals(expected));
        branchService(BuildServiceType.LOCAL).validateVersions(List.of(work), Map.of(work, new RepoConfig()));
    }

    @Test
    void integrationBranch_lightspeedGradleRepo_getsBareSnapshot_andValidationFixesMismatch() {
        Path work = migratedOnMain("repo-a");
        BranchService lightspeed = branchService(BuildServiceType.LIGHTSPEED);
        lightspeed.apply(work, "main", new RepoConfig());
        assertThat(versionUpdater.getRootVersion(work)).isEqualTo("1.0.0-SNAPSHOT");

        versionUpdater.setVersions(work, "1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        ReflectionTestUtils.setField(lightspeed, "versionMismatchMode", "fix");
        ReflectionTestUtils.setField(lightspeed, "dryMode", true);
        lightspeed.validateVersions(List.of(work), Map.of(work, new RepoConfig()));

        assertThat(versionUpdater.getRootVersion(work)).isEqualTo("1.0.0-SNAPSHOT");
    }

    // ── dependency version propagation ───────────────────────────────────────

    @Test
    void dependencyVersionService_updatesGradlePropertyAndCommits() throws Exception {
        Path libA = migratedOnMain("repo-a");
        Path consumer = migratedOnMain("repo-b-property");
        versionUpdater.setVersions(libA, "1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        List<RepositoryProject> projects = aggregator.aggregate(List.of(libA, consumer));

        dependencyVersionService(true).apply(moduleMap(projects), List.of(libA, consumer),
                Map.of(libA, new RepoConfig(), consumer, new RepoConfig()), Map.of());

        assertThat(GradleProperties.read(consumer.resolve("gradle.properties")))
                .containsEntry("libAVersion", "1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
        try (Git g = Git.open(consumer.toFile())) {
            assertThat(g.log().setMaxCount(1).call().iterator().next().getFullMessage())
                    .isEqualTo("chore: update dependency versions for " + INTEGRATION_BRANCH);
        }
    }

    @Test
    void dependencyVersionService_mixedEstate_gradleLibIntoMavenConsumerAndBack() {
        Path libA = migratedOnMain("repo-a");                  // Gradle producer
        Path mavenConsumer = TestGit.cloneFixture(git, "repo-b", tempDir);        // Maven consumer
        Path gradleConsumer = migratedOnMain("repo-multi");     // Gradle consumer
        versionUpdater.setVersions(libA, "1.0.7-SNAPSHOT");
        List<Path> repos = List.of(libA, mavenConsumer, gradleConsumer);
        List<RepositoryProject> projects = aggregator.aggregate(repos);

        dependencyVersionService(true).apply(moduleMap(projects), repos,
                repos.stream().collect(Collectors.toMap(r -> r, r -> new RepoConfig())), Map.of());

        assertThat(read(mavenConsumer.resolve("pom.xml"))).contains("<version>1.0.7-SNAPSHOT</version>");
        assertThat(read(gradleConsumer.resolve("module-service/build.gradle")))
                .contains("api 'com.example:lib-a:1.0.7-SNAPSHOT'");
    }

    // ── release ──────────────────────────────────────────────────────────────

    @Test
    void release_gradleEstate_pinsReleaseVersionsTagsAndBumpsToNextSnapshot() throws Exception {
        Path libA = migratedOnMain("repo-a");
        Path consumer = migratedOnMain("repo-b");
        List<Path> repos = List.of(libA, consumer);
        List<RepositoryProject> projects = aggregator.aggregate(repos);
        Map<Path, RepoConfig> configs = Map.of(libA, new RepoConfig(), consumer, new RepoConfig());
        ResumeState state = new ResumeState();
        List<List<Path>> layers = aggregator.buildGraph(projects).topologicalLayers();
        state.initRepos(layers, configs);

        releaseService().execute(layers, moduleMap(projects), repos, List.of(), configs, state);

        assertThat(versionUpdater.getRootVersion(libA)).isEqualTo("1.0.1-SNAPSHOT");
        assertThat(versionUpdater.getRootVersion(consumer)).isEqualTo("2.0.1-SNAPSHOT");
        assertThat(read(consumer.resolve("build.gradle"))).contains("api 'com.example:lib-a:1.0.0'");
        assertThat(state.getCompletedVersionsByRepo(repos)).containsEntry(libA, "1.0.0").containsEntry(consumer, "2.0.0");
        try (Git g = Git.open(libA.toFile())) {
            assertThat(g.getRepository().resolve("refs/tags/1.0.0")).isNotNull();
            assertThat(g.status().call().isClean()).isTrue();
        }
    }

    @Test
    void release_mixedEstate_mavenConsumerPinsGradleRelease() {
        Path libA = migratedOnMain("repo-a");
        Path mavenConsumer = TestGit.cloneFixture(git, "repo-b", tempDir);
        List<Path> repos = List.of(libA, mavenConsumer);
        List<RepositoryProject> projects = aggregator.aggregate(repos);
        Map<Path, RepoConfig> configs = Map.of(libA, new RepoConfig(), mavenConsumer, new RepoConfig());
        ResumeState state = new ResumeState();
        List<List<Path>> layers = aggregator.buildGraph(projects).topologicalLayers();
        state.initRepos(layers, configs);

        releaseService().execute(layers, moduleMap(projects), repos, List.of(), configs, state);

        assertThat(versionUpdater.getRootVersion(libA)).isEqualTo("1.0.1-SNAPSHOT");
        assertThat(new PomVersionUpdater().getRootVersion(mavenConsumer)).isEqualTo("2.0.1-SNAPSHOT");
        assertThat(read(mavenConsumer.resolve("pom.xml"))).contains("<version>1.0.0</version>");
    }

    @Test
    void patchVersionResolver_keepsExistingPatchVersionOfGradleRepo() {
        Path libA = migratedOnMain("repo-a");
        versionUpdater.setVersions(libA, "1.0.0.3-SNAPSHOT");
        PatchVersionResolver resolver = new PatchVersionResolver(versionUpdater,
                new MavenRepositoryClient(new LightspeedProperties(), new RestTemplate()));

        assertThat(resolver.resolve(libA)).isEqualTo("1.0.0.3");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<Path> cloneAll(List<String> names) {
        return names.stream().map(n -> TestGit.cloneFixture(git, n, tempDir)).toList();
    }

    // Clones the fixture, migrates it on main and pushes, as if migrate-gradle had been merged.
    private Path migratedOnMain(String name) {
        Path work = TestGit.cloneFixture(git, name, tempDir);
        GradleMigrationService.Result r = migration.migrate(work, null, true);
        assertThat(r.status()).isEqualTo(GradleMigrationService.Status.MIGRATED);
        return work;
    }

    private Map<String, List<String>> edges(List<Path> repos) {
        DependencyGraph<String> graph = aggregator.buildGraph(aggregator.aggregate(repos))
                .map(p -> p.getFileName().toString());
        Map<String, List<String>> result = new TreeMap<>();
        graph.getAdjacencyList().forEach((k, v) -> result.put(k, v.stream().sorted().toList()));
        return result;
    }

    private List<List<String>> layers(List<Path> repos) {
        return aggregator.buildGraph(aggregator.aggregate(repos)).topologicalLayers().stream()
                .map(l -> l.stream().map(p -> p.getFileName().toString()).sorted().toList())
                .toList();
    }

    private static Map<Artifact, Module> moduleMap(List<RepositoryProject> projects) {
        return projects.stream().flatMap(p -> p.getModules().stream())
                .collect(Collectors.toMap(Module::getArtifact, m -> m));
    }

    private BranchService branchService(BuildServiceType type) {
        BranchService s = new BranchService(git, versionUpdater, formatter,
                new PatchVersionResolver(versionUpdater,
                        new MavenRepositoryClient(new LightspeedProperties(), new RestTemplate())));
        ReflectionTestUtils.setField(s, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(s, "dryMode", false);
        ReflectionTestUtils.setField(s, "defaultBuildService", type);
        ReflectionTestUtils.setField(s, "versionMismatchMode", "fail");
        ReflectionTestUtils.setField(s, "buildMode", BuildMode.SNAPSHOT);
        return s;
    }

    private DependencyVersionService dependencyVersionService(boolean dryMode) {
        DependencyVersionService s = new DependencyVersionService(dependencyUpdater, git, formatter);
        ReflectionTestUtils.setField(s, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(s, "dryMode", dryMode);
        ReflectionTestUtils.setField(s, "defaultBuildService", BuildServiceType.LOCAL);
        return s;
    }

    private ReleaseService releaseService() {
        ReleaseService s = new ReleaseService(versionUpdater, dependencyUpdater, git, new DummyBuildService(),
                new MavenRepositoryClient(new LightspeedProperties(), new RestTemplate()));
        ReflectionTestUtils.setField(s, "dryMode", true);
        ReflectionTestUtils.setField(s, "defaultBuildService", BuildServiceType.DUMMY);
        return s;
    }
}
