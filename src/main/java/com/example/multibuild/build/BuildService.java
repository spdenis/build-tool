package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public interface BuildService {
    // layers: each inner list is a group of repo roots with no inter-dependencies
    // that can be built in parallel. Layers must be processed in order.
    // completedRepoNames: repo directory names to skip (already built in a previous run).
    // buildBranchByRepo: optional per-repo branch/tag (used by TeamCity release builds).
    // onRepoComplete: called for each repo as soon as its build finishes.
    //   Second arg is null on success, or the error message on failure.
    //   Implementations must call this even when completedRepoNames causes a skip (pass null).
    // Throws BuildFailedException (carries succeeded repos) on partial or total failure.
    void buildAll(List<List<Path>> layers, Map<Artifact, Module> moduleMap,
                  Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                  Map<Path, String> buildBranchByRepo,
                  BiConsumer<Path, String> onRepoComplete);
}
