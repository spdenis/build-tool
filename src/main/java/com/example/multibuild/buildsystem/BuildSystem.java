package com.example.multibuild.buildsystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Build system used by a repository, detected from the build files at its root.
// Gradle wins when both are present: it is the build that is parsed and run. A repo migrated with
// migrate.keep.maven=true keeps its poms until the migration is completed; meanwhile version and
// dependency updates are applied to both builds (see hasMavenBuild) so the Maven build stays usable.
public enum BuildSystem {
    MAVEN, GRADLE, NONE;

    public static final List<String> GRADLE_ROOT_FILES =
            List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");

    public static BuildSystem detect(Path repoRoot) {
        for (String f : GRADLE_ROOT_FILES) {
            if (Files.isRegularFile(repoRoot.resolve(f))) return GRADLE;
        }
        if (Files.isRegularFile(repoRoot.resolve("pom.xml"))) return MAVEN;
        return NONE;
    }

    // True when a pom.xml sits next to a Gradle build (migration in progress, or a stale pom).
    public static boolean hasMavenBuild(Path repoRoot) {
        return Files.isRegularFile(repoRoot.resolve("pom.xml"));
    }

    // True when the repo has a build the tool can parse and version (pom.xml or Gradle build).
    public static boolean hasBuildFile(Path repoRoot) {
        return detect(repoRoot) != NONE;
    }
}
