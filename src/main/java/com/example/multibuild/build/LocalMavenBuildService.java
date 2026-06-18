package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
@Qualifier("local")
public class LocalMavenBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(LocalMavenBuildService.class);

    @Value("${build.maven.goals:clean install}")
    private String goals;

    @Override
    public void buildAll(List<List<Path>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo,
                         BiConsumer<Path, String> onRepoComplete) {
        Set<Path> overallSucceeded = new LinkedHashSet<>();
        int layerNum = 0;

        for (List<Path> layer : layers) {
            layerNum++;
            List<Path> repos = layer.stream()
                    .filter(p -> !completedRepoNames.contains(p.getFileName().toString()))
                    .toList();
            if (repos.isEmpty()) continue;

            String repoNames = repos.stream().map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            log.info("  [LOCAL] Layer {}/{}: {} repo(s): {}", layerNum, layers.size(), repos.size(), repoNames);
            long layerStart = System.currentTimeMillis();

            Set<Path> layerSucceeded;
            List<String> layerFailures;

            if (repos.size() == 1) {
                layerSucceeded = new LinkedHashSet<>();
                layerFailures = new ArrayList<>();
                Path repo = repos.get(0);
                try {
                    buildRepo(repo);
                    onRepoComplete.accept(repo, null);
                    layerSucceeded.add(repo);
                } catch (RuntimeException e) {
                    onRepoComplete.accept(repo, e.getMessage());
                    layerFailures.add(e.getMessage());
                }
            } else {
                log.info("  [LOCAL] Running {} repo(s) in parallel", repos.size());
                Set<Path> concurrentSucceeded = ConcurrentHashMap.newKeySet();
                List<String> concurrentFailures = Collections.synchronizedList(new ArrayList<>());

                List<CompletableFuture<Void>> futures = repos.stream()
                        .map(repo -> CompletableFuture.runAsync(() -> {
                            try {
                                buildRepo(repo);
                                onRepoComplete.accept(repo, null);
                                concurrentSucceeded.add(repo);
                            } catch (RuntimeException e) {
                                onRepoComplete.accept(repo, e.getMessage());
                                concurrentFailures.add(e.getMessage());
                            }
                        }))
                        .toList();

                futures.forEach(CompletableFuture::join);
                layerSucceeded = new LinkedHashSet<>(concurrentSucceeded);
                layerFailures = concurrentFailures;
            }

            overallSucceeded.addAll(layerSucceeded);
            if (!layerFailures.isEmpty()) {
                throw new BuildFailedException(
                        layerFailures.size() + " repo(s) failed:\n" + String.join("\n---\n", layerFailures),
                        overallSucceeded);
            }
            log.info("  [LOCAL] Layer {}/{}: done in {}", layerNum, layers.size(), elapsed(layerStart));
        }
    }

    private static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void buildRepo(Path repoRoot) {
        log.info("  [LOCAL] Building {}", repoRoot.getFileName());
        try {
            runMaven(repoRoot);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "Build failed in repository: " + repoRoot + "\n" +
                    "  Goals  : " + goals + "\n" +
                    "  Reason : " + e.getMessage(), e);
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
