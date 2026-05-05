package com.example.multibuild.app;

import com.example.multibuild.build.BuildFailedException;
import com.example.multibuild.build.BuildService;
import com.example.multibuild.git.GitService;
import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.model.RepositoryProject;
import com.example.multibuild.model.ResumeState;
import com.example.multibuild.service.BranchService;
import com.example.multibuild.service.DependencyVersionService;
import com.example.multibuild.service.ProjectAggregator;
import com.example.multibuild.service.ReleaseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootApplication
@ComponentScan(basePackages = "com.example.multibuild")
public class Main implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Value("${clone.dir}")
    private String cloneDir;

    @Value("${build.enabled:false}")
    private boolean buildEnabled;

    @Value("${build.mode:SNAPSHOT}")
    private BuildMode buildMode;

    @Value("${build.resume.state.file:}")
    private String resumeStateFile;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    @Value("${default.source.branch:main}")
    private String defaultSourceBranch;

    @Value("${github.token:}")
    private String githubToken;

    @Value("${git.auth.token.in.url:false}")
    private boolean gitAuthTokenInUrl;

    private final GitService gitService;
    private final BranchService branchService;
    private final PomVersionUpdater pomVersionUpdater;
    private final ProjectAggregator aggregator;
    private final DependencyVersionService dependencyVersionService;
    private final BuildService buildService;
    private final ReleaseService releaseService;
    private final ObjectMapper objectMapper;

    public Main(GitService gitService, BranchService branchService,
                PomVersionUpdater pomVersionUpdater,
                ProjectAggregator aggregator, DependencyVersionService dependencyVersionService,
                BuildService buildService, ReleaseService releaseService, ObjectMapper objectMapper) {
        this.gitService = gitService;
        this.branchService = branchService;
        this.pomVersionUpdater = pomVersionUpdater;
        this.aggregator = aggregator;
        this.dependencyVersionService = dependencyVersionService;
        this.buildService = buildService;
        this.releaseService = releaseService;
        this.objectMapper = objectMapper;
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar multibuild.jar repos.json");
            System.exit(1);
        }

        if (branchService.getIntegrationBranch().isBlank()) {
            System.err.println("[ERROR] integration.branch is required but not set.");
            System.exit(1);
        }

        List<RepoConfig> repoEntries = objectMapper.readValue(
                Paths.get(args[0]).toFile(), new TypeReference<List<RepoConfig>>() {});

        log.info("══════════════════════════════════════════════════════");
        log.info("  MultiBuild  mode={}  repos={}  build={}  dryMode={}",
                buildMode, repoEntries.size(),
                buildEnabled ? buildMode : "disabled", dryMode);
        log.info("══════════════════════════════════════════════════════");

        Path workDir = Paths.get(cloneDir);
        Files.createDirectories(workDir);

        Path stateFile = resumeStateFile.isBlank()
                ? workDir.resolve(".multibuild-resume.json")
                : Paths.get(resumeStateFile);

        ResumeState resumeState = loadResumeState(stateFile);

        // ── Phase: clone & branch ──────────────────────────────────
        log.info("── Phase 1/3: clone & branch ─────────────────────────");
        List<Path> clonedPaths = new ArrayList<>();
        Map<Path, RepoConfig> repoConfigByPath = new LinkedHashMap<>();
        for (int i = 0; i < repoEntries.size(); i++) {
            RepoConfig entry = repoEntries.get(i);
            String url = entry.getUrl();
            String repoName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");
            log.info("  [{}/{}] {}", i + 1, repoEntries.size(), repoName);
            String cloneUrl = gitAuthTokenInUrl ? withToken(url, githubToken) : url;
            Path cloned = gitService.cloneRepo(cloneUrl, workDir.resolve(repoName));
            branchService.apply(cloned, resolveSourceBranch(cloned, entry.getEffectiveSourceBranch(defaultSourceBranch)));
            if (entry.hasVersionOverride()) {
                applyVersionOverride(cloned, entry.getVersion());
            }
            clonedPaths.add(cloned);
            repoConfigByPath.put(cloned, entry);
        }

        if (buildEnabled && buildMode != BuildMode.RELEASE) {
            List<Path> pathsToValidate = clonedPaths.stream()
                    .filter(p -> !repoConfigByPath.get(p).hasVersionOverride())
                    .toList();
            try {
                branchService.validateVersions(pathsToValidate);
            } catch (RuntimeException e) {
                System.err.println("\n[BUILD FAILED]\n" + e.getMessage());
                System.exit(1);
            }
        }

        // ── Phase: parse & graph ───────────────────────────────────
        log.info("── Phase 2/3: parse pom.xml & resolve dependency graph ");
        List<RepositoryProject> projects = aggregator.aggregate(clonedPaths);
        DependencyGraph graph = aggregator.buildGraph(projects);
        List<List<Artifact>> buildLayers = graph.topologicalLayers();
        List<Artifact> buildOrder = buildLayers.stream().flatMap(List::stream).toList();

        log.info("  {} artifact(s) across {} repo(s) in {} layer(s):",
                buildOrder.size(), clonedPaths.size(), buildLayers.size());
        for (int i = 0; i < buildLayers.size(); i++) {
            List<Artifact> layer = buildLayers.get(i);
            String repos = layer.stream()
                    .map(a -> { Module m = projects.stream().flatMap(p -> p.getModules().stream())
                            .filter(mod -> mod.getArtifact().equals(a)).findFirst().orElse(null);
                        return m != null ? m.getRepoRoot().getFileName().toString() : a.getArtifactId(); })
                    .distinct().collect(Collectors.joining(", "));
            log.info("    Layer {}/{}: {} artifact(s) — {}", i + 1, buildLayers.size(), layer.size(), repos);
        }
        logDependencyTree(projects, graph);

        Map<Artifact, Module> moduleMap = projects.stream()
                .flatMap(p -> p.getModules().stream())
                .collect(Collectors.toMap(Module::getArtifact, m -> m));

        // ── Phase: build ───────────────────────────────────────────
        if (buildEnabled) {
            log.info("── Phase 3/3: build ({}) ─────────────────────────────", buildMode);
            long buildStart = System.currentTimeMillis();
            try {
                if (buildMode == BuildMode.RELEASE) {
                    releaseService.execute(buildLayers, moduleMap, clonedPaths, repoConfigByPath, resumeState);
                } else {
                    dependencyVersionService.apply(moduleMap, clonedPaths);
                    try {
                        buildService.buildAll(buildLayers, moduleMap, repoConfigByPath,
                                resumeState.getCompletedRepoNames(), Collections.emptyMap());
                    } catch (BuildFailedException e) {
                        e.getSucceeded().forEach(resumeState::markCompleted);
                        throw e;
                    }
                }
                Files.deleteIfExists(stateFile);
                log.info("══ Build complete in {} ══════════════════════════════", elapsed(buildStart));
            } catch (RuntimeException e) {
                saveResumeState(resumeState, stateFile);
                System.err.println("\n[BUILD FAILED]\n" + e.getMessage());
                System.err.println("\nTo resume this build, re-run with:");
                System.err.println("  --build.resume.state.file=" + stateFile.toAbsolutePath());
                System.exit(1);
            }
        }

        BuildResult result = new BuildResult();
        result.artifacts = new ArrayList<>();
        result.dependencies = new ArrayList<>();
        result.buildOrder = new ArrayList<>();

        for (Map.Entry<Artifact, List<Artifact>> entry : graph.getAdjacencyList().entrySet()) {
            result.artifacts.add(new BuildResult.ArtifactDto(entry.getKey()));
            for (Artifact dep : entry.getValue()) {
                result.dependencies.add(new BuildResult.DependencyDto(
                    entry.getKey().toString(), dep.toString()
                ));
            }
        }

        for (Artifact a : buildOrder) {
            result.buildOrder.add(a.toString());
        }

        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private void logDependencyTree(List<RepositoryProject> projects, DependencyGraph graph) {
        // artifact key → repo name
        Map<String, String> repoByKey = new HashMap<>();
        for (RepositoryProject p : projects) {
            for (Module m : p.getModules()) {
                repoByKey.put(m.getArtifact().key(), p.getName());
            }
        }

        // repoDeps[A] = repos A depends on (cross-repo); repoDependents[B] = repos that depend on B
        Map<String, Set<String>> repoDeps = new LinkedHashMap<>();
        Map<String, Set<String>> repoDependents = new LinkedHashMap<>();
        for (RepositoryProject p : projects) {
            repoDeps.put(p.getName(), new LinkedHashSet<>());
            repoDependents.put(p.getName(), new LinkedHashSet<>());
        }
        for (Map.Entry<Artifact, List<Artifact>> e : graph.getAdjacencyList().entrySet()) {
            String from = repoByKey.get(e.getKey().key());
            if (from == null) continue;
            for (Artifact dep : e.getValue()) {
                String to = repoByKey.get(dep.key());
                if (to != null && !to.equals(from)) {
                    repoDeps.get(from).add(to);
                    repoDependents.get(to).add(from);
                }
            }
        }

        // roots = repos with no external dependencies (foundation repos)
        List<String> roots = projects.stream()
                .map(RepositoryProject::getName)
                .filter(r -> repoDeps.get(r).isEmpty())
                .collect(Collectors.toList());
        if (roots.isEmpty()) {
            roots = projects.stream().map(RepositoryProject::getName).collect(Collectors.toList());
        }

        log.info("── Dependency tree ───────────────────────────────────");
        Set<String> printed = new LinkedHashSet<>();
        for (String root : roots) {
            log.info("  {}", root);
            printed.add(root);
            List<String> dependents = new ArrayList<>(repoDependents.getOrDefault(root, Set.of()));
            for (int i = 0; i < dependents.size(); i++) {
                renderTreeNode(dependents.get(i), "", i == dependents.size() - 1, repoDependents, printed);
            }
        }
    }

    private void renderTreeNode(String repo, String prefix, boolean last,
                                Map<String, Set<String>> repoDependents,
                                Set<String> printed) {
        String connector = last ? "└── " : "├── ";
        if (printed.contains(repo)) {
            log.info("  {}{}{} ↑", prefix, connector, repo);
            return;
        }
        log.info("  {}{}{}", prefix, connector, repo);
        printed.add(repo);
        List<String> dependents = new ArrayList<>(repoDependents.getOrDefault(repo, Set.of()));
        String childPrefix = prefix + (last ? "    " : "│   ");
        for (int i = 0; i < dependents.size(); i++) {
            renderTreeNode(dependents.get(i), childPrefix, i == dependents.size() - 1, repoDependents, printed);
        }
    }

    private static String withToken(String url, String token) {
        if (token == null || token.isBlank() || url == null) return url;
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return url;
            return new java.net.URI(scheme, "token:" + token, uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (java.net.URISyntaxException e) {
            return url;
        }
    }

    private String resolveSourceBranch(Path repoDir, String requested) {
        if (!"main".equals(requested)) return requested;
        if (gitService.hasRemoteBranch(repoDir, "main")) return "main";
        log.info("    Branch 'main' not found in {}, falling back to 'master'", repoDir.getFileName());
        return "master";
    }

    private void applyVersionOverride(Path repoDir, String version) {
        log.info("    Applying version override {} in {}", version, repoDir.getFileName());
        pomVersionUpdater.setVersions(repoDir, version);
        boolean committed = gitService.commitAllIfDirty(repoDir, "chore: set version to " + version);
        if (committed) {
            if (dryMode) {
                log.info("    Dry mode — skipping push for {}", repoDir.getFileName());
            } else {
                gitService.push(repoDir);
            }
        }
    }

    private ResumeState loadResumeState(Path stateFile) {
        if (Files.exists(stateFile)) {
            try {
                ResumeState state = objectMapper.readValue(stateFile.toFile(), ResumeState.class);
                log.info("Resuming from state file: {}", stateFile.toAbsolutePath());
                log.info("  Already completed: {}", state.getCompletedRepoNames());
                if (state.isReleasePhase1Complete()) {
                    log.info("  Release phase 1 already complete");
                }
                return state;
            } catch (IOException e) {
                log.warn("Could not read resume state from {}: {}", stateFile, e.getMessage());
            }
        }
        return new ResumeState();
    }

    private void saveResumeState(ResumeState state, Path stateFile) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
            log.info("Resume state saved to: {}", stateFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not save resume state to {}: {}", stateFile, e.getMessage());
        }
    }

    static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
