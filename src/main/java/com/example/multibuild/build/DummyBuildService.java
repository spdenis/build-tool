package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Qualifier("dummy")
public class DummyBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(DummyBuildService.class);

    @Override
    public void buildAll(List<List<Path>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo) {
        for (List<Path> layer : layers) {
            for (Path repoRoot : layer) {
                if (completedRepoNames.contains(repoRoot.getFileName().toString())) continue;
                log.info("  [DUMMY] Skipping build for {} (dummy build service)", repoRoot.getFileName());
            }
        }
    }
}
