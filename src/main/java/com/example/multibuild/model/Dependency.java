package com.example.multibuild.model;

public class Dependency {
    private final Artifact artifact;

    public Dependency(Artifact artifact) {
        this.artifact = artifact;
    }

    public Artifact getArtifact() { return artifact; }
}
