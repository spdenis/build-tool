package com.example.multibuild.udeploy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "udeploy")
public class UDeployProperties {

    private String url = "";
    private String username = "";
    private String password = "";
    // Application name in uDeploy (contains the snapshot)
    private String application = "";
    // Snapshot name to update
    private String snapshot = "";
    // Regex matched against component version names in SNAPSHOT build mode
    private String versionPatternSnapshot = ".*";
    // Regex matched against component version names in RELEASE build mode
    private String versionPatternRelease = ".*";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getApplication() { return application; }
    public void setApplication(String application) { this.application = application; }

    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }

    public String getVersionPatternSnapshot() { return versionPatternSnapshot; }
    public void setVersionPatternSnapshot(String p) { this.versionPatternSnapshot = p; }

    public String getVersionPatternRelease() { return versionPatternRelease; }
    public void setVersionPatternRelease(String p) { this.versionPatternRelease = p; }
}
