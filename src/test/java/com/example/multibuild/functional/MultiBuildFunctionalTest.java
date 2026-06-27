package com.example.multibuild.functional;

import com.example.multibuild.git.JGitService;
import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.*;
import com.example.multibuild.model.Module;
import com.example.multibuild.service.BranchService;
import com.example.multibuild.service.CommitMessageFormatter;
import com.example.multibuild.service.DependencyVersionService;
import com.example.multibuild.service.ProjectAggregator;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MultiBuildFunctionalTest {

    @TempDir
    Path tempDir;

    private JGitService gitService;
    private PomVersionUpdater pomVersionUpdater;
    private DependencyVersionUpdater dependencyVersionUpdater;
    private MavenParserImpl mavenParser;
    private ProjectAggregator projectAggregator;
    private BranchService branchService;

    private static final String INTEGRATION_BRANCH = "test-integration";

    @BeforeEach
    void setUp() {
        pomVersionUpdater = new PomVersionUpdater();
        dependencyVersionUpdater = new DependencyVersionUpdater();

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

        mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", false);

        projectAggregator = new ProjectAggregator(mavenParser);
    }

    // ── Clone phase ────────────────────────────────────────────────────────────

    @Test
    void clone_createsLocalCopyWithGitDirectory() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");

        gitService.cloneRepo(remote.toUri().toString(), work);

        assertThat(work.resolve(".git")).isDirectory();
        assertThat(work.resolve("pom.xml")).exists();
    }

    @Test
    void clone_reuseExistingRepo_fetchesNewCommit() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        // Add a new file to the remote after the initial clone
        Path newFile = remote.resolve("added.txt");
        Files.writeString(newFile, "added after clone");
        try (Git git = Git.open(remote.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add file post-clone").call();
        }

        // Second clone call on the same target should reuse and fetch
        gitService.cloneRepo(remote.toUri().toString(), work);

        // The fetched ref should be visible via git log
        try (Git git = Git.open(work.toFile())) {
            Iterable<org.eclipse.jgit.revwalk.RevCommit> log =
                    git.log().add(git.getRepository().resolve("origin/main")).setMaxCount(2).call();
            long count = java.util.stream.StreamSupport.stream(log.spliterator(), false).count();
            assertThat(count).isEqualTo(2); // initial + added
        }
    }

    // ── Branch phase ──────────────────────────────────────────────────────────

    @Test
    void branch_newBranch_createsIntegrationBranchWithVersionSuffix() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        branchService.apply(work, "main", new RepoConfig());

        try (Git git = Git.open(work.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo(INTEGRATION_BRANCH);
        }
        assertThat(pomVersionUpdater.getRootVersion(work))
                .isEqualTo("1.0.0-" + INTEGRATION_BRANCH + "-SNAPSHOT");
    }

    @Test
    void branch_existingBranch_keepsCurrentVersion() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        branchService.apply(work, "main", new RepoConfig());
        String versionAfterFirst = pomVersionUpdater.getRootVersion(work);

        // Applying a second time must not modify the version again
        branchService.apply(work, "main", new RepoConfig());

        assertThat(pomVersionUpdater.getRootVersion(work)).isEqualTo(versionAfterFirst);
    }

    @Test
    void branch_integrationBranchExistsOnRemoteButTrackingRefAbsent_checksOutExistingBranch() throws Exception {
        // Reproduces the shallow-clone failure: native `git clone --depth N` implies
        // --single-branch, so remote tracking refs for non-default branches are never
        // created locally. The fix must use ls-remote to detect the branch on the remote.
        Path remote = initRemoteRepo("repo-a");

        // Integration branch already exists on the remote (e.g., from a previous run)
        try (Git git = Git.open(remote.toFile())) {
            git.checkout().setCreateBranch(true).setName(INTEGRATION_BRANCH).call();
            git.checkout().setName("main").call();
        }

        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        // Delete the local tracking ref to simulate what shallow cloning causes
        try (Git git = Git.open(work.toFile())) {
            org.eclipse.jgit.lib.RefUpdate ru = git.getRepository().getRefDatabase()
                    .newUpdate("refs/remotes/origin/" + INTEGRATION_BRANCH, false);
            ru.setForceUpdate(true);
            ru.delete();
        }

        // checkoutOrCreateBranch must detect the branch via ls-remote (not tracking refs),
        // fetch it, check it out, and return false (= branch existed, not newly created)
        boolean created = gitService.checkoutOrCreateBranch(work, INTEGRATION_BRANCH, "main");
        assertThat(created).isFalse();

        try (Git git = Git.open(work.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo(INTEGRATION_BRANCH);
        }
    }

    @Test
    void branch_preserveVersion_skipsVersionUpdate() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        RepoConfig config = new RepoConfig();
        config.setPreserveVersion(true);
        branchService.apply(work, "main", config);

        assertThat(pomVersionUpdater.getRootVersion(work)).isEqualTo("1.0.0-SNAPSHOT");
    }

    // ── Parse phase ───────────────────────────────────────────────────────────

    @Test
    void parse_singleModule_discoversArtifactCoordinates() throws Exception {
        Path remote = initRemoteRepo("repo-a");
        Path work = workPath("repo-a");
        gitService.cloneRepo(remote.toUri().toString(), work);

        RepositoryProject project = projectAggregator.aggregate(List.of(work)).get(0);

        assertThat(project.getModules()).hasSize(1);
        Module module = project.getModules().get(0);
        assertThat(module.getArtifact().getGroupId()).isEqualTo("com.example");
        assertThat(module.getArtifact().getArtifactId()).isEqualTo("lib-a");
        assertThat(module.getArtifact().getVersion()).isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    void parse_multiModule_discoversLeafModulesAndExcludesAggregator() throws Exception {
        Path remote = initRemoteRepo("repo-multi");
        Path work = workPath("repo-multi");
        gitService.cloneRepo(remote.toUri().toString(), work);

        RepositoryProject project = projectAggregator.aggregate(List.of(work)).get(0);

        List<String> artifactIds = project.getModules().stream()
                .map(m -> m.getArtifact().getArtifactId())
                .sorted()
                .toList();
        assertThat(artifactIds).containsExactlyInAnyOrder("module-core", "module-service");
    }

    @Test
    void parse_declaredModulesOnly_excludesOrphanPomXml() throws Exception {
        Path remote = initRemoteRepo("repo-multi");

        // Add a module that is NOT listed in the root pom.xml <modules>
        Path orphanDir = remote.resolve("module-orphan");
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>" +
                "<groupId>com.example</groupId><artifactId>module-orphan</artifactId>" +
                "<version>3.0.0-SNAPSHOT</version></project>");
        try (Git git = Git.open(remote.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add undeclared module").call();
        }

        Path work = workPath("repo-multi");
        gitService.cloneRepo(remote.toUri().toString(), work);

        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", false);
        List<String> allIds = projectAggregator.aggregate(List.of(work)).get(0).getModules().stream()
                .map(m -> m.getArtifact().getArtifactId()).toList();
        assertThat(allIds).contains("module-orphan");

        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        List<String> declaredIds = projectAggregator.aggregate(List.of(work)).get(0).getModules().stream()
                .map(m -> m.getArtifact().getArtifactId()).toList();
        assertThat(declaredIds).doesNotContain("module-orphan");
        assertThat(declaredIds).containsExactlyInAnyOrder("module-core", "module-service");
    }

    // ── Graph phase ───────────────────────────────────────────────────────────

    @Test
    void graph_linearDependency_buildsLayersInOrder() throws Exception {
        Path workA = cloneFixture("repo-a");
        Path workB = cloneFixture("repo-b");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workA, workB));
        DependencyGraph<Path> graph = projectAggregator.buildGraph(projects);
        List<List<Path>> layers = graph.topologicalLayers();

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).containsExactly(workA); // lib-a has no inter-repo deps
        assertThat(layers.get(1)).containsExactly(workB); // service-b depends on lib-a
    }

    @Test
    void graph_independentRepos_appearsInSameLayer() throws Exception {
        Path workA = cloneFixture("repo-a");
        Path workC = cloneFixture("repo-c");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workA, workC));
        DependencyGraph<Path> graph = projectAggregator.buildGraph(projects);
        List<List<Path>> layers = graph.topologicalLayers();

        assertThat(layers).hasSize(1);
        assertThat(layers.get(0)).containsExactlyInAnyOrder(workA, workC);
    }

    @Test
    void graph_regularPomParent_createsEdgeFromChildRepoToParentRepo() throws Exception {
        // repo-b-with-parent has <parent> = lib-a-parent (from repo-a-parent).
        // This exercises the non-aggregator <parent> path in parsePom: the parent
        // is added as a regular Dependency, which buildGraph turns into a graph edge.
        Path workParent = cloneFixture("repo-a-parent");
        Path workChild  = cloneFixture("repo-b-with-parent");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workParent, workChild));
        DependencyGraph<Path> graph = projectAggregator.buildGraph(projects);
        List<List<Path>> layers = graph.topologicalLayers();

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).containsExactly(workParent);
        assertThat(layers.get(1)).containsExactly(workChild);
    }

    @Test
    void graph_aggregatorParent_createsEdgeFromMultiRepoToParentRepo() throws Exception {
        // repo-multi-child root pom is an aggregator (<modules>) that also declares
        // <parent> = lib-a-parent (from repo-a-parent).
        // This exercises the aggregatorParents code path in parsePom/buildGraph.
        Path workParent = cloneFixture("repo-a-parent");
        Path workMulti  = cloneFixture("repo-multi-child");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workParent, workMulti));
        DependencyGraph<Path> graph = projectAggregator.buildGraph(projects);
        List<List<Path>> layers = graph.topologicalLayers();

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).containsExactly(workParent);
        assertThat(layers.get(1)).containsExactly(workMulti);
    }

    @Test
    void graph_multiModuleRepoDependent_placesMultiRepoAfterLeaf() throws Exception {
        Path workA = cloneFixture("repo-a");
        Path workMulti = cloneFixture("repo-multi");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workA, workMulti));
        DependencyGraph<Path> graph = projectAggregator.buildGraph(projects);
        List<List<Path>> layers = graph.topologicalLayers();

        // module-service inside repo-multi depends on lib-a → multi must be after a
        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).containsExactly(workA);
        assertThat(layers.get(1)).containsExactly(workMulti);
    }

    @Test
    void graph_parentPomDependency_inheritedBySubModules() throws Exception {
        // repo-multi-parent-dep root pom declares lib-a in <dependencies>.
        // module-y does NOT re-declare it — it inherits the dep from the parent pom.
        // The graph must still recognise the cross-repo edge and place repo-multi-parent-dep
        // after repo-a.
        Path workA = cloneFixture("repo-a");
        Path workMultiParentDep = cloneFixture("repo-multi-parent-dep");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workA, workMultiParentDep));
        DependencyGraph<Path> graph = projectAggregator.buildGraph(projects);
        List<List<Path>> layers = graph.topologicalLayers();

        assertThat(layers).hasSize(2);
        assertThat(layers.get(0)).containsExactly(workA);
        assertThat(layers.get(1)).containsExactly(workMultiParentDep);
    }

    // ── Dependency version update phase ───────────────────────────────────────

    @Test
    void dependencyUpdate_inlineVersion_updatesConsumerPom() throws Exception {
        Path workA = cloneFixture("repo-a");
        Path workB = cloneFixture("repo-b");

        dependencyVersionUpdater.update(List.of(workB), Map.of("com.example:lib-a", "1.0.0"));

        String pomB = Files.readString(workB.resolve("pom.xml"));
        assertThat(pomB)
                .contains("<artifactId>lib-a</artifactId>")
                .contains("<version>1.0.0</version>");
    }

    @Test
    void dependencyUpdate_propertyPlaceholder_updatesPropertyNode() throws Exception {
        Path workBProp = cloneFixture("repo-b-property");

        dependencyVersionUpdater.update(List.of(workBProp), Map.of("com.example:lib-a", "1.0.0"));

        String pom = Files.readString(workBProp.resolve("pom.xml"));
        assertThat(pom).contains("<lib-a.version>1.0.0</lib-a.version>");
        // The placeholder itself must remain unchanged
        assertThat(pom).contains("<version>${lib-a.version}</version>");
    }

    @Test
    void dependencyUpdate_dependencyManagement_updatesVersionNode() throws Exception {
        Path work = cloneFixture("repo-b-depmanagement");

        dependencyVersionUpdater.update(List.of(work), Map.of("com.example:lib-a", "1.0.0"));

        String pom = Files.readString(work.resolve("pom.xml"));
        assertThat(pom)
                .contains("<artifactId>lib-a</artifactId>")
                .contains("<version>1.0.0</version>");
        // Version in the direct <dependency> (no <version> element) must stay absent
        assertThat(pom).doesNotContain("<dependencies>\n    <dependency>\n      <groupId>com.example</groupId>\n      <artifactId>lib-a</artifactId>\n      <version>");
    }

    @Test
    void resume_builtVersionByRepo_propagatesToConsumerDepViaApply() throws Exception {
        // Reproduces the resume scenario:
        //   1. First run: build.target=repo-a, pause-after=repo-a → state: {repo-a: SUCCESS("1.5.0-custom")}
        //   2. User manually sets a custom version in the resume state file
        //   3. Second run: build.target=repo-a, with-deps=true → repo-b's dep on lib-a must be "1.5.0-custom"
        Path workA = cloneFixture("repo-a");
        Path workB = cloneFixture("repo-b");

        branchService.apply(workA, "main", new RepoConfig());
        branchService.apply(workB, "main", new RepoConfig());

        // After branch, repo-b's dep on lib-a is still at the original "1.0.0-SNAPSHOT"
        assertThat(Files.readString(workB.resolve("pom.xml"))).contains("<version>1.0.0-SNAPSHOT</version>");

        List<RepositoryProject> projects = projectAggregator.aggregate(List.of(workA, workB));
        Map<Artifact, Module> moduleMap = projects.stream()
                .flatMap(p -> p.getModules().stream())
                .collect(Collectors.toMap(Module::getArtifact, m -> m));

        // Simulate what Main.java does inside the build loop for the consumer layer:
        //   builtVersionsByRepo = resumeState.getCompletedVersionsByRepo(clonedPaths)
        //   = {workA → "1.5.0-custom"} (the manually-set version in the state file)
        Map<Path, String> builtVersionsByRepo = Map.of(workA, "1.5.0-custom");

        CommitMessageFormatter formatter = new CommitMessageFormatter();
        ReflectionTestUtils.setField(formatter, "prefix", "");
        DependencyVersionService depVersionService =
                new DependencyVersionService(dependencyVersionUpdater, gitService, formatter);
        ReflectionTestUtils.setField(depVersionService, "integrationBranch", INTEGRATION_BRANCH);
        ReflectionTestUtils.setField(depVersionService, "dryMode", true);
        ReflectionTestUtils.setField(depVersionService, "defaultBuildService", BuildServiceType.LOCAL);

        Map<Path, RepoConfig> repoConfigByPath = Map.of(workA, new RepoConfig(), workB, new RepoConfig());
        depVersionService.apply(moduleMap, List.of(workB), repoConfigByPath, builtVersionsByRepo);

        assertThat(Files.readString(workB.resolve("pom.xml")))
                .contains("<artifactId>lib-a</artifactId>")
                .contains("<version>1.5.0-custom</version>");
    }

    @Test
    void pomVersionUpdater_setVersions_updatesRootAndSubmodules() throws Exception {
        Path work = cloneFixture("repo-multi");

        pomVersionUpdater.setVersions(work, "3.0.0");

        assertThat(Files.readString(work.resolve("pom.xml")))
                .contains("<version>3.0.0</version>");
        assertThat(Files.readString(work.resolve("module-core").resolve("pom.xml")))
                .contains("<version>3.0.0</version>");
        assertThat(Files.readString(work.resolve("module-service").resolve("pom.xml")))
                .contains("<version>3.0.0</version>");
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
            URI uri = MultiBuildFunctionalTest.class.getClassLoader().getResource("repos").toURI();
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
