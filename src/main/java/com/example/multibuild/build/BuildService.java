package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface BuildService {
    // layers: each inner list is a group of artifacts with no inter-dependencies
    // that can be built in parallel. Layers must be processed in order.
    void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                  Map<Path, RepoConfig> repoConfigs);
}
