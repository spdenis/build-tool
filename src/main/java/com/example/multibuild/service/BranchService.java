package com.example.multibuild.service;

import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.PomVersionUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    @Value("${integration.branch:}")
    private String integrationBranch;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    // "fail" (default) — abort if any repo version violates the guideline.
    // "fix"            — auto-correct the version, commit, and push.
    @Value("${snapshot.version.mismatch:fail}")
    private String versionMismatchMode;

    private final GitService gitService;
    private final PomVersionUpdater versionUpdater;

    public BranchService(GitService gitService, PomVersionUpdater versionUpdater) {
        this.gitService = gitService;
        this.versionUpdater = versionUpdater;
    }

    public boolean isEnabled() {
        return !integrationBranch.isBlank();
    }

    // Enforces that every repo's root version ends with -<branch>-SNAPSHOT.
    // Behaviour on mismatch is controlled by snapshot.version.mismatch:
    //   "fail" — collect all violations and throw (default)
    //   "fix"  — correct the version, commit, and push
    public void validateVersions(List<Path> repoDirs) {
        if (!isEnabled()) return;

        boolean fixMode = "fix".equalsIgnoreCase(versionMismatchMode);
        String requiredSuffix = "-" + integrationBranch + "-SNAPSHOT";
        List<String> violations = new ArrayList<>();

        for (Path repoDir : repoDirs) {
            String version = versionUpdater.getRootVersion(repoDir);
            if (version == null) {
                violations.add("  " + repoDir.getFileName() + ": could not read version");
                continue;
            }
            if (version.endsWith(requiredSuffix)) continue;

            if (fixMode) {
                log.info("Fixing version in {} ('{}' does not end with '{}')",
                        repoDir.getFileName(), version, requiredSuffix);
                String newVersion = versionUpdater.updateVersions(repoDir, integrationBranch);
                gitService.commitAll(repoDir, "chore: set version to " + newVersion);
                if (dryMode) {
                    log.info("Dry mode — skipping push for {}", repoDir.getFileName());
                } else {
                    gitService.push(repoDir);
                }
            } else {
                violations.add("  " + repoDir.getFileName() + ": version is '" + version +
                        "' (expected suffix '" + requiredSuffix + "')");
            }
        }

        if (!violations.isEmpty()) {
            throw new RuntimeException(
                    "Version guideline violation — all repos on branch '" + integrationBranch +
                    "' must have versions ending with '" + requiredSuffix + "':\n" +
                    String.join("\n", violations));
        }
    }

    // Checks out the integration branch in the cloned repo.
    // If the branch is newly created, bumps all pom.xml versions to <branch>-SNAPSHOT and commits.
    // sourceBranch is the branch used as the start point when the integration branch doesn't exist yet.
    public void apply(Path repoDir, String sourceBranch) {
        if (!isEnabled()) return;

        boolean created = gitService.checkoutOrCreateBranch(repoDir, integrationBranch, sourceBranch);
        if (created) {
            log.info("Created branch {} in {}, updating pom versions", integrationBranch, repoDir.getFileName());
            String newVersion = versionUpdater.updateVersions(repoDir, integrationBranch);
            gitService.commitAll(repoDir, "chore: set version to " + newVersion);
            if (dryMode) {
                log.info("Dry mode — skipping push for {}", repoDir.getFileName());
            } else {
                gitService.push(repoDir);
            }
        } else {
            log.info("Branch {} already exists in {}, keeping current versions", integrationBranch, repoDir.getFileName());
        }
    }
}
