package com.example.multibuild.model;

import java.nio.file.Path;
import java.util.List;

public class Module {
    private final Artifact artifact;
    private final List<Dependency> dependencies;
    private final Path directory;
    private final Path repoRoot;

    public Module(Artifact artifact, List<Dependency> dependencies, Path directory, Path repoRoot) {
        this.artifact = artifact;
        this.dependencies = dependencies;
        this.directory = directory;
        this.repoRoot = repoRoot;
    }

    public Artifact getArtifact() { return artifact; }
    public List<Dependency> getDependencies() { return dependencies; }
    public Path getDirectory() { return directory; }
    public Path getRepoRoot() { return repoRoot; }
}
