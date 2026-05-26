package com.example.multibuild.session;

import com.example.multibuild.build.BuildFailedException;
import com.example.multibuild.build.DispatchingBuildService;
import com.example.multibuild.event.BuildProgressEvent;
import com.example.multibuild.git.GitService;
import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.logging.SessionLogAppender;
import com.example.multibuild.model.*;
import com.example.multibuild.model.Module;
import com.example.multibuild.pipeline.BuildContext;
import com.example.multibuild.service.BranchService;
import com.example.multibuild.service.DependencyVersionService;
import com.example.multibuild.service.ProjectAggregator;
import com.example.multibuild.web.dto.BuildEventDto;
import com.example.multibuild.web.dto.GraphResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class BuildOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BuildOrchestrator.class);

    private final ProjectAggregator aggregator;
    private final DispatchingBuildService buildService;
    private final SessionLogAppender logAppender;
    private final GitService gitService;
    private final ApplicationEventPublisher publisher;
    private final BranchService branchService;
    private final DependencyVersionService dependencyVersionService;

    public BuildOrchestrator(ProjectAggregator aggregator, DispatchingBuildService buildService,
                             SessionLogAppender logAppender, GitService gitService,
                             ApplicationEventPublisher publisher, BranchService branchService,
                             DependencyVersionService dependencyVersionService) {
        this.aggregator = aggregator;
        this.buildService = buildService;
        this.logAppender = logAppender;
        this.gitService = gitService;
        this.publisher = publisher;
        this.branchService = branchService;
        this.dependencyVersionService = dependencyVersionService;
    }

    public void start(BuildSession session, BuildContext context) {
        Thread thread = new Thread(() -> runBuild(session, context));
        thread.setDaemon(true);
        thread.start();
        session.setExecutionThread(thread);
    }

    private void runBuild(BuildSession session, BuildContext context) {
        long buildStartNs = System.nanoTime();
        session.setExecutionThread(Thread.currentThread());
        MDC.put("sessionId", session.getId());
        logAppender.register(session.getId(), session);
        session.setStatus(BuildSession.Status.RUNNING);

        try {
            Files.createDirectories(context.cloneDir());

            List<Path> repoPaths;
            if (context.skipGit()) {
                repoPaths = resolveRepoPaths(context);
            } else {
                repoPaths = cloneAndBranch(context);
            }

            Map<Path, RepoConfig> repoConfigByPath = buildRepoConfigMap(context);

            if (context.buildEnabled() && context.buildMode() != BuildMode.RELEASE && !context.skipGit()) {
                List<Path> pathsToValidate = repoPaths.stream()
                        .filter(p -> !repoConfigByPath.get(p).hasVersionOverride())
                        .toList();
                branchService.validateVersions(pathsToValidate, repoConfigByPath,
                        context.integrationBranch(), context.dryMode());
            }

            List<RepositoryProject> projects = aggregator.aggregate(repoPaths);
            DependencyGraph<Path> graph = aggregator.buildGraph(projects);
            List<List<Path>> buildLayers = graph.topologicalLayers();
            Map<Artifact, Module> moduleMap = buildModuleMap(projects);
            Map<Path, String> pathToName = buildPathToNameMap(context);
            int totalLayers = buildLayers.size();

            if (context.buildEnabled()) {
                dependencyVersionService.apply(moduleMap, repoPaths, repoConfigByPath,
                        context.integrationBranch(), context.dryMode());
            }

            for (int i = 0; i < totalLayers; i++) {
                List<Path> layer = buildLayers.get(i);
                List<String> layerNames = layer.stream()
                        .map(p -> pathToName.getOrDefault(p, p.getFileName().toString()))
                        .toList();

                publish(session, new BuildEventDto.LayerStarted(i, totalLayers, layerNames));
                for (String name : layerNames) {
                    publish(session, new BuildEventDto.RepoStarted(name, i));
                }

                long layerNs = System.nanoTime();
                try {
                    buildService.buildAll(List.of(layer), moduleMap, repoConfigByPath,
                            Collections.emptySet(), Collections.emptyMap());
                    long ms = (System.nanoTime() - layerNs) / 1_000_000;
                    for (String name : layerNames) {
                        publish(session, new BuildEventDto.RepoFinished(name, true, null, ms));
                    }
                } catch (BuildFailedException e) {
                    long ms = (System.nanoTime() - layerNs) / 1_000_000;
                    Set<String> succeeded = e.getSucceeded().stream()
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.toSet());
                    for (String name : layerNames) {
                        boolean ok = succeeded.contains(name);
                        publish(session, new BuildEventDto.RepoFinished(name, ok, ok ? null : e.getMessage(), ms));
                    }
                    throw e;
                }
            }

            long totalMs = (System.nanoTime() - buildStartNs) / 1_000_000;
            publish(session, new BuildEventDto.BuildFinished(true, null, totalMs));
            session.setStatus(BuildSession.Status.SUCCESS);

        } catch (BuildFailedException e) {
            long totalMs = (System.nanoTime() - buildStartNs) / 1_000_000;
            publish(session, new BuildEventDto.BuildFinished(false, e.getMessage(), totalMs));
            session.setStatus(BuildSession.Status.FAILED);
            session.setErrorMessage(e.getMessage());

        } catch (CancellationException e) {
            session.setStatus(BuildSession.Status.CANCELLED);
            Thread.currentThread().interrupt();

        } catch (Exception e) {
            log.error("Build session {} failed unexpectedly", session.getId(), e);
            session.setStatus(BuildSession.Status.FAILED);
            session.setErrorMessage(e.getMessage());
            long totalMs = (System.nanoTime() - buildStartNs) / 1_000_000;
            publish(session, new BuildEventDto.BuildFinished(false, e.getMessage(), totalMs));

        } finally {
            logAppender.deregister(session.getId());
            MDC.clear();
        }
    }

    public GraphResponse preview(BuildContext context) {
        try {
            Files.createDirectories(context.cloneDir());

            List<Path> repoPaths = context.skipGit()
                    ? context.repos().stream()
                            .map(r -> context.cloneDir().resolve(repoName(r.getUrl())))
                            .toList()
                    : cloneReposOnly(context);

            List<RepositoryProject> projects = aggregator.aggregate(repoPaths);
            DependencyGraph<Path> graph = aggregator.buildGraph(projects);
            List<List<Path>> layers = graph.topologicalLayers();

            Map<String, String> nameToSourceBranch = new HashMap<>();
            Map<String, String> buildServiceByName = new HashMap<>();
            for (RepoConfig r : context.repos()) {
                String name = repoName(r.getUrl());
                nameToSourceBranch.put(name, r.getEffectiveSourceBranch("main"));
                BuildServiceType type = r.getBuildService() != null ? r.getBuildService() : context.buildService();
                if (type != null) buildServiceByName.put(name, type.name());
            }

            // version per repo: use first module's artifact version (all modules share parent version)
            Map<String, String> versionByName = new HashMap<>();
            for (RepositoryProject project : projects) {
                if (!project.getModules().isEmpty()) {
                    versionByName.put(
                            project.getRepoRoot().getFileName().toString(),
                            project.getModules().get(0).getArtifact().getVersion());
                }
            }

            // dependency artifacts per directed edge (fromRepo → toRepo)
            Map<String, String> repoNameByKey = new HashMap<>();
            for (RepositoryProject project : projects) {
                String name = project.getRepoRoot().getFileName().toString();
                for (Module module : project.getModules()) {
                    repoNameByKey.put(module.getArtifact().key(), name);
                }
            }
            Map<String, LinkedHashSet<String>> edgeDepsMap = new LinkedHashMap<>();
            for (RepositoryProject project : projects) {
                String fromName = project.getRepoRoot().getFileName().toString();
                for (Artifact parent : project.getAggregatorParents()) {
                    String toName = repoNameByKey.get(parent.key());
                    if (toName == null || toName.equals(fromName)) continue;
                    edgeDepsMap.computeIfAbsent(fromName + "→" + toName, k -> new LinkedHashSet<>())
                               .add(parent.toString());
                }
                for (Module module : project.getModules()) {
                    for (com.example.multibuild.model.Dependency dep : module.getDependencies()) {
                        String toName = repoNameByKey.get(dep.getArtifact().key());
                        if (toName == null || toName.equals(fromName)) continue;
                        edgeDepsMap.computeIfAbsent(fromName + "→" + toName, k -> new LinkedHashSet<>())
                                   .add(dep.getArtifact().toString());
                    }
                }
            }

            List<GraphResponse.NodeDto> nodes = repoPaths.stream()
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return new GraphResponse.NodeDto(
                                name,
                                nameToSourceBranch.getOrDefault(name, "main"),
                                versionByName.get(name),
                                buildServiceByName.get(name));
                    })
                    .toList();

            Set<String> nodeNames = nodes.stream().map(GraphResponse.NodeDto::id).collect(Collectors.toSet());

            List<GraphResponse.EdgeDto> edges = new ArrayList<>();
            for (Map.Entry<Path, List<Path>> entry : graph.getAdjacencyList().entrySet()) {
                String from = entry.getKey().getFileName().toString();
                for (Path dep : entry.getValue()) {
                    String to = dep.getFileName().toString();
                    if (nodeNames.contains(from) && nodeNames.contains(to)) {
                        List<String> deps = new ArrayList<>(
                                edgeDepsMap.getOrDefault(from + "→" + to, new LinkedHashSet<>()));
                        edges.add(new GraphResponse.EdgeDto(from, to, deps));
                    }
                }
            }

            List<List<String>> layerNames = layers.stream()
                    .map(layer -> layer.stream().map(p -> p.getFileName().toString()).toList())
                    .toList();

            return new GraphResponse(nodes, edges, layerNames);

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize clone directory: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────

    private List<Path> cloneAndBranch(BuildContext context) {
        int total = context.repos().size();
        Path[] results = new Path[total];
        var executor = Executors.newCachedThreadPool();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                final int idx = i;
                RepoConfig repo = context.repos().get(idx);
                String name = repoName(repo.getUrl());
                String cloneUrl = context.gitAuthTokenInUrl()
                        ? withToken(repo.getUrl(), context.githubToken())
                        : repo.getUrl();
                futures.add(CompletableFuture.runAsync(() -> {
                    log.info("Cloning {}", name);
                    Path cloned = gitService.cloneRepo(cloneUrl, context.cloneDir().resolve(name));
                    String sourceBranch = resolveSourceBranch(cloned,
                            repo.getEffectiveSourceBranch(context.defaultSourceBranch()));
                    branchService.apply(cloned, sourceBranch, repo,
                            context.integrationBranch(), context.dryMode());
                    if (repo.hasVersionOverride()) {
                        branchService.applyVersionOverride(cloned, repo.getVersion(), repo, context.dryMode());
                    }
                    results[idx] = cloned;
                }, executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException(cause.getMessage(), cause);
            }
        } finally {
            executor.shutdown();
        }
        return Arrays.stream(results).filter(Objects::nonNull).toList();
    }

    private List<Path> cloneReposOnly(BuildContext context) {
        int total = context.repos().size();
        Path[] results = new Path[total];
        var executor = Executors.newCachedThreadPool();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                final int idx = i;
                RepoConfig repo = context.repos().get(idx);
                String name = repoName(repo.getUrl());
                String cloneUrl = context.gitAuthTokenInUrl()
                        ? withToken(repo.getUrl(), context.githubToken())
                        : repo.getUrl();
                futures.add(CompletableFuture.runAsync(() -> {
                    log.info("Cloning {}", name);
                    results[idx] = gitService.cloneRepo(cloneUrl, context.cloneDir().resolve(name));
                }, executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException(cause.getMessage(), cause);
            }
        } finally {
            executor.shutdown();
        }
        return Arrays.stream(results).filter(Objects::nonNull).toList();
    }

    private String resolveSourceBranch(Path repoDir, String requested) {
        if (!"main".equals(requested)) return requested;
        if (gitService.hasRemoteBranch(repoDir, "main")) return "main";
        log.info("Branch 'main' not found in {}, falling back to 'master'", repoDir.getFileName());
        return "master";
    }

    private void publish(BuildSession session, BuildEventDto event) {
        publisher.publishEvent(new BuildProgressEvent(session.getId(), event));
    }

    private Map<Path, String> buildPathToNameMap(BuildContext context) {
        Map<Path, String> map = new LinkedHashMap<>();
        for (RepoConfig r : context.repos()) {
            String name = repoName(r.getUrl());
            map.put(context.cloneDir().resolve(name), name);
        }
        return map;
    }

    private static Map<Artifact, Module> buildModuleMap(List<RepositoryProject> projects) {
        return projects.stream()
                .flatMap(p -> p.getModules().stream())
                .collect(Collectors.toMap(Module::getArtifact, m -> m));
    }

    private List<Path> resolveRepoPaths(BuildContext context) {
        return context.repos().stream()
                .map(r -> context.cloneDir().resolve(repoName(r.getUrl())))
                .toList();
    }

    private Map<Path, RepoConfig> buildRepoConfigMap(BuildContext context) {
        Map<Path, RepoConfig> map = new LinkedHashMap<>();
        for (RepoConfig repo : context.repos()) {
            map.put(context.cloneDir().resolve(repoName(repo.getUrl())), repo);
        }
        return map;
    }

    private static String repoName(String url) {
        if (url == null) return "unknown";
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
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
}
