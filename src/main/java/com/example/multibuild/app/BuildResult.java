package com.example.multibuild.app;

import com.example.multibuild.model.Artifact;

import java.util.List;

public class BuildResult {
    public List<ArtifactDto> artifacts;
    public List<DependencyDto> dependencies;
    public List<String> buildOrder;

    public static class ArtifactDto {
        public String groupId;
        public String artifactId;
        public String version;

        public ArtifactDto(Artifact a) {
            this.groupId = a.getGroupId();
            this.artifactId = a.getArtifactId();
            this.version = a.getVersion();
        }
    }

    public static class DependencyDto {
        public String from;
        public String to;

        public DependencyDto(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }
}
