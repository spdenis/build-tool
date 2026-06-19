package com.example.multibuild.app;

import com.example.multibuild.build.BuildService;
import com.example.multibuild.git.GitHubService;
import com.example.multibuild.git.GitService;
import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.*;
import com.example.multibuild.model.Module;
import com.example.multibuild.service.*;
import com.example.multibuild.udeploy.UDeployService;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
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

    @Value("${skip.git:false}")
    private boolean skipGit;

    @Value("${build.target:}")
    private String buildTarget;

    @Value("${build.target.with-deps:false}")
    private boolean buildTargetWithDeps;

    @Value("${build.pause-after:}")
    private String pauseAfterRepo;

    private final GitService gitService;
    private final BranchService branchService;
    private final PomVersionUpdater pomVersionUpdater;
    private final ProjectAggregator aggregator;
    private final DependencyVersionService dependencyVersionService;
    private final BuildService buildService;
    private final ReleaseService releaseService;
    private final ObjectMapper objectMapper;
    private final CommitMessageFormatter commitFormatter;
    private final GitHubService gitHubService;
    private final UDeployService uDeployService;

    public Main(GitService gitService, BranchService branchService,
                PomVersionUpdater pomVersionUpdater,
                ProjectAggregator aggregator, DependencyVersionService dependencyVersionService,
                BuildService buildService, ReleaseService releaseService, ObjectMapper objectMapper,
                CommitMessageFormatter commitFormatter, GitHubService gitHubService,
                UDeployService uDeployService) {
        this.gitService = gitService;
        this.branchService = branchService;
        this.pomVersionUpdater = pomVersionUpdater;
        this.aggregator = aggregator;
        this.dependencyVersionService = dependencyVersionService;
        this.buildService = buildService;
        this.releaseService = releaseService;
        this.objectMapper = objectMapper;
        this.commitFormatter = commitFormatter;
        this.gitHubService = gitHubService;
        this.uDeployService = uDeployService;
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }
        switch (args[0]) {
            case "build"           -> runBuild(args[1]);
            case "release-init"    -> runReleaseInit(args[1]);
            case "create-prs"      -> runCreatePrs(args[1]);
            case "update-snapshot" -> runUpdateSnapshot(args[1]);
            default -> {
                System.err.println("[ERROR] Unknown command: " + args[0]);
                printUsage();
                System.exit(1);
            }
        }
    }

    private void runBuild(String reposFile) throws Exception {
        if (branchService.getIntegrationBranch().isBlank()) {
            System.err.println("[ERROR] integration.branch is required but not set.");
            System.exit(1);
        }

        List<RepoConfig> repoEntries = loadRepos(reposFile);

        log.info("══════════════════════════════════════════════════════");
        log.info("  MultiBuild  mode={}  repos={}  build={}  dryMode={}  skipGit={}",
                buildMode, repoEntries.size(),
                buildEnabled ? buildMode : "disabled", dryMode, skipGit);
        if (!buildTarget.isBlank()) {
            log.info("  build.target={}  with-deps={}", buildTarget, buildTargetWithDeps);
        }
        if (!pauseAfterRepo.isBlank()) {
            log.info("  build.pause-after={}", pauseAfterRepo);
        }
        log.info("══════════════════════════════════════════════════════");

        Path workDir = Paths.get(cloneDir);
        Files.createDirectories(workDir);

        Path stateFile = resumeStateFile.isBlank()
                ? workDir.resolve(".multibuild-resume.json")
                : Paths.get(resumeStateFile);

        ResumeState resumeState = loadResumeState(stateFile);
        resumeState.setOnUpdate(() -> saveResumeState(resumeState, stateFile));
        // Create the state file immediately from repos.json so it exists even if cloning fails.
        resumeState.initReposFromConfigs(repoEntries);
        saveResumeState(resumeState, stateFile);

        if (skipGit) {
            log.info("── Phase 1/3: skipped — using local repos in {} ──", workDir);
        } else {
            log.info("── Phase 1/3: clone & branch ({} repo(s), parallel) ──", repoEntries.size());
        }
        CloneResult cloned = skipGit ? useLocalRepos(repoEntries, workDir) : cloneAndBranch(repoEntries, workDir);
        List<Path> clonedPaths = cloned.paths();
        Map<Path, RepoConfig> repoConfigByPath = cloned.configByPath();

        // Push any commits left unpushed by a prior dry-mode run before starting the build.
        // This is a no-op when local is already in sync with remote.
        if (!dryMode) {
            for (Path repoRoot : clonedPaths) {
                RepoConfig config = repoConfigByPath.get(repoRoot);
                if (config == null || !config.isDryRun()) {
                    gitService.pushIfAhead(repoRoot);
                }
            }
        }

        if (buildEnabled && buildMode != BuildMode.RELEASE && !skipGit) {
            List<Path> pathsToValidate = clonedPaths.stream()
                    .filter(p -> !repoConfigByPath.get(p).hasVersionOverride())
                    .toList();
            try {
                branchService.validateVersions(pathsToValidate, repoConfigByPath);
            } catch (RuntimeException e) {
                log.error("Version validation failed", e);
                System.err.println("\n[BUILD FAILED]\n" + e.getMessage());
                System.exit(1);
            }
        }

        // ── Phase: parse & graph ───────────────────────────────────
        log.info("── Phase 2/3: parse pom.xml & resolve dependency graph ");
        List<RepositoryProject> projects = aggregator.aggregate(clonedPaths);
        DependencyGraph<Path> graph = aggregator.buildGraph(projects);
        List<List<Path>> buildLayers = graph.topologicalLayers();
        if (!buildTarget.isBlank()) {
            buildLayers = filterToTarget(buildLayers, graph, clonedPaths);
        }
        // Capture full layer list before any pause truncation so the state file always shows all repos.
        List<List<Path>> allLayers = buildLayers;
        boolean willPause = false;
        if (!pauseAfterRepo.isBlank()) {
            int pauseIdx = findPauseLayerIndex(buildLayers, pauseAfterRepo);
            if (pauseIdx < 0) {
                String available = buildLayers.stream().flatMap(List::stream)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.joining(", "));
                System.err.println("[ERROR] build.pause-after '" + pauseAfterRepo + "' not found. Available: " + available);
                System.exit(1);
            }
            boolean alreadyDone = buildLayers.get(pauseIdx).stream()
                    .map(p -> p.getFileName().toString())
                    .allMatch(resumeState.getCompletedRepoNames()::contains);
            if (alreadyDone) {
                log.info("  '{}' already completed — build.pause-after ignored, continuing full build", pauseAfterRepo);
            } else {
                long deferred = buildLayers.subList(pauseIdx + 1, buildLayers.size()).stream()
                        .flatMap(List::stream).count();
                log.info("  Will pause after layer {}/{} ('{}'); {} downstream repo(s) deferred",
                        pauseIdx + 1, buildLayers.size(), pauseAfterRepo, deferred);
                buildLayers = buildLayers.subList(0, pauseIdx + 1);
                willPause = true;
            }
        }
        List<Path> buildOrder = buildLayers.stream().flatMap(List::stream).toList();

        // Initialise state with every repo as PENDING (preserves existing status on resume).
        // Saved to disk immediately so the file exists even if the process crashes before the first build.
        resumeState.initRepos(allLayers, repoConfigByPath);
        saveResumeState(resumeState, stateFile);

        log.info("  {} repo(s) in {} layer(s):", clonedPaths.size(), buildLayers.size());
        for (int i = 0; i < buildLayers.size(); i++) {
            List<Path> layer = buildLayers.get(i);
            String repos = layer.stream().map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            log.info("    Layer {}/{}: {} repo(s) — {}", i + 1, buildLayers.size(), layer.size(), repos);
        }
        logDependencyTree(graph);

        Map<Artifact, Module> moduleMap = projects.stream()
                .flatMap(p -> p.getModules().stream())
                .collect(Collectors.toMap(Module::getArtifact, m -> m));

        // Pre-compute version for each repo so the callback can record what was actually built.
        Map<Path, String> versionByRepo = new LinkedHashMap<>();
        for (Map.Entry<Artifact, Module> e : moduleMap.entrySet()) {
            versionByRepo.putIfAbsent(e.getValue().getRepoRoot(), e.getKey().getVersion());
        }

        // ── Phase: build ───────────────────────────────────────────
        if (buildEnabled) {
            log.info("── Phase 3/3: build ({}) ─────────────────────────────", buildMode);
            long buildStart = System.currentTimeMillis();
            try {
                if (buildMode == BuildMode.RELEASE) {
                    List<Path> releaseRepoRoots = buildTarget.isBlank()
                            ? clonedPaths
                            : allLayers.stream().flatMap(List::stream).toList();
                    releaseService.execute(buildLayers, moduleMap, releaseRepoRoots, repoConfigByPath, resumeState);
                    if (willPause) {
                        long total = allLayers.stream().flatMap(List::stream).count();
                        log.info("══ Release PAUSED after '{}' (Phase 1 complete, {}/{} repo(s) built) ══",
                                pauseAfterRepo, resumeState.getCompletedRepoNames().size(), total);
                        log.info("  Resume when ready: re-run with --build.resume.state.file={}",
                                stateFile.toAbsolutePath());
                        return;
                    }
                } else {
                    // On resume, completed repos' built versions override their current pom versions
                    // (which may have been bumped). Completed repos are excluded from the update
                    // target — their poms are already correct from the first run.
                    Map<Path, String> builtVersionsByRepo = resumeState.getCompletedVersionsByRepo(buildOrder);
                    List<Path> reposToUpdate = buildOrder.stream()
                            .filter(r -> !builtVersionsByRepo.containsKey(r))
                            .toList();
                    dependencyVersionService.apply(moduleMap, reposToUpdate, repoConfigByPath, builtVersionsByRepo);
                    BiConsumer<Path, String> onRepoComplete = (repoRoot, error) -> {
                        if (error == null) resumeState.markCompleted(repoRoot, versionByRepo.get(repoRoot));
                        else resumeState.markFailed(repoRoot, error);
                    };
                    buildService.buildAll(buildLayers, moduleMap, repoConfigByPath,
                            resumeState.getCompletedRepoNames(), Collections.emptyMap(), onRepoComplete);
                    if (willPause) {
                        long total = allLayers.stream().flatMap(List::stream).count();
                        log.info("══ Build PAUSED after '{}' ({}/{} repo(s) complete) ══════════",
                                pauseAfterRepo, resumeState.getCompletedRepoNames().size(), total);
                        log.info("  Resume when ready: re-run with --build.resume.state.file={}",
                                stateFile.toAbsolutePath());
                        return;
                    }
                }
                // Keep the completed build as a permanent record at a stable path.
                // The resume file is removed so the next fresh run starts clean.
                Path buildRecord = workDir.resolve(".multibuild-last-build.json");
                saveResumeState(resumeState, buildRecord);
                Files.deleteIfExists(stateFile);
                log.info("══ Build complete in {} — record saved to {} ══",
                        elapsed(buildStart), buildRecord.getFileName());
            } catch (RuntimeException e) {
                log.error("Build failed", e);
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

        for (Artifact a : moduleMap.keySet()) {
            result.artifacts.add(new BuildResult.ArtifactDto(a));
        }

        for (Map.Entry<Path, List<Path>> entry : graph.getAdjacencyList().entrySet()) {
            String from = entry.getKey().getFileName().toString();
            for (Path dep : entry.getValue()) {
                result.dependencies.add(new BuildResult.DependencyDto(from, dep.getFileName().toString()));
            }
        }

        for (Path p : buildOrder) {
            result.buildOrder.add(p.getFileName().toString());
        }

        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private void runReleaseInit(String reposFile) throws Exception {
        if (branchService.getIntegrationBranch().isBlank()) {
            System.err.println("[ERROR] integration.branch is required but not set.");
            System.exit(1);
        }

        List<RepoConfig> repoEntries = loadRepos(reposFile);
        Path workDir = Paths.get(cloneDir);
        Files.createDirectories(workDir);

        if (skipGit) {
            log.info("── Release init: skipped — using local repos in {} ──", workDir);
        } else {
            log.info("── Release init: clone & branch ({} repo(s), parallel) ──", repoEntries.size());
        }
        CloneResult result = skipGit ? useLocalRepos(repoEntries, workDir) : cloneAndBranch(repoEntries, workDir);
        log.info("══ Release init complete — {} repo(s) branched ══════════", result.paths().size());
    }

    private void runCreatePrs(String reposFile) throws Exception {
        if (branchService.getIntegrationBranch().isBlank()) {
            System.err.println("[ERROR] integration.branch is required but not set.");
            System.exit(1);
        }

        List<RepoConfig> repoEntries = loadRepos(reposFile);

        String integrationBranch = branchService.getIntegrationBranch();
        log.info("── Creating pull requests: {} → source branch ({} repo(s)) ──",
                integrationBranch, repoEntries.size());

        List<String> prUrls = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (RepoConfig entry : repoEntries) {
            String repoUrl = entry.getUrl();
            String repoName = repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
            String sourceBranch = entry.getEffectiveSourceBranch(defaultSourceBranch);
            String title = "Merge " + integrationBranch + " into " + sourceBranch;
            try {
                String prUrl = gitHubService.createOrFindPr(repoUrl, integrationBranch, sourceBranch, title);
                prUrls.add(prUrl);
                log.info("  {} — PR ready", repoName);
            } catch (Exception e) {
                log.error("  {} — FAILED", repoName, e);
                errors.add(repoName + ": " + e.getMessage());
            }
        }

        log.info("── Pull request URLs ─────────────────────────────────────");
        prUrls.forEach(url -> log.info("  {}", url));

        if (!errors.isEmpty()) {
            System.err.println("\n[ERRORS]");
            errors.forEach(e -> System.err.println("  " + e));
            System.exit(1);
        }
    }

    private void runUpdateSnapshot(String reposFile) throws Exception {
        List<RepoConfig> repoEntries = loadRepos(reposFile);
        log.info("── Update uDeploy snapshot: {}/{}  mode={}  repos={} ──",
                buildMode, repoEntries.size(), buildMode, repoEntries.size());
        try {
            uDeployService.updateSnapshot(repoEntries, buildMode);
            log.info("══ uDeploy snapshot update complete ══════════════════");
        } catch (RuntimeException e) {
            log.error("uDeploy snapshot update failed", e);
            System.err.println("\n[FAILED]\n" + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar multibuild.jar build           repos.json");
        System.err.println("       java -jar multibuild.jar release-init    repos.json");
        System.err.println("       java -jar multibuild.jar create-prs      repos.json");
        System.err.println("       java -jar multibuild.jar update-snapshot repos.json");
    }

    private record CloneResult(List<Path> paths, Map<Path, RepoConfig> configByPath) {}

    private List<RepoConfig> loadRepos(String reposFile) throws IOException {
        List<RepoConfig> repos = objectMapper.readValue(
                Paths.get(reposFile).toFile(), new TypeReference<List<RepoConfig>>() {});
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (RepoConfig entry : repos) {
            if (!seen.add(entry.getUrl())) duplicates.add(entry.getUrl());
        }
        if (!duplicates.isEmpty()) {
            System.err.println("[ERROR] Duplicate repository URL(s) in " + reposFile + ":");
            duplicates.forEach(u -> System.err.println("  " + u));
            System.exit(1);
        }
        return repos;
    }

    private CloneResult cloneAndBranch(List<RepoConfig> repoEntries, Path workDir) {
        int total = repoEntries.size();
        // Pre-sized array keeps insertion order without synchronization (each index written by one thread)
        Path[] clonedArray = new Path[total];
        var executor = Executors.newCachedThreadPool();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                final int idx = i;
                RepoConfig entry = repoEntries.get(i);
                String url = entry.getUrl();
                String repoName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");
                String cloneUrl = gitAuthTokenInUrl ? withToken(url, githubToken) : url;
                futures.add(CompletableFuture.runAsync(() -> {
                    log.info("  Cloning {}", repoName);
                    try {
                        Path cloned = gitService.cloneRepo(cloneUrl, workDir.resolve(repoName));
                        branchService.apply(cloned, resolveSourceBranch(cloned, entry.getEffectiveSourceBranch(defaultSourceBranch)), entry);
                        if (entry.hasVersionOverride()) {
                            applyVersionOverride(cloned, entry.getVersion(), entry);
                        }
                        clonedArray[idx] = cloned;
                        log.info("  Done: {}", repoName);
                    } catch (RuntimeException e) {
                        log.error("  Clone/branch failed for {}", repoName, e);
                        throw e;
                    }
                }, executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException ignored) {
                // collect all errors below
            }
            List<String> errors = futures.stream()
                    .filter(CompletableFuture::isCompletedExceptionally)
                    .map(f -> {
                        try { f.join(); return null; }
                        catch (CompletionException e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            return cause.getMessage() != null ? cause.getMessage() : cause.toString();
                        }
                    })
                    .toList();
            if (!errors.isEmpty()) {
                System.err.println("\n[FAILED]\n" + String.join("\n---\n", errors));
                System.exit(1);
            }
        } finally {
            executor.shutdown();
        }

        List<Path> paths = new ArrayList<>(total);
        Map<Path, RepoConfig> configByPath = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            if (clonedArray[i] != null) {
                paths.add(clonedArray[i]);
                configByPath.put(clonedArray[i], repoEntries.get(i));
            }
        }
        return new CloneResult(paths, configByPath);
    }

    private CloneResult useLocalRepos(List<RepoConfig> repoEntries, Path workDir) {
        List<Path> paths = new ArrayList<>();
        Map<Path, RepoConfig> configByPath = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (RepoConfig entry : repoEntries) {
            String url = entry.getUrl();
            String repoName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");
            Path repoDir = workDir.resolve(repoName);
            if (!repoDir.toFile().exists()) {
                missing.add(repoName + " (expected at " + repoDir.toAbsolutePath() + ")");
            } else {
                log.info("  Using local repo: {}", repoName);
                paths.add(repoDir);
                configByPath.put(repoDir, entry);
            }
        }

        if (!missing.isEmpty()) {
            System.err.println("[ERROR] skip.git=true but the following repos are not present in " + workDir + ":");
            missing.forEach(m -> System.err.println("  " + m));
            System.exit(1);
        }

        return new CloneResult(paths, configByPath);
    }

    private List<List<Path>> filterToTarget(List<List<Path>> layers, DependencyGraph<Path> graph,
                                            List<Path> allPaths) {
        Path targetPath = allPaths.stream()
                .filter(p -> p.getFileName().toString().equals(buildTarget))
                .findFirst()
                .orElse(null);
        if (targetPath == null) {
            String available = allPaths.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            System.err.println("[ERROR] build.target '" + buildTarget + "' not found. Available: " + available);
            System.exit(1);
            return layers;
        }

        Set<Path> keep = new LinkedHashSet<>();
        keep.add(targetPath);
        if (buildTargetWithDeps) {
            keep.addAll(graph.transitiveConsumersOf(targetPath));
            log.info("  Filtering build to '{}' + {} downstream repo(s): {}", buildTarget,
                    keep.size() - 1,
                    keep.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
        } else {
            log.info("  Filtering build to '{}' only", buildTarget);
        }

        return layers.stream()
                .map(layer -> layer.stream().filter(keep::contains).toList())
                .filter(layer -> !layer.isEmpty())
                .toList();
    }

    private int findPauseLayerIndex(List<List<Path>> layers, String repoName) {
        for (int i = 0; i < layers.size(); i++) {
            for (Path p : layers.get(i)) {
                if (p.getFileName().toString().equals(repoName)) return i;
            }
        }
        return -1;
    }

    private void logDependencyTree(DependencyGraph<Path> graph) {
        Map<Path, List<Path>> adjacency = graph.getAdjacencyList();

        Map<Path, Set<Path>> repoDependents = new LinkedHashMap<>();
        for (Path repo : adjacency.keySet()) {
            repoDependents.putIfAbsent(repo, new LinkedHashSet<>());
        }
        for (Map.Entry<Path, List<Path>> e : adjacency.entrySet()) {
            for (Path dep : e.getValue()) {
                repoDependents.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }

        List<Path> roots = adjacency.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (roots.isEmpty()) {
            roots = new ArrayList<>(adjacency.keySet());
        }

        log.info("── Dependency tree ───────────────────────────────────");
        Set<Path> printed = new LinkedHashSet<>();
        for (Path root : roots) {
            log.info("  {}", root.getFileName());
            printed.add(root);
            List<Path> dependents = new ArrayList<>(repoDependents.getOrDefault(root, Set.of()));
            for (int i = 0; i < dependents.size(); i++) {
                renderTreeNode(dependents.get(i), "", i == dependents.size() - 1, repoDependents, printed);
            }
        }
    }

    private void renderTreeNode(Path repo, String prefix, boolean last,
                                Map<Path, Set<Path>> repoDependents,
                                Set<Path> printed) {
        String connector = last ? "└── " : "├── ";
        if (printed.contains(repo)) {
            log.info("  {}{}{} ↑", prefix, connector, repo.getFileName());
            return;
        }
        log.info("  {}{}{}", prefix, connector, repo.getFileName());
        printed.add(repo);
        List<Path> dependents = new ArrayList<>(repoDependents.getOrDefault(repo, Set.of()));
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

    private void applyVersionOverride(Path repoDir, String version, RepoConfig repoConfig) {
        log.info("    Applying version override {} in {}", version, repoDir.getFileName());
        pomVersionUpdater.setVersions(repoDir, version);
        boolean committed = gitService.commitAllIfDirty(repoDir, commitFormatter.format("chore: set version to " + version));
        if (committed) {
            if (dryMode || repoConfig.isDryRun()) {
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
                log.warn("Could not read resume state from {}", stateFile, e);
            }
        }
        return new ResumeState();
    }

    private void saveResumeState(ResumeState state, Path stateFile) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
            log.info("Resume state saved to: {}", stateFile.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not save resume state to {}", stateFile, e);
        }
    }

    static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
