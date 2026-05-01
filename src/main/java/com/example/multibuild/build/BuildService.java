package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface BuildService {
    // layers: each inner list is a group of artifacts with no inter-dependencies
    // that can be built in parallel. Layers must be processed in order.
    // completedRepoNames: repo directory names to skip (already built in a previous run).
    // buildBranchByRepo: optional per-repo branch/tag to build from (used by TeamCity).
    //   For snapshot builds pass Collections.emptyMap(); for release builds pass the tag map.
    // Throws BuildFailedException (carries succeeded repos) on partial or total failure.
    void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                  Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                  Map<Path, String> buildBranchByRepo);
}
