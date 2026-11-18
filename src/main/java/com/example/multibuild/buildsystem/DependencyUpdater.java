package com.example.multibuild.buildsystem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Rewrites versions of in-scope dependencies (keyed by groupId:artifactId) in the given repos.
public interface DependencyUpdater {
    // Returns the repo roots that had at least one change.
    Set<Path> update(List<Path> repoRoots, Map<String, String> versionByKey);
}
