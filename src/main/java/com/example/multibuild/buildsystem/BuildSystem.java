package com.example.multibuild.buildsystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Build system used by a repository, detected from the build files at its root.
// Gradle wins when both are present: a repo migrated by migrate-gradle no longer has a pom.xml,
// and a hand-migrated repo that still carries a stale pom.xml is built with Gradle.
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

    // True when the repo has a build the tool can parse and version (pom.xml or Gradle build).
    public static boolean hasBuildFile(Path repoRoot) {
        return detect(repoRoot) != NONE;
    }
}
