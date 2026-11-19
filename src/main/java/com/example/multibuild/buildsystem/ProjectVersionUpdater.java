package com.example.multibuild.buildsystem;

import com.example.multibuild.gradle.GradleVersionUpdater;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

// Delegates to the version updater of each repo's build system. While a repo carries both builds
// (Gradle migration in progress) Gradle is authoritative and the poms are kept in sync with its version.
@Component
@Primary
public class ProjectVersionUpdater implements VersionUpdater {

    private static final Logger log = LoggerFactory.getLogger(ProjectVersionUpdater.class);

    private final PomVersionUpdater maven;
    private final GradleVersionUpdater gradle;

    public ProjectVersionUpdater(PomVersionUpdater maven, GradleVersionUpdater gradle) {
        this.maven = maven;
        this.gradle = gradle;
    }

    private VersionUpdater forRepo(Path repoDir) {
        return BuildSystem.detect(repoDir) == BuildSystem.GRADLE ? gradle : maven;
    }

    @Override
    public String getRootVersion(Path repoDir) {
        return forRepo(repoDir).getRootVersion(repoDir);
    }

    @Override
    public Artifact getRootArtifact(Path repoDir) {
        return forRepo(repoDir).getRootArtifact(repoDir);
    }

    @Override
    public void setVersions(Path repoDir, String newVersion) {
        forRepo(repoDir).setVersions(repoDir, newVersion);
        syncMaven(repoDir, newVersion);
    }

    @Override
    public String updateVersions(Path repoDir, String branchName) {
        return syncMaven(repoDir, forRepo(repoDir).updateVersions(repoDir, branchName));
    }

    @Override
    public String updateVersionsBare(Path repoDir) {
        return syncMaven(repoDir, forRepo(repoDir).updateVersionsBare(repoDir));
    }

    // Applies the Gradle build's new version to poms kept alongside it.
    private String syncMaven(Path repoDir, String newVersion) {
        if (newVersion != null && BuildSystem.detect(repoDir) == BuildSystem.GRADLE && BuildSystem.hasMavenBuild(repoDir)) {
            try {
                maven.setVersions(repoDir, newVersion);
            } catch (RuntimeException e) {
                log.warn("Kept Maven build in {} not updated to {}: {}", repoDir.getFileName(), newVersion, e.getMessage());
            }
        }
        return newVersion;
    }
}
