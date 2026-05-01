package com.example.multibuild.service;

import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
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

    private final DependencyVersionUpdater updater;
    private final GitService gitService;

    public DependencyVersionService(DependencyVersionUpdater updater, GitService gitService) {
        this.updater = updater;
        this.gitService = gitService;
    }

    // Updates dependency versions across all repos for every in-scope artifact,
    // then commits (and pushes unless dry mode) each repo that changed.
    public void apply(Map<Artifact, Module> moduleMap, List<Path> repoRoots) {
        Map<String, String> versionByKey = moduleMap.keySet().stream()
                .collect(Collectors.toMap(Artifact::key, Artifact::getVersion));

        log.info("Updating in-scope dependency versions across {} repo(s)", repoRoots.size());
        Set<Path> modified = updater.update(repoRoots, versionByKey);

        if (modified.isEmpty()) {
            log.info("No dependency version changes needed");
            return;
        }

        String suffix = integrationBranch.isBlank() ? "" : " for " + integrationBranch;
        String commitMessage = "chore: update dependency versions" + suffix;

        for (Path repoRoot : modified) {
            log.info("Committing dependency version updates in {}", repoRoot.getFileName());
            gitService.commitAll(repoRoot, commitMessage);
            if (dryMode) {
                log.info("Dry mode — skipping push for {}", repoRoot.getFileName());
            } else {
                gitService.push(repoRoot);
            }
        }
    }
}
