package com.example.multibuild.service;

import com.example.multibuild.build.BuildFailedException;
import com.example.multibuild.build.BuildService;
import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.model.ResumeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    @Value("${dry.mode:false}")
    private boolean dryMode;

    private final PomVersionUpdater pomVersionUpdater;
    private final DependencyVersionUpdater dependencyVersionUpdater;
    private final GitService gitService;
    private final BuildService buildService;

    public ReleaseService(PomVersionUpdater pomVersionUpdater,
                          DependencyVersionUpdater dependencyVersionUpdater,
                          GitService gitService,
                          BuildService buildService) {
        this.pomVersionUpdater = pomVersionUpdater;
        this.dependencyVersionUpdater = dependencyVersionUpdater;
        this.gitService = gitService;
        this.buildService = buildService;
    }

    public void execute(List<List<Artifact>> buildLayers, Map<Artifact, Module> moduleMap,
                        List<Path> repoRoots, Map<Path, RepoConfig> repoConfigs,
                        ResumeState resumeState) {
        List<Artifact> buildOrder = buildLayers.stream().flatMap(List::stream).toList();

        // --- Phase 1: compute release versions, tag, push ---
        Map<String, String> releaseVersionByKey = new LinkedHashMap<>();
        Map<Path, String> releaseVersionByRepo = new LinkedHashMap<>();

        for (Path repoRoot : repoRoots) {
            String current = pomVersionUpdater.getRootVersion(repoRoot);
            if (current == null) {
                log.warn("Could not determine version for {}, skipping", repoRoot.getFileName());
                continue;
            }
            String release = baseVersion(current);
            releaseVersionByRepo.put(repoRoot, release);
            log.info("Release version for {}: {}", repoRoot.getFileName(), release);
        }

        for (Map.Entry<Artifact, Module> entry : moduleMap.entrySet()) {
            String rv = releaseVersionByRepo.get(entry.getValue().getRepoRoot());
            if (rv != null) releaseVersionByKey.put(entry.getKey().key(), rv);
        }

        if (!resumeState.isReleasePhase1Complete()) {
            for (Path repoRoot : repoRoots) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;
                pomVersionUpdater.setVersions(repoRoot, release);
            }
            dependencyVersionUpdater.update(repoRoots, releaseVersionByKey);

            for (Path repoRoot : repoRoots) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;
                String tagName = "v" + release;
                gitService.deleteTagIfExists(repoRoot, tagName);
                log.info("Tagging {} as {}", repoRoot.getFileName(), tagName);
                gitService.commitAll(repoRoot, "chore: release " + release);
                gitService.createTag(repoRoot, tagName, "Release " + release);
                if (dryMode) {
                    log.info("Dry mode — skipping push/pushTag for {}", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                    gitService.pushTag(repoRoot, tagName);
                }
            }
            resumeState.setReleasePhase1Complete(true);
        } else {
            log.info("Skipping release Phase 1 — already completed in previous run");
            // Re-derive release versions from pom files (they were set and pushed in Phase 1)
            for (Path repoRoot : repoRoots) {
                String v = pomVersionUpdater.getRootVersion(repoRoot);
                if (v != null) {
                    releaseVersionByRepo.put(repoRoot, v);
                    log.info("Resumed release version for {}: {}", repoRoot.getFileName(), v);
                }
            }
            releaseVersionByKey.clear();
            for (Map.Entry<Artifact, Module> entry : moduleMap.entrySet()) {
                String rv = releaseVersionByRepo.get(entry.getValue().getRepoRoot());
                if (rv != null) releaseVersionByKey.put(entry.getKey().key(), rv);
            }
        }

        // --- Phase 2: build each layer, then bump to next dev SNAPSHOT ---
        Map<Path, String> tagByRepo = new LinkedHashMap<>();
        releaseVersionByRepo.forEach((repo, ver) -> tagByRepo.put(repo, "v" + ver));

        Map<String, String> currentVersionByKey = new HashMap<>(releaseVersionByKey);

        for (List<Artifact> layer : buildLayers) {
            LinkedHashSet<Path> layerRepos = new LinkedHashSet<>();
            for (Artifact a : layer) {
                Module m = moduleMap.get(a);
                if (m != null) layerRepos.add(m.getRepoRoot());
            }

            boolean allSkipped = layerRepos.stream().allMatch(resumeState::isCompleted);
            if (allSkipped) {
                log.info("Skipping layer — all {} repo(s) already completed", layerRepos.size());
                continue;
            }

            log.info("Building release layer with {} repo(s): {}", layerRepos.size(),
                    layerRepos.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));

            // Build; catch partial failures so we can bump the repos that did succeed
            Set<Path> newlyBuilt;
            BuildFailedException buildError = null;
            try {
                buildService.buildAll(List.of(layer), moduleMap, repoConfigs,
                        resumeState.getCompletedRepoNames(), tagByRepo);
                newlyBuilt = layerRepos.stream()
                        .filter(r -> !resumeState.isCompleted(r))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            } catch (BuildFailedException e) {
                newlyBuilt = e.getSucceeded();
                buildError = e;
            }

            // Bump version for every repo that was newly built (even if others in the layer failed)
            for (Path repoRoot : newlyBuilt) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;
                String nextSnapshot = incrementPatch(release) + "-SNAPSHOT";
                log.info("Bumping {} to {}", repoRoot.getFileName(), nextSnapshot);

                pomVersionUpdater.setVersions(repoRoot, nextSnapshot);

                for (Artifact a : layer) {
                    Module m = moduleMap.get(a);
                    if (m != null && repoRoot.equals(m.getRepoRoot())) {
                        currentVersionByKey.put(a.key(), nextSnapshot);
                    }
                }
                dependencyVersionUpdater.update(List.of(repoRoot), currentVersionByKey);

                gitService.commitAll(repoRoot, "chore: prepare next development version " + nextSnapshot);
                if (dryMode) {
                    log.info("Dry mode — skipping push for {}", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                }
                // Only mark completed after the full cycle (build + bump + push) succeeds
                resumeState.markCompleted(repoRoot);
            }

            if (buildError != null) throw buildError;
        }
    }

    private String baseVersion(String version) {
        int idx = version.indexOf('-');
        return idx >= 0 ? version.substring(0, idx) : version;
    }

    private String incrementPatch(String version) {
        String[] parts = version.split("\\.");
        if (parts.length == 0) return version;
        try {
            int last = Integer.parseInt(parts[parts.length - 1]);
            parts[parts.length - 1] = String.valueOf(last + 1);
        } catch (NumberFormatException e) {
            log.warn("Could not parse last segment of version '{}', appending .1", version);
            return version + ".1";
        }
        return String.join(".", parts);
    }
}
