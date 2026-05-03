package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Routes each repository in a build layer to the appropriate BuildService implementation
 * based on the per-repo buildService setting in RepoConfig (falling back to the global
 * build.service property). Different service groups within the same layer run concurrently.
 */
@Service
@Primary
public class DispatchingBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(DispatchingBuildService.class);

    @Value("${build.service:LOCAL}")
    private BuildServiceType defaultBuildService;

    private final BuildService localService;
    private final BuildService teamCityService;
    private final BuildService lightspeedService;

    public DispatchingBuildService(@Qualifier("local") BuildService localService,
                                   @Qualifier("teamcity") BuildService teamCityService,
                                   @Qualifier("lightspeed") BuildService lightspeedService) {
        this.localService = localService;
        this.teamCityService = teamCityService;
        this.lightspeedService = lightspeedService;
    }

    @Override
    public void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo) {
        Set<Path> overallSucceeded = new LinkedHashSet<>();

        for (List<Artifact> layer : layers) {
            // Group artifacts by service type so we can log the routing and run groups concurrently
            Map<BuildServiceType, List<Artifact>> byType = new LinkedHashMap<>();
            for (Artifact a : layer) {
                Module m = moduleMap.get(a);
                if (m == null) continue;
                BuildServiceType type = resolveType(repoConfigs.get(m.getRepoRoot()));
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(a);
            }
            if (byType.isEmpty()) continue;

            // Log routing summary for this layer
            byType.forEach((type, artifacts) -> {
                String repos = artifacts.stream()
                        .map(a -> { Module m = moduleMap.get(a);
                            return m != null ? m.getRepoRoot().getFileName().toString() : a.getArtifactId(); })
                        .distinct().collect(Collectors.joining(", "));
                log.info("  [{}] {}", type, repos);
            });

            if (byType.size() == 1) {
                Map.Entry<BuildServiceType, List<Artifact>> entry = byType.entrySet().iterator().next();
                try {
                    resolve(entry.getKey()).buildAll(List.of(entry.getValue()), moduleMap, repoConfigs,
                            completedRepoNames, buildBranchByRepo);
                    collectSucceeded(entry.getValue(), moduleMap, completedRepoNames, overallSucceeded);
                } catch (BuildFailedException e) {
                    overallSucceeded.addAll(e.getSucceeded());
                    throw new BuildFailedException(e.getMessage(), overallSucceeded);
                }
            } else {
                log.info("  Running {} service group(s) in parallel", byType.size());
                Set<Path> layerSucceeded = ConcurrentHashMap.newKeySet();
                List<String> layerFailures = Collections.synchronizedList(new ArrayList<>());

                List<CompletableFuture<Void>> futures = byType.entrySet().stream()
                        .map(e -> CompletableFuture.runAsync(() -> {
                            try {
                                resolve(e.getKey()).buildAll(List.of(e.getValue()), moduleMap, repoConfigs,
                                        completedRepoNames, buildBranchByRepo);
                                collectSucceeded(e.getValue(), moduleMap, completedRepoNames, layerSucceeded);
                            } catch (BuildFailedException ex) {
                                layerSucceeded.addAll(ex.getSucceeded());
                                layerFailures.add(ex.getMessage());
                            } catch (RuntimeException ex) {
                                layerFailures.add(ex.getMessage());
                            }
                        }))
                        .toList();

                futures.forEach(CompletableFuture::join);
                overallSucceeded.addAll(layerSucceeded);

                if (!layerFailures.isEmpty()) {
                    throw new BuildFailedException(
                            layerFailures.size() + " build service group(s) failed:\n" +
                            String.join("\n---\n", layerFailures),
                            overallSucceeded);
                }
            }
        }
    }

    private BuildServiceType resolveType(RepoConfig config) {
        return (config != null && config.getBuildService() != null)
                ? config.getBuildService()
                : defaultBuildService;
    }

    private BuildService resolve(BuildServiceType type) {
        return switch (type) {
            case TEAMCITY -> teamCityService;
            case LIGHTSPEED -> lightspeedService;
            default -> localService;
        };
    }

    private static void collectSucceeded(List<Artifact> artifacts, Map<Artifact, Module> moduleMap,
                                         Set<String> completedRepoNames, Set<Path> target) {
        artifacts.stream()
                .map(moduleMap::get)
                .filter(m -> m != null &&
                        !completedRepoNames.contains(m.getRepoRoot().getFileName().toString()))
                .map(Module::getRepoRoot)
                .distinct()
                .forEach(target::add);
    }
}
