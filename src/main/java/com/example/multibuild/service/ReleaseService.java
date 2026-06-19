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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
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
                RepoConfig config = repoConfigs.get(repoRoot);
                if (config != null && config.hasVersionOverride()) {
                    String release = config.getVersion();
                    releaseVersionByRepo.put(repoRoot, release);
                    log.info("  [{}] No pom.xml — using version override {} as release version",
                            repoRoot.getFileName(), release);
                } else {
                    log.warn("  [{}] No pom.xml and no version override — skipping release", repoRoot.getFileName());
                }
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
                if (Files.exists(repoRoot.resolve("pom.xml"))) {
                    boolean committed = gitService.commitAllIfDirty(repoRoot, "chore: release " + release);
                    if (committed) {
                        log.info("  [{}] Committed release version, tagging {}", repoRoot.getFileName(), tagName);
                    } else {
                        log.info("  [{}] Nothing to commit (pom already at release version), tagging {} on HEAD",
                                repoRoot.getFileName(), tagName);
                    }
                } else {
                    log.info("  [{}] No pom.xml — tagging {} directly on HEAD", repoRoot.getFileName(), tagName);
                }
                // Tag is created locally here but pushed in Phase 2, just before each layer's
                // build is triggered. Pushing all tags upfront would cause Jenkins to start
                // building downstream repos before their upstream dependencies are available.
                gitService.createTag(repoRoot, tagName, "Release " + release);
                if (isDryRun(repoConfigs.get(repoRoot))) {
                    log.info("  [{}] Dry mode — skipping push", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                    log.info("  [{}] Pushed release branch at {}", repoRoot.getFileName(), tagName);
                }
            }
            resumeState.setReleasePhase1Complete(true);
            log.info("── Release Phase 1 complete in {} ──────────────────────", elapsed(phase1Start));
        } else {
            log.info("  Skipping — already completed in a previous run, re-deriving versions from pom files");
            for (Path repoRoot : repoRoots) {
                String v = pomVersionUpdater.getRootVersion(repoRoot);
                if (v != null) {
                    // baseVersion strips any -SNAPSHOT/-branch-SNAPSHOT suffix that may remain if the repo
                    // was not yet processed by Phase 1 when the previous run crashed.
                    String release = baseVersion(v);
                    releaseVersionByRepo.put(repoRoot, release);
                    log.info("  [{}] Resumed at version {}", repoRoot.getFileName(), release);
                } else {
                    RepoConfig config = repoConfigs.get(repoRoot);
                    if (config != null && config.hasVersionOverride()) {
                        releaseVersionByRepo.put(repoRoot, config.getVersion());
                        log.info("  [{}] No pom.xml — using version override {} on resume",
                                repoRoot.getFileName(), config.getVersion());
                    }
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
        int layerNum = 0;

        for (List<Path> layer : buildLayers) {
            layerNum++;

            // Exclude repos that have no release tag determined by Phase 1. This happens for repos
            // without pom.xml that also have no 'version' set in repos.json. Building them with
            // the integration branch instead of a release tag would produce a SNAPSHOT artifact.
            List<Path> layerRepos = new ArrayList<>();
            for (Path r : layer) {
                if (resumeState.isCompleted(r) || tagByRepo.containsKey(r)) {
                    layerRepos.add(r);
                } else {
                    log.warn("  [{}] Skipping in release build — no pom.xml and no 'version' in repos.json",
                            r.getFileName());
                }
            }

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

            // Push release tags for this layer's repos just before triggering their builds.
            // Doing this per-layer (not all upfront in Phase 1) ensures Jenkins never starts
            // building a repo before its upstream dependencies' artifacts are published.
            // Force-push handles resume runs where a tag was already pushed before a crash.
            for (Path repoRoot : layerRepos) {
                if (resumeState.isCompleted(repoRoot) || isDryRun(repoConfigs.get(repoRoot))) continue;
                String tagName = tagByRepo.get(repoRoot);
                if (tagName == null) continue;
                // Recreate local tag in case the repo was freshly cloned for this resume run
                // (Phase 1 only created tags locally, never pushed them to remote).
                gitService.deleteTagIfExists(repoRoot, tagName);
                gitService.createTag(repoRoot, tagName, "Release " + tagName);
                gitService.pushTagForce(repoRoot, tagName);
                log.info("  [{}] Pushed release tag {} — Jenkins build will start shortly",
                        repoRoot.getFileName(), tagName);
            }

            // For release builds, failure is tracked immediately via callback; success is tracked
            // below after the version bump (markCompleted triggers auto-save via onUpdate).
            BiConsumer<Path, String> buildCallback = (repoRoot, error) -> {
                if (error != null) resumeState.markFailed(repoRoot, error);
            };

            Set<Path> newlyBuilt;
            BuildFailedException buildError = null;
            long layerStart = System.currentTimeMillis();
            try {
                buildService.buildAll(List.of(layerRepos), moduleMap, repoConfigs,
                        resumeState.getCompletedRepoNames(), tagByRepo, buildCallback);
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
                if (!Files.exists(repoRoot.resolve("pom.xml"))) {
                    log.info("  [{}] No pom.xml — skipping version bump", repoRoot.getFileName());
                    resumeState.markCompleted(repoRoot, release);
                    continue;
                }
                String nextSnapshot = incrementPatch(release) + "-SNAPSHOT";
                log.info("  [{}] Bumping {} → {}", repoRoot.getFileName(), release, nextSnapshot);

                pomVersionUpdater.setVersions(repoRoot, nextSnapshot);
                gitService.commitAll(repoRoot, "chore: prepare next development version " + nextSnapshot);
                if (isDryRun(repoConfigs.get(repoRoot))) {
                    log.info("  [{}] Dry mode — skipping push", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                    log.info("  [{}] Pushed next dev version {}", repoRoot.getFileName(), nextSnapshot);
                }
                resumeState.markCompleted(repoRoot, release);
            }

            if (buildError != null) throw buildError;
        }

        log.info("── Release Phase 2 complete in {} ──────────────────────", elapsed(phase2Start));
    }

    private String baseVersion(String version) {
        int idx = version.indexOf('-');
        return idx >= 0 ? version.substring(0, idx) : version;
    }

    private boolean isDryRun(RepoConfig config) {
        return dryMode || (config != null && config.isDryRun());
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
