package com.example.multibuild.build;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lightspeed")
public class LightspeedProperties {

    private MavenRepo mavenRepo = new MavenRepo();
    private long pollIntervalMs = 20_000;
    private long timeoutMs = 1_800_000; // 30 minutes

    public MavenRepo getMavenRepo() { return mavenRepo; }
    public void setMavenRepo(MavenRepo mavenRepo) { this.mavenRepo = mavenRepo; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public static class MavenRepo {
        private String snapshotsUrl = "";
        private String releasesUrl = "";
        private String username = "";
        private String password = "";

        public String getSnapshotsUrl() { return snapshotsUrl; }
        public void setSnapshotsUrl(String snapshotsUrl) { this.snapshotsUrl = snapshotsUrl; }

        public String getReleasesUrl() { return releasesUrl; }
        public void setReleasesUrl(String releasesUrl) { this.releasesUrl = releasesUrl; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
