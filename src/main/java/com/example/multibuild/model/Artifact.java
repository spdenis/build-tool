package com.example.multibuild.model;

import java.util.Objects;

public class Artifact {
    private final String groupId;
    private final String artifactId;
    private final String version;

    public Artifact(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    public String getVersion() { return version; }

    public String key() {
        return groupId + ":" + artifactId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Artifact)) return false;
        Artifact other = (Artifact) o;
        return Objects.equals(groupId, other.groupId)
            && Objects.equals(artifactId, other.artifactId)
            && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
