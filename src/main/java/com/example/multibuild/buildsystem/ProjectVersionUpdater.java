package com.example.multibuild.buildsystem;

import com.example.multibuild.gradle.GradleVersionUpdater;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

// Delegates to the version updater of each repo's build system.
@Component
@Primary
public class ProjectVersionUpdater implements VersionUpdater {

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
    }

    @Override
    public String updateVersions(Path repoDir, String branchName) {
        return forRepo(repoDir).updateVersions(repoDir, branchName);
    }

    @Override
    public String updateVersionsBare(Path repoDir) {
        return forRepo(repoDir).updateVersionsBare(repoDir);
    }
}
