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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "build.service", havingValue = "local", matchIfMissing = true)
public class LocalMavenBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(LocalMavenBuildService.class);

    @Value("${build.maven.goals:clean install}")
    private String goals;

    @Override
    public void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs) {
        for (List<Artifact> layer : layers) {
            LinkedHashSet<Path> repoRoots = new LinkedHashSet<>();
            for (Artifact a : layer) {
                Module m = moduleMap.get(a);
                if (m != null) repoRoots.add(m.getRepoRoot());
            }
            if (repoRoots.isEmpty()) continue;

            if (repoRoots.size() == 1) {
                buildRepo(repoRoots.iterator().next(), layer, moduleMap);
            } else {
                log.info("Building {} repos in parallel: {}", repoRoots.size(),
                        repoRoots.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
                List<CompletableFuture<Void>> futures = repoRoots.stream()
                        .map(repoRoot -> CompletableFuture.runAsync(
                                () -> buildRepo(repoRoot, layer, moduleMap)))
                        .toList();

                List<Throwable> failures = new ArrayList<>();
                for (CompletableFuture<Void> f : futures) {
                    try {
                        f.join();
                    } catch (CompletionException e) {
                        failures.add(e.getCause());
                    }
                }
                if (!failures.isEmpty()) {
                    String messages = failures.stream()
                            .map(Throwable::getMessage)
                            .collect(Collectors.joining("\n---\n"));
                    RuntimeException combined = new RuntimeException(
                            failures.size() + " repo(s) failed in parallel build layer:\n" + messages);
                    failures.subList(1, failures.size()).forEach(t -> combined.addSuppressed(t));
                    throw combined;
                }
            }
        }
    }

    private void buildRepo(Path repoRoot, List<Artifact> layer, Map<Artifact, Module> moduleMap) {
        List<String> artifactIds = layer.stream()
                .filter(a -> {
                    Module m = moduleMap.get(a);
                    return m != null && repoRoot.equals(m.getRepoRoot());
                })
                .map(Artifact::toString)
                .collect(Collectors.toList());

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
