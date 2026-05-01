package com.example.multibuild.build;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "teamcity")
public class TeamCityProperties {

    private String url = "";
    private String token = "";
    /** Pattern for resolving the TeamCity build config ID from an artifact.
     *  Placeholders: {groupId}, {artifactId}, {version} */
    private String buildConfigPattern = "{artifactId}";
    private long pollIntervalMs = 5000;
    private long timeoutMs = 600_000;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getBuildConfigPattern() { return buildConfigPattern; }
    public void setBuildConfigPattern(String buildConfigPattern) { this.buildConfigPattern = buildConfigPattern; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
