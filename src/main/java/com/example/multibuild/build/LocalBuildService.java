package com.example.multibuild.build;

import com.example.multibuild.buildsystem.BuildSystem;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
@Qualifier("local")
// Builds repos on this machine: `mvn <build.maven.goals>` for Maven repos, and
// `<gradle> <build.gradle.tasks>` for Gradle repos, where <gradle> is the repo's wrapper (./gradlew)
// when present, else build.gradle.command (default: `gradle` from PATH). The default Gradle tasks
// publish to the local Maven repository, so downstream repos — Maven or Gradle — resolve the
// freshly built artifacts exactly as after `mvn install`.
public class LocalBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(LocalBuildService.class);

    @Value("${build.maven.goals:clean install}")
    private String goals;

    @Value("${build.gradle.tasks:clean build publishToMavenLocal}")
    private String gradleTasks;

    @Value("${build.gradle.command:}")
    private String gradleCommand;

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
        List<String> cmd = command(repoRoot);
        String tool = BuildSystem.detect(repoRoot) == BuildSystem.GRADLE ? "Gradle" : "Maven";
        try {
            run(repoRoot, cmd, tool);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "Build failed in repository: " + repoRoot + "\n" +
                    "  Command: " + String.join(" ", cmd) + "\n" +
                    "  Reason : " + e.getMessage(), e);
        }
    }

    // Command line used to build the repo.
    List<String> command(Path repoRoot) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (BuildSystem.detect(repoRoot) == BuildSystem.GRADLE) {
            Path wrapper = repoRoot.resolve(isWindows ? "gradlew.bat" : "gradlew");
            if (Files.isRegularFile(wrapper)) {
                cmd.add(wrapper.toAbsolutePath().toString());
            } else if (gradleCommand != null && !gradleCommand.isBlank()) {
                cmd.add(gradleCommand.trim());
            } else {
                cmd.add(isWindows ? "gradle.bat" : "gradle");
            }
            cmd.addAll(Arrays.asList(gradleTasks.trim().split("\\s+")));
        } else {
            cmd.add(isWindows ? "mvn.cmd" : "mvn");
            cmd.addAll(Arrays.asList(goals.trim().split("\\s+")));
        }
        return cmd;
    }

    private void run(Path repoRoot, List<String> cmd, String tool) {
        try {
            int exitCode = new ProcessBuilder(cmd)
                    .directory(repoRoot.toFile())
                    .inheritIO()
                    .start()
                    .waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        tool + " build failed in " + repoRoot.getFileName() + " (exit code " + exitCode + ")");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to run " + tool + " in " + repoRoot.getFileName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while running " + tool + " in " + repoRoot.getFileName(), e);
        }
    }
}
