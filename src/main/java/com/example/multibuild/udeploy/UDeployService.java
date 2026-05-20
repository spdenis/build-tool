package com.example.multibuild.udeploy;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@EnableConfigurationProperties(UDeployProperties.class)
public class UDeployService {

    private static final Logger log = LoggerFactory.getLogger(UDeployService.class);

    private final UDeployProperties props;
    private final UDeployClient client;

    public UDeployService(UDeployProperties props, UDeployClient client) {
        this.props = props;
        this.client = client;
    }

    public boolean isConfigured() {
        return !props.getUrl().isBlank()
                && !props.getUsername().isBlank()
                && !props.getApplication().isBlank()
                && !props.getSnapshot().isBlank();
    }

    /**
     * Updates the configured uDeploy snapshot so that each repo's component is pinned to the
     * latest version whose name matches the mode's pattern. Repos without udeployComponent
     * are silently skipped.
     */
    public void updateSnapshot(List<RepoConfig> repos, BuildMode mode) {
        validate();
        Pattern pattern = Pattern.compile(
                mode == BuildMode.RELEASE ? props.getVersionPatternRelease() : props.getVersionPatternSnapshot());

        // Cache snapshot resolution — repos sharing the same (url, application) pair hit the API only once.
        Map<String, String> snapshotIdByKey = new HashMap<>();
        Map<String, List<SnapshotEntry>> currentVersionsBySnapshot = new HashMap<>();

        for (RepoConfig repo : repos) {
            String componentName = repo.getUdeployComponent();
            if (componentName == null || componentName.isBlank()) continue;

            String url = effectiveUrl(repo);
            String appName = effectiveApplication(repo);
            String cacheKey = url + "|" + appName;
            String snapshotId = snapshotIdByKey.computeIfAbsent(cacheKey, k ->
                    resolveOrCreateSnapshot(url, appName));


            List<SnapshotEntry> currentVersions = currentVersionsBySnapshot.computeIfAbsent(
                    snapshotId, id -> client.getSnapshotVersions(url, id));

            updateComponent(url, snapshotId, componentName, pattern, currentVersions);
        }
    }

    private void updateComponent(String baseUrl, String snapshotId, String componentName, Pattern pattern,
                                 List<SnapshotEntry> currentVersions) {
        log.info("  Component: {}", componentName);

        ComponentInfo comp;
        try {
            comp = client.resolveComponent(baseUrl, componentName);
        } catch (RuntimeException e) {
            log.warn("    Component '{}' not found in uDeploy — skipping", componentName);
            return;
        }

        List<VersionInfo> versions = client.getComponentVersions(baseUrl, comp.id());
        VersionInfo target = versions.stream()
                .filter(v -> pattern.matcher(v.name()).matches())
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.warn("    No version matching '{}' found for '{}' — skipping",
                    pattern.pattern(), componentName);
            return;
        }

        SnapshotEntry existing = currentVersions.stream()
                .filter(e -> e.componentName().equals(componentName))
                .findFirst()
                .orElse(null);

        if (existing != null && existing.versionName().equals(target.name())) {
            log.info("    Already at version '{}' — nothing to do", target.name());
            return;
        }

        log.info("    Pinning version '{}' …", target.name());
        client.addComponentVersion(baseUrl, snapshotId, target.id());
        log.info("    Done: {} → {}", existing != null ? existing.versionName() : "(none)", target.name());
    }

    private String resolveOrCreateSnapshot(String url, String appName) {
        String snapshotName = props.getSnapshot();
        log.info("Resolving uDeploy snapshot '{}/{}' …", appName, snapshotName);
        String appId = client.resolveApplicationId(url, appName);
        String sid = client.findSnapshotId(url, appId, snapshotName);
        if (sid != null) {
            log.info("  Snapshot id: {}", sid);
            return sid;
        }
        log.info("  Snapshot '{}' not found — creating …", snapshotName);
        sid = client.createSnapshot(url, appId, snapshotName);
        log.info("  Created snapshot id: {}", sid);
        return sid;
    }

    private String effectiveUrl(RepoConfig repo) {
        String override = repo.getUdeployUrl();
        return override != null && !override.isBlank() ? override : props.getUrl();
    }

    private String effectiveApplication(RepoConfig repo) {
        String override = repo.getUdeployApplication();
        return override != null && !override.isBlank() ? override : props.getApplication();
    }

    private void validate() {
        List<String> missing = new ArrayList<>();
        if (props.getUrl().isBlank())         missing.add("udeploy.url");
        if (props.getUsername().isBlank())    missing.add("udeploy.username");
        if (props.getPassword().isBlank())    missing.add("udeploy.password");
        if (props.getApplication().isBlank()) missing.add("udeploy.application");
        if (props.getSnapshot().isBlank())    missing.add("udeploy.snapshot");
        if (!missing.isEmpty()) {
            throw new RuntimeException("Missing required uDeploy properties: " + String.join(", ", missing));
        }
    }
}
