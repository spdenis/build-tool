package com.example.multibuild.service;

import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DependencyVersionService {

    private static final Logger log = LoggerFactory.getLogger(DependencyVersionService.class);

    @Value("${integration.branch:}")
    private String integrationBranch;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    @Value("${build.service:LOCAL}")
    private BuildServiceType defaultBuildService;

    private final DependencyVersionUpdater updater;
    private final GitService gitService;
    private final CommitMessageFormatter commitFormatter;

    public DependencyVersionService(DependencyVersionUpdater updater, GitService gitService,
                                    CommitMessageFormatter commitFormatter) {
        this.updater = updater;
        this.gitService = gitService;
        this.commitFormatter = commitFormatter;
    }

    // Updates dependency versions across all repos for every in-scope artifact,
    // then commits (and pushes unless dry mode) each repo that changed.
    // For Lightspeed repos the POM carries a bare version (e.g. 1.0.1-SNAPSHOT), but
    // the artifact that actually lands in the Maven repository is 1.0.1-<branch>-SNAPSHOT
    // because the CI pipeline appends the branch name. Dependencies in other repos must
    // therefore reference the expanded version, not the bare one.
    public void apply(Map<Artifact, Module> moduleMap, List<Path> repoRoots,
                      Map<Path, RepoConfig> repoConfigByPath, String integrationBranch, boolean dryMode) {
        Map<String, String> versionByKey = moduleMap.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().key(),
                        e -> {
                            Artifact a = e.getKey();
                            Module m = e.getValue();
                            RepoConfig config = repoConfigByPath.get(m.getRepoRoot());
                            if (config != null && config.isPreserveVersion()) {
                                return a.getVersion();
                            }
                            if (isLightspeed(config) && !integrationBranch.isBlank()) {
                                return expandVersion(a.getVersion(), integrationBranch);
                            }
                            return a.getVersion();
                        }));

        log.info("Updating in-scope dependency versions across {} repo(s)", repoRoots.size());
        Set<Path> modified = updater.update(repoRoots, versionByKey);

        if (modified.isEmpty()) {
            log.info("No dependency version changes needed");
            return;
        }

        String suffix = integrationBranch.isBlank() ? "" : " for " + integrationBranch;
        String commitMessage = commitFormatter.format("chore: update dependency versions" + suffix);

        for (Path repoRoot : modified) {
            log.info("Committing dependency version updates in {}", repoRoot.getFileName());
            gitService.commitAll(repoRoot, commitMessage);
            RepoConfig config = repoConfigByPath.get(repoRoot);
            if (dryMode || (config != null && config.isDryRun())) {
                log.info("Dry mode — skipping push for {}", repoRoot.getFileName());
            } else {
                gitService.push(repoRoot);
            }
        }
    }

    private boolean isLightspeed(RepoConfig config) {
        BuildServiceType type = (config != null && config.getBuildService() != null)
                ? config.getBuildService() : defaultBuildService;
        return type == BuildServiceType.LIGHTSPEED;
    }

    // "1.0.1-SNAPSHOT" + "integration" → "1.0.1-integration-SNAPSHOT"
    private static String expandVersion(String bareVersion, String branch) {
        if (bareVersion.endsWith("-SNAPSHOT")) {
            return bareVersion.substring(0, bareVersion.length() - "-SNAPSHOT".length())
                    + "-" + branch + "-SNAPSHOT";
        }
        return bareVersion;
    }
}
