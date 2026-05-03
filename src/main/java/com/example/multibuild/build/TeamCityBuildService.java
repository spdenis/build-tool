package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Qualifier("teamcity")
@EnableConfigurationProperties(TeamCityProperties.class)
public class TeamCityBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(TeamCityBuildService.class);

    @Value("${build.mode:SNAPSHOT}")
    private BuildMode buildMode;

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
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo) {
        Set<Path> overallSucceeded = new LinkedHashSet<>();

        for (List<Artifact> layer : layers) {
            record QueuedBuild(String buildId, String configId, Artifact artifact, Module module) {}

            // Trigger all non-completed builds in this layer simultaneously
            List<QueuedBuild> queued = new ArrayList<>();
            for (Artifact artifact : layer) {
                Module module = moduleMap.get(artifact);
                if (module == null) continue;
                if (completedRepoNames.contains(module.getRepoRoot().getFileName().toString())) continue;
                String configId = resolveConfigId(artifact, repoConfigs.get(module.getRepoRoot()));
                String branch = buildBranchByRepo.getOrDefault(module.getRepoRoot(), integrationBranch);
                log.info("Triggering TeamCity build config {} for {} on branch/tag {}", configId, artifact, branch);
                String buildId = triggerBuild(configId, branch);
                log.info("Build queued: id={}", buildId);
                queued.add(new QueuedBuild(buildId, configId, artifact, module));
            }
            if (queued.isEmpty()) continue;

            // Wait for all; track per-repo success (a repo succeeds only if all its artifacts succeed)
            Map<Path, Boolean> repoSuccess = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();

            for (QueuedBuild b : queued) {
                try {
                    waitForCompletion(b.buildId(), b.artifact());
                    repoSuccess.merge(b.module().getRepoRoot(), true, Boolean::logicalAnd);
                } catch (RuntimeException e) {
                    repoSuccess.put(b.module().getRepoRoot(), false);
                    failures.add("Build failed in repository: " + b.module().getRepoRoot() + "\n" +
                            "  Artifact  : " + b.artifact() + "\n" +
                            "  Config ID : " + b.configId() + "\n" +
                            "  Reason    : " + e.getMessage());
                }
            }

            repoSuccess.forEach((repo, ok) -> { if (ok) overallSucceeded.add(repo); });

            if (!failures.isEmpty()) {
                throw new BuildFailedException(
                        failures.size() + " build(s) failed in parallel layer:\n" +
                        String.join("\n---\n", failures),
                        overallSucceeded);
            }
        }
    }

    private String resolveConfigId(Artifact artifact, RepoConfig repoConfig) {
        if (repoConfig != null) {
            boolean release = buildMode == BuildMode.RELEASE;
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

    private String triggerBuild(String configId, String branchName) {
        if (props.getUrl().isBlank()) {
            throw new RuntimeException("TeamCity is not configured: set teamcity.url and teamcity.token");
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("buildType", Map.of("id", configId));
            if (!branchName.isBlank()) {
                payload.put("branchName", branchName);
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
        long startMs = System.currentTimeMillis();
        long deadline = startMs + props.getTimeoutMs();
        String url = props.getUrl() + "/app/rest/builds/id:" + buildId + "?fields=state,status";
        String lastState = "";

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

                if (!state.equals(lastState)) {
                    log.info("  [TC] Build {} ({}) → state={} status={} elapsed={}",
                            buildId, artifact.getArtifactId(), state, status, elapsed(startMs));
                    lastState = state;
                } else {
                    log.debug("Build {}: state={} status={} elapsed={}", buildId, state, status, elapsed(startMs));
                }

                if ("finished".equalsIgnoreCase(state)) {
                    if (!"SUCCESS".equalsIgnoreCase(status)) {
                        throw new RuntimeException(
                                "TeamCity build " + buildId + " for " + artifact + " finished with status: " + status);
                    }
                    log.info("  [TC] Build {} for {} succeeded in {}", buildId, artifact.getArtifactId(), elapsed(startMs));
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

    private static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
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
