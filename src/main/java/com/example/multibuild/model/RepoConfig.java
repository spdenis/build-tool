package com.example.multibuild.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoConfig {

    private String url;
    private String sourceBranch;
    private String version;
    private BuildServiceType buildService;
    private String teamcitySnapshotConfigId;
    private String teamcityReleaseConfigId;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }

    public String getEffectiveSourceBranch() {
        return sourceBranch != null && !sourceBranch.isBlank() ? sourceBranch : "main";
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
}
