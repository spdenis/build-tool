package com.example.multibuild.service;

import com.example.multibuild.build.BuildService;
import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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

    // Phase 1: set release versions on all repos, commit + tag + push.
    // Phase 2: build each repo in order, then bump to next dev SNAPSHOT.
    public void execute(List<List<Artifact>> buildLayers, Map<Artifact, Module> moduleMap,
                        List<Path> repoRoots, Map<Path, RepoConfig> repoConfigs) {
        List<Artifact> buildOrder = buildLayers.stream().flatMap(List::stream).toList();
        // --- Phase 1: compute release versions for every in-scope artifact ---
        // Map artifactKey -> releaseVersion
        Map<String, String> releaseVersionByKey = new LinkedHashMap<>();
        // Map repoRoot -> releaseVersion (one per repo, determined by root pom)
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

        // Populate releaseVersionByKey from moduleMap using the computed release versions
        for (Map.Entry<Artifact, Module> entry : moduleMap.entrySet()) {
            Path repoRoot = entry.getValue().getRepoRoot();
            String releaseVersion = releaseVersionByRepo.get(repoRoot);
            if (releaseVersion != null) {
                releaseVersionByKey.put(entry.getKey().key(), releaseVersion);
            }
        }

        // Set release versions in all repos
        for (Path repoRoot : repoRoots) {
            String release = releaseVersionByRepo.get(repoRoot);
            if (release == null) continue;
            pomVersionUpdater.setVersions(repoRoot, release);
        }

        // Update cross-repo dependency versions to release
        dependencyVersionUpdater.update(repoRoots, releaseVersionByKey);

        // Commit, tag, and push each repo
        for (Path repoRoot : repoRoots) {
            String release = releaseVersionByRepo.get(repoRoot);
            if (release == null) continue;
            String tagName = "v" + release;
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

        // --- Phase 2: per layer — build all repos in parallel, then bump versions ---
        // currentVersionByKey tracks each artifact's current version as repos are bumped.
        Map<String, String> currentVersionByKey = new HashMap<>(releaseVersionByKey);

        for (List<Artifact> layer : buildLayers) {
            // Unique repo roots for this layer (repos in a layer have no inter-dependencies)
            LinkedHashSet<Path> layerRepos = new LinkedHashSet<>();
            for (Artifact a : layer) {
                Module m = moduleMap.get(a);
                if (m != null) layerRepos.add(m.getRepoRoot());
            }

            // Build all repos in this layer in parallel
            log.info("Building release layer with {} repo(s): {}", layerRepos.size(),
                    layerRepos.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
            buildService.buildAll(List.of(layer), moduleMap, repoConfigs);

            // After all builds in the layer succeed, bump each repo to next SNAPSHOT
            for (Path repoRoot : layerRepos) {
                String release = releaseVersionByRepo.get(repoRoot);
                if (release == null) continue;

                String nextSnapshot = incrementPatch(release) + "-SNAPSHOT";
                log.info("Bumping {} to {}", repoRoot.getFileName(), nextSnapshot);

                pomVersionUpdater.setVersions(repoRoot, nextSnapshot);

                for (Artifact artifact : layer) {
                    Module m = moduleMap.get(artifact);
                    if (m != null && repoRoot.equals(m.getRepoRoot())) {
                        currentVersionByKey.put(artifact.key(), nextSnapshot);
                    }
                }

                dependencyVersionUpdater.update(List.of(repoRoot), currentVersionByKey);

                gitService.commitAll(repoRoot, "chore: prepare next development version " + nextSnapshot);
                if (dryMode) {
                    log.info("Dry mode — skipping push for {}", repoRoot.getFileName());
                } else {
                    gitService.push(repoRoot);
                }
            }
        }
    }

    // Strips qualifier: "1.4.2-SNAPSHOT" -> "1.4.2", "2.0.0" -> "2.0.0"
    private String baseVersion(String version) {
        int idx = version.indexOf('-');
        return idx >= 0 ? version.substring(0, idx) : version;
    }

    // Increments the patch segment: "1.4.2" -> "1.4.3", "1.0" -> "1.1"
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
