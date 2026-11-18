package com.example.multibuild.buildsystem;

import com.example.multibuild.model.Artifact;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

// Reads and rewrites the version of the project in a repository.
public interface VersionUpdater {

    // Current root version of the project in repoDir, or null if undetectable.
    String getRootVersion(Path repoDir);

    // Root artifact coordinates (version = root version), or null if undetectable.
    Artifact getRootArtifact(Path repoDir);

    // Sets every module version to newVersion.
    void setVersions(Path repoDir, String newVersion);

    // Sets versions to <baseVersion>-<branchName>-SNAPSHOT, keeping the numeric part. Returns the new version.
    default String updateVersions(Path repoDir, String branchName) {
        String current = getRootVersion(repoDir);
        if (current == null) {
            LoggerFactory.getLogger(VersionUpdater.class)
                    .warn("Could not determine root version in {}", repoDir);
            return branchName + "-SNAPSHOT";
        }
        String newVersion = baseVersion(current) + "-" + branchName + "-SNAPSHOT";
        LoggerFactory.getLogger(VersionUpdater.class)
                .info("Updating versions in {} from {} to {}", repoDir.getFileName(), current, newVersion);
        setVersions(repoDir, newVersion);
        return newVersion;
    }

    // Sets versions to <baseVersion>-SNAPSHOT (no branch qualifier, used for Lightspeed). Returns the new version.
    default String updateVersionsBare(Path repoDir) {
        String current = getRootVersion(repoDir);
        if (current == null) {
            throw new RuntimeException("Could not determine root version in " + repoDir);
        }
        String newVersion = baseVersion(current) + "-SNAPSHOT";
        LoggerFactory.getLogger(VersionUpdater.class)
                .info("Updating versions in {} from {} to {}", repoDir.getFileName(), current, newVersion);
        setVersions(repoDir, newVersion);
        return newVersion;
    }

    // Strips the qualifier suffix (everything from the first '-' onward).
    // e.g. "1.4.2-SNAPSHOT" → "1.4.2",  "2.0.0-RC1" → "2.0.0",  "1.0" → "1.0"
    static String baseVersion(String version) {
        int idx = version.indexOf('-');
        return idx >= 0 ? version.substring(0, idx) : version;
    }
}
