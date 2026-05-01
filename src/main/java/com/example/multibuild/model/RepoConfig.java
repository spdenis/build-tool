package com.example.multibuild.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoConfig {

    private String url;
    private String sourceBranch;
    private String teamcitySnapshotConfigId;
    private String teamcityReleaseConfigId;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }

    public String getEffectiveSourceBranch() {
        return sourceBranch != null && !sourceBranch.isBlank() ? sourceBranch : "main";
    }

    public String getTeamcitySnapshotConfigId() { return teamcitySnapshotConfigId; }
    public void setTeamcitySnapshotConfigId(String id) { this.teamcitySnapshotConfigId = id; }

    public String getTeamcityReleaseConfigId() { return teamcityReleaseConfigId; }
    public void setTeamcityReleaseConfigId(String id) { this.teamcityReleaseConfigId = id; }
}
