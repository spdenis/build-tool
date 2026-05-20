package com.example.multibuild.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoConfig {

    private String url;
    private String sourceBranch;
    private String version;
    private BuildServiceType buildService;
    private String teamcitySnapshotConfigId;
    private String teamcityReleaseConfigId;
    private boolean dryRun;
    private boolean preserveVersion;
    // Artifact coordinates (groupId:artifactId) to poll in the Maven release repo after a release build.
    // Required for repos without pom.xml; ignored for repos that have one (modules are discovered automatically).
    private List<String> releaseArtifacts;

    // uDeploy component name for this repository. When set, the update-snapshot command will
    // update the snapshot to point to the latest matching version of this component.
    private String udeployComponent;

    // Overrides the global udeploy.application property for this repository.
    private String udeployApplication;

    // Overrides the global udeploy.url property for this repository.
    private String udeployUrl;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }

    public String getEffectiveSourceBranch(String defaultBranch) {
        return sourceBranch != null && !sourceBranch.isBlank() ? sourceBranch : defaultBranch;
    }

    // When set, overrides the version read from pom.xml for all modules in this repo.
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public boolean hasVersionOverride() {
        return version != null && !version.isBlank();
    }

    // Per-repo build service override. When absent, the global build.service property is used.
    public BuildServiceType getBuildService() { return buildService; }
    public void setBuildService(BuildServiceType buildService) { this.buildService = buildService; }

    public String getTeamcitySnapshotConfigId() { return teamcitySnapshotConfigId; }
    public void setTeamcitySnapshotConfigId(String id) { this.teamcitySnapshotConfigId = id; }

    public String getTeamcityReleaseConfigId() { return teamcityReleaseConfigId; }
    public void setTeamcityReleaseConfigId(String id) { this.teamcityReleaseConfigId = id; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public boolean isPreserveVersion() { return preserveVersion; }
    public void setPreserveVersion(boolean preserveVersion) { this.preserveVersion = preserveVersion; }

    public List<String> getReleaseArtifacts() { return releaseArtifacts != null ? releaseArtifacts : List.of(); }
    public void setReleaseArtifacts(List<String> releaseArtifacts) { this.releaseArtifacts = releaseArtifacts; }

    public String getUdeployComponent() { return udeployComponent; }
    public void setUdeployComponent(String udeployComponent) { this.udeployComponent = udeployComponent; }

    public String getUdeployApplication() { return udeployApplication; }
    public void setUdeployApplication(String udeployApplication) { this.udeployApplication = udeployApplication; }

    public String getUdeployUrl() { return udeployUrl; }
    public void setUdeployUrl(String udeployUrl) { this.udeployUrl = udeployUrl; }
}
