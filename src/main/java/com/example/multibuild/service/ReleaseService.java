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

    public void execute(List<List<Path>> buildLayers, Map<Artifact, Module> moduleMap,
                        List<Path> repoRoots, Map<Path, RepoConfig> repoConfigs,
                        ResumeState resumeState) {

        // ── Release Phase 1: pin versions, tag, push ────────────────
        log.info("── Release Phase 1: pin versions, tag & push ({} repo(s)) ──", repoRoots.size());
        long phase1Start = System.currentTimeMillis();

        Map<String, String> releaseVersionByKey = new LinkedHashMap<>();
        Map<Path, String> releaseVersionByRepo = new LinkedHashMap<>();

        for (Path repoRoot : repoRoots) {
            String current = pomVersionUpdater.getRootVersion(repoRoot);
            if (current == null) {
                log.warn("  [{}] Could not determine version, skipping", repoRoot.getFileName());
                continue;
            }
            String release = baseVersion(current);
            releaseVersionByRepo.put(repoRoot, release);
            log.info("  [{}] {} → {}", repoRoot.getFileName(), current, release);
        }

        for (Map.Entry<Artifact, Module> entry : moduleMap.entrySet()) {
            String rv = releaseVersionByRepo.get(entry.getValue().getRepoRoot());
            if (rv != null) releaseVersionByKey.put(entry.getKey().key(), rv);
        }

        if (!resumeState.isReleasePhase1Complete()) {
            log.info("  Setting release versions in pom.xml files...");
            for (Path repoRoot : repoRoots) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;
                pomVersionUpdater.setVersions(repoRoot, release);
            }

            log.info("  Updating cross-repo dependency versions...");
            dependencyVersionUpdater.update(repoRoots, releaseVersionByKey);

            for (Path repoRoot : repoRoots) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;
                String tagName = release;
                gitService.deleteTagIfExists(repoRoot, tagName);
                log.info("  [{}] Committing, tagging {}", repoRoot.getFileName(), tagName);
                gitService.commitAll(repoRoot, "chore: release " + release);
                gitService.createTag(repoRoot, tagName, "Release " + release);
                if (dryMode) {
                    log.info("  [{}] Dry mode — skipping push/pushTag", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                    gitService.pushTag(repoRoot, tagName);
                    log.info("  [{}] Pushed branch and tag {}", repoRoot.getFileName(), tagName);
                }
            }
            resumeState.setReleasePhase1Complete(true);
            log.info("── Release Phase 1 complete in {} ──────────────────────", elapsed(phase1Start));
        } else {
            log.info("  Skipping — already completed in a previous run, re-deriving versions from pom files");
            for (Path repoRoot : repoRoots) {
                String v = pomVersionUpdater.getRootVersion(repoRoot);
                if (v != null) {
                    releaseVersionByRepo.put(repoRoot, v);
                    log.info("  [{}] Resumed at version {}", repoRoot.getFileName(), v);
                }
            }
            releaseVersionByKey.clear();
            for (Map.Entry<Artifact, Module> entry : moduleMap.entrySet()) {
                String rv = releaseVersionByRepo.get(entry.getValue().getRepoRoot());
                if (rv != null) releaseVersionByKey.put(entry.getKey().key(), rv);
            }
        }

        // ── Release Phase 2: build layers, bump to next SNAPSHOT ────
        log.info("── Release Phase 2: build & bump ({} layer(s)) ──────────", buildLayers.size());
        long phase2Start = System.currentTimeMillis();

        Map<Path, String> tagByRepo = new LinkedHashMap<>(releaseVersionByRepo);
        Map<String, String> currentVersionByKey = new HashMap<>(releaseVersionByKey);
        int layerNum = 0;

        for (List<Path> layer : buildLayers) {
            layerNum++;
            List<Path> layerRepos = layer;

            boolean allSkipped = layerRepos.stream().allMatch(resumeState::isCompleted);
            if (allSkipped) {
                log.info("  Layer {}/{}: all {} repo(s) already completed — skipping",
                        layerNum, buildLayers.size(), layerRepos.size());
                continue;
            }

            String repoNames = layerRepos.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            log.info("  Layer {}/{}: building {} repo(s): {}",
                    layerNum, buildLayers.size(), layerRepos.size(), repoNames);

            Set<Path> newlyBuilt;
            BuildFailedException buildError = null;
            long layerStart = System.currentTimeMillis();
            try {
                buildService.buildAll(List.of(layer), moduleMap, repoConfigs,
                        resumeState.getCompletedRepoNames(), tagByRepo);
                newlyBuilt = layerRepos.stream()
                        .filter(r -> !resumeState.isCompleted(r))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                log.info("  Layer {}/{}: all builds succeeded in {}",
                        layerNum, buildLayers.size(), elapsed(layerStart));
            } catch (BuildFailedException e) {
                newlyBuilt = e.getSucceeded();
                buildError = e;
                log.warn("  Layer {}/{}: {} succeeded, continuing with version bump before re-throwing",
                        layerNum, buildLayers.size(), newlyBuilt.size());
            }

            for (Path repoRoot : newlyBuilt) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;
                String nextSnapshot = incrementPatch(release) + "-SNAPSHOT";
                log.info("  [{}] Bumping {} → {}", repoRoot.getFileName(), release, nextSnapshot);

                pomVersionUpdater.setVersions(repoRoot, nextSnapshot);

                // Update version map for all artifacts that belong to this repo
                moduleMap.entrySet().stream()
                        .filter(e -> repoRoot.equals(e.getValue().getRepoRoot()))
                        .forEach(e -> currentVersionByKey.put(e.getKey().key(), nextSnapshot));
                dependencyVersionUpdater.update(List.of(repoRoot), currentVersionByKey);

                gitService.commitAll(repoRoot, "chore: prepare next development version " + nextSnapshot);
                if (dryMode) {
                    log.info("  [{}] Dry mode — skipping push", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                    log.info("  [{}] Pushed next dev version {}", repoRoot.getFileName(), nextSnapshot);
                }
                resumeState.markCompleted(repoRoot);
            }

            if (buildError != null) throw buildError;
        }

        log.info("── Release Phase 2 complete in {} ──────────────────────", elapsed(phase2Start));
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

    private static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
