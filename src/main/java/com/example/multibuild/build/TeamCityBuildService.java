package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "build.service", havingValue = "teamcity")
@EnableConfigurationProperties(TeamCityProperties.class)
public class TeamCityBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(TeamCityBuildService.class);

    @Value("${build.mode:snapshot}")
    private String buildMode;

    @Value("${integration.branch:}")
    private String integrationBranch;

    private final TeamCityProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public TeamCityBuildService(TeamCityProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs) {
        for (List<Artifact> layer : layers) {
            // Trigger all builds in this layer simultaneously
            record QueuedBuild(String buildId, String configId, Artifact artifact, Module module) {}
            List<QueuedBuild> queued = new ArrayList<>();
            for (Artifact artifact : layer) {
                Module module = moduleMap.get(artifact);
                if (module == null) continue;
                String configId = resolveConfigId(artifact, repoConfigs.get(module.getRepoRoot()));
                log.info("Triggering TeamCity build config {} for {}", configId, artifact);
                String buildId = triggerBuild(configId);
                log.info("Build queued: id={}", buildId);
                queued.add(new QueuedBuild(buildId, configId, artifact, module));
            }

            // Wait for all builds in this layer to finish; collect failures
            List<String> failures = new ArrayList<>();
            for (QueuedBuild b : queued) {
                try {
                    waitForCompletion(b.buildId(), b.artifact());
                } catch (RuntimeException e) {
                    failures.add("Build failed in repository: " + b.module().getRepoRoot() + "\n" +
                            "  Artifact  : " + b.artifact() + "\n" +
                            "  Config ID : " + b.configId() + "\n" +
                            "  Reason    : " + e.getMessage());
                }
            }
            if (!failures.isEmpty()) {
                throw new RuntimeException(
                        failures.size() + " build(s) failed in parallel layer:\n" +
                        String.join("\n---\n", failures));
            }
        }
    }

    private String resolveConfigId(Artifact artifact, RepoConfig repoConfig) {
        if (repoConfig != null) {
            boolean release = "release".equalsIgnoreCase(buildMode);
            String explicit = release
                    ? repoConfig.getTeamcityReleaseConfigId()
                    : repoConfig.getTeamcitySnapshotConfigId();
            if (explicit != null && !explicit.isBlank()) {
                return explicit;
            }
        }
        return props.getBuildConfigPattern()
                .replace("{groupId}", artifact.getGroupId())
                .replace("{artifactId}", artifact.getArtifactId())
                .replace("{version}", artifact.getVersion());
    }

    private String triggerBuild(String configId) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("buildType", Map.of("id", configId));
            if (!integrationBranch.isBlank()) {
                payload.put("branchName", integrationBranch);
            }
            String body = objectMapper.writeValueAsString(payload);

            HttpResponse<String> response = httpClient.send(
                    jsonRequest("POST", props.getUrl() + "/app/rest/buildQueue", body),
                    HttpResponse.BodyHandlers.ofString());

            assertSuccess(response, "trigger build " + configId);
            return objectMapper.readTree(response.body()).get("id").asText();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to trigger TeamCity build for " + configId, e);
        }
    }

    private void waitForCompletion(String buildId, Artifact artifact) {
        long deadline = System.currentTimeMillis() + props.getTimeoutMs();
        String url = props.getUrl() + "/app/rest/builds/id:" + buildId + "?fields=state,status";

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(props.getPollIntervalMs());

                HttpResponse<String> response = httpClient.send(
                        jsonRequest("GET", url, null),
                        HttpResponse.BodyHandlers.ofString());

                assertSuccess(response, "poll build " + buildId);
                JsonNode json = objectMapper.readTree(response.body());
                String state = json.path("state").asText();
                String status = json.path("status").asText();
                log.debug("Build {}: state={} status={}", buildId, state, status);

                if ("finished".equalsIgnoreCase(state)) {
                    if (!"SUCCESS".equalsIgnoreCase(status)) {
                        throw new RuntimeException(
                                "TeamCity build " + buildId + " for " + artifact + " finished with status: " + status);
                    }
                    log.info("Build {} for {} succeeded", buildId, artifact);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for build " + buildId, e);
            } catch (IOException e) {
                log.warn("Error polling build {}: {}", buildId, e.getMessage());
            }
        }
        throw new RuntimeException("Timeout waiting for TeamCity build " + buildId + " (" + artifact + ")");
    }

    private HttpRequest jsonRequest(String method, String url, String body) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + props.getToken())
                .header("Accept", "application/json");

        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private void assertSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 300) {
            throw new RuntimeException("TeamCity " + operation + " failed (" +
                    response.statusCode() + "): " + response.body());
        }
    }
}
