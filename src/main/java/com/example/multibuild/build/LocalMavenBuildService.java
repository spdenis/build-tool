package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "build.service", havingValue = "local", matchIfMissing = true)
public class LocalMavenBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(LocalMavenBuildService.class);

    @Value("${build.maven.goals:clean install}")
    private String goals;

    @Override
    public void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo) {
        Set<Path> overallSucceeded = new LinkedHashSet<>();

        for (List<Artifact> layer : layers) {
            LinkedHashMap<Path, List<String>> repoArtifacts = new LinkedHashMap<>();
            for (Artifact a : layer) {
                Module m = moduleMap.get(a);
                if (m == null) continue;
                if (completedRepoNames.contains(m.getRepoRoot().getFileName().toString())) continue;
                repoArtifacts.computeIfAbsent(m.getRepoRoot(), k -> new ArrayList<>())
                             .add(a.toString());
            }
            if (repoArtifacts.isEmpty()) continue;

            Set<Path> layerSucceeded;
            List<String> layerFailures;

            if (repoArtifacts.size() == 1) {
                Map.Entry<Path, List<String>> entry = repoArtifacts.entrySet().iterator().next();
                layerSucceeded = new LinkedHashSet<>();
                layerFailures = new ArrayList<>();
                try {
                    buildRepo(entry.getKey(), entry.getValue());
                    layerSucceeded.add(entry.getKey());
                } catch (RuntimeException e) {
                    layerFailures.add(e.getMessage());
                }
            } else {
                log.info("Building {} repos in parallel: {}", repoArtifacts.size(),
                        repoArtifacts.keySet().stream()
                                .map(p -> p.getFileName().toString())
                                .collect(Collectors.joining(", ")));

                Set<Path> concurrentSucceeded = ConcurrentHashMap.newKeySet();
                List<String> concurrentFailures = new ArrayList<>();

                List<CompletableFuture<Void>> futures = repoArtifacts.entrySet().stream()
                        .map(e -> CompletableFuture.runAsync(() -> {
                            buildRepo(e.getKey(), e.getValue());
                            concurrentSucceeded.add(e.getKey());
                        }))
                        .toList();

                for (CompletableFuture<Void> f : futures) {
                    try {
                        f.join();
                    } catch (CompletionException e) {
                        synchronized (concurrentFailures) {
                            concurrentFailures.add(e.getCause().getMessage());
                        }
                    }
                }
                layerSucceeded = new LinkedHashSet<>(concurrentSucceeded);
                layerFailures = concurrentFailures;
            }

            overallSucceeded.addAll(layerSucceeded);

            if (!layerFailures.isEmpty()) {
                String msg = layerFailures.size() + " repo(s) failed:\n" +
                        String.join("\n---\n", layerFailures);
                throw new BuildFailedException(msg, overallSucceeded);
            }
        }
    }

    private void buildRepo(Path repoRoot, List<String> artifactIds) {
        log.info("Building {} from root pom (artifacts: {})", repoRoot.getFileName(), artifactIds);
        try {
            runMaven(repoRoot);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "Build failed in repository: " + repoRoot + "\n" +
                    "  Artifacts : " + String.join(", ", artifactIds) + "\n" +
                    "  Goals     : " + goals + "\n" +
                    "  Reason    : " + e.getMessage(), e);
        }
    }

    private void runMaven(Path repoRoot) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        cmd.add(isWindows ? "mvn.cmd" : "mvn");
        cmd.addAll(Arrays.asList(goals.split("\\s+")));

        try {
            int exitCode = new ProcessBuilder(cmd)
                    .directory(repoRoot.toFile())
                    .inheritIO()
                    .start()
                    .waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Maven build failed in " + repoRoot.getFileName() + " (exit code " + exitCode + ")");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run Maven in " + repoRoot.getFileName(), e);
        }
    }
}
