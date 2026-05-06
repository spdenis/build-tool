package com.example.multibuild.service;

import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    @Value("${integration.branch:}")
    private String integrationBranch;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    @Value("${build.service:LOCAL}")
    private BuildServiceType defaultBuildService;

    // "fail" (default) — abort if any repo version violates the guideline.
    // "fix"            — auto-correct the version, commit, and push.
    @Value("${snapshot.version.mismatch:fail}")
    private String versionMismatchMode;

    private final GitService gitService;
    private final PomVersionUpdater versionUpdater;
    private final CommitMessageFormatter commitFormatter;

    public BranchService(GitService gitService, PomVersionUpdater versionUpdater,
                         CommitMessageFormatter commitFormatter) {
        this.gitService = gitService;
        this.versionUpdater = versionUpdater;
        this.commitFormatter = commitFormatter;
    }

    public String getIntegrationBranch() {
        return integrationBranch;
    }

    // Enforces correct pom versions for all repos on the integration branch.
    // Lightspeed repos must have bare -SNAPSHOT (e.g. 1.0.1-SNAPSHOT) because the
    // CI pipeline appends the branch name itself; all others must end with -<branch>-SNAPSHOT.
    // Behaviour on mismatch controlled by snapshot.version.mismatch ("fail" or "fix").
    public void validateVersions(List<Path> repoDirs, Map<Path, RepoConfig> repoConfigByPath) {
        boolean fixMode = "fix".equalsIgnoreCase(versionMismatchMode);
        String requiredSuffix = "-" + integrationBranch + "-SNAPSHOT";
        List<String> violations = new ArrayList<>();

        for (Path repoDir : repoDirs) {
            RepoConfig config = repoConfigByPath.get(repoDir);
            if (config != null && config.isPreserveVersion()) {
                log.info("Skipping version validation for {} (preserveVersion=true)", repoDir.getFileName());
                continue;
            }

            String version = versionUpdater.getRootVersion(repoDir);
            if (version == null) {
                violations.add("  " + repoDir.getFileName() + ": could not read version");
                continue;
            }

            boolean lightspeed = isLightspeed(config);
            boolean valid = lightspeed
                    ? version.endsWith("-SNAPSHOT") && !version.endsWith(requiredSuffix)
                    : version.endsWith(requiredSuffix);
            if (valid) continue;

            if (fixMode) {
                if (lightspeed) {
                    log.info("Fixing version in {} ('{}' must be bare -SNAPSHOT for Lightspeed)",
                            repoDir.getFileName(), version);
                    String newVersion = versionUpdater.updateVersionsBare(repoDir);
                    gitService.commitAll(repoDir, commitFormatter.format("chore: set version to " + newVersion));
                } else {
                    log.info("Fixing version in {} ('{}' does not end with '{}')",
                            repoDir.getFileName(), version, requiredSuffix);
                    String newVersion = versionUpdater.updateVersions(repoDir, integrationBranch);
                    gitService.commitAll(repoDir, commitFormatter.format("chore: set version to " + newVersion));
                }
                if (isDryRun(dryMode, repoConfigByPath.get(repoDir))) {
                    log.info("Dry mode — skipping push for {}", repoDir.getFileName());
                } else {
                    gitService.push(repoDir);
                }
            } else {
                if (lightspeed) {
                    violations.add("  " + repoDir.getFileName() + ": version is '" + version +
                            "' (Lightspeed repos must use bare -SNAPSHOT, e.g. '1.0.1-SNAPSHOT'" +
                            " — the pipeline appends the branch name)");
                } else {
                    violations.add("  " + repoDir.getFileName() + ": version is '" + version +
                            "' (expected suffix '" + requiredSuffix + "')");
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new RuntimeException("Version guideline violation on branch '" + integrationBranch + "':\n" +
                    String.join("\n", violations));
        }
    }

    // Checks out the integration branch in the cloned repo.
    // If newly created: Lightspeed repos get a bare -SNAPSHOT version; others get -<branch>-SNAPSHOT.
    // sourceBranch is the start point when the integration branch doesn't exist yet.
    public void apply(Path repoDir, String sourceBranch, RepoConfig repoConfig) {
        boolean created = gitService.checkoutOrCreateBranch(repoDir, integrationBranch, sourceBranch);
        if (created) {
            if (repoConfig != null && repoConfig.isPreserveVersion()) {
                log.info("Created branch {} in {}, preserveVersion=true — skipping version update",
                        integrationBranch, repoDir.getFileName());
            } else {
                log.info("Created branch {} in {}, updating pom versions", integrationBranch, repoDir.getFileName());
                String newVersion = isLightspeed(repoConfig)
                        ? versionUpdater.updateVersionsBare(repoDir)
                        : versionUpdater.updateVersions(repoDir, integrationBranch);
                gitService.commitAll(repoDir, commitFormatter.format("chore: set version to " + newVersion));
                if (isDryRun(dryMode, repoConfig)) {
                    log.info("Dry mode — skipping push for {}", repoDir.getFileName());
                } else {
                    gitService.push(repoDir);
                }
            }
        } else {
            log.info("Branch {} already exists in {}, keeping current versions", integrationBranch, repoDir.getFileName());
        }
    }

    private boolean isLightspeed(RepoConfig config) {
        BuildServiceType type = (config != null && config.getBuildService() != null)
                ? config.getBuildService() : defaultBuildService;
        return type == BuildServiceType.LIGHTSPEED;
    }

    private static boolean isDryRun(boolean globalDryMode, RepoConfig config) {
        return globalDryMode || (config != null && config.isDryRun());
    }
}
