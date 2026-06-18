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
import java.util.*;
import java.util.function.BiConsumer;

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
    public void buildAll(List<List<Path>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo,
                         BiConsumer<Path, String> onRepoComplete) {
        // Build reverse lookup: repo root → first artifact (for build-config-pattern fallback)
        Map<Path, Artifact> representativeByRepo = new LinkedHashMap<>();
        for (Map.Entry<Artifact, Module> e : moduleMap.entrySet()) {
            representativeByRepo.putIfAbsent(e.getValue().getRepoRoot(), e.getKey());
        }

        Set<Path> overallSucceeded = new LinkedHashSet<>();

        for (List<Path> layer : layers) {
            record QueuedBuild(String buildId, String configId, Path repoRoot, String branch) {}

            List<Path> repos = layer.stream()
                    .filter(p -> !completedRepoNames.contains(p.getFileName().toString()))
                    .toList();
            if (repos.isEmpty()) continue;

            List<QueuedBuild> queued = new ArrayList<>();
            for (Path repoRoot : repos) {
                Artifact representative = representativeByRepo.get(repoRoot);
                String configId = resolveConfigId(representative, repoConfigs.get(repoRoot));
                String branch = buildBranchByRepo.getOrDefault(repoRoot, integrationBranch);
                log.info("Triggering TeamCity build config {} for {} on branch/tag {}",
                        configId, repoRoot.getFileName(), branch);
                String buildId = triggerBuild(configId, branch);
                log.info("Build queued: id={} repo={}", buildId, repoRoot.getFileName());
                queued.add(new QueuedBuild(buildId, configId, repoRoot, branch));
            }

            List<String> failures = new ArrayList<>();
            for (QueuedBuild b : queued) {
                try {
                    waitForCompletionWithRetry(b.buildId(), b.configId(), b.repoRoot(), b.branch());
                    onRepoComplete.accept(b.repoRoot(), null);
                    overallSucceeded.add(b.repoRoot());
                } catch (RuntimeException e) {
                    String msg = "Build failed in repository: " + b.repoRoot() + "\n" +
                            "  Config ID : " + b.configId() + "\n" +
                            "  Reason    : " + e.getMessage();
                    onRepoComplete.accept(b.repoRoot(), msg);
                    failures.add(msg);
                }
            }

            if (!failures.isEmpty()) {
                throw new BuildFailedException(
                        failures.size() + " build(s) failed in parallel layer:\n" +
                        String.join("\n---\n", failures),
                        overallSucceeded);
            }
        }
    }

    private void waitForCompletionWithRetry(String buildId, String configId, Path repoRoot, String branch) {
        int maxAttempts = props.getMaxRetries() + 1;
        String repoName = repoRoot.getFileName().toString();
        String currentBuildId = buildId;
        Set<Integer> excludedAgentIds = new LinkedHashSet<>();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForCompletion(currentBuildId, repoName);
                return;
            } catch (RuntimeException e) {
                if (attempt == maxAttempts) throw e;
                Integer failedAgentId = getAgentId(currentBuildId);
                if (failedAgentId != null) {
                    excludedAgentIds.add(failedAgentId);
                    log.warn("  [TC] Build {} for {} failed on agent id={} (attempt {}/{}): {}",
                            currentBuildId, repoName, failedAgentId, attempt, maxAttempts, e.getMessage());
                } else {
                    log.warn("  [TC] Build {} for {} failed (attempt {}/{}): {}",
                            currentBuildId, repoName, attempt, maxAttempts, e.getMessage());
                }
                Integer alternativeAgentId = pickAlternativeAgent(configId, excludedAgentIds);
                if (alternativeAgentId != null) {
                    log.info("  [TC] Retriggering build config {} for {} (attempt {}) on agent id={}",
                            configId, repoName, attempt + 1, alternativeAgentId);
                } else {
                    log.info("  [TC] Retriggering build config {} for {} (attempt {}) — no alternative agent found, letting TC decide",
                            configId, repoName, attempt + 1);
                }
                currentBuildId = triggerBuild(configId, branch, alternativeAgentId);
                log.info("  [TC] Retry build queued: id={} repo={}", currentBuildId, repoName);
            }
        }
    }

    private Integer getAgentId(String buildId) {
        try {
            String url = props.getUrl() + "/app/rest/builds/id:" + buildId + "?fields=agent(id,name)";
            HttpResponse<String> response = httpClient.send(
                    jsonRequest("GET", url, null), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) return null;
            JsonNode agentNode = objectMapper.readTree(response.body()).path("agent");
            if (agentNode.isMissingNode()) return null;
            log.debug("Failed build {} ran on agent id={} name={}",
                    buildId, agentNode.path("id").asInt(), agentNode.path("name").asText());
            return agentNode.path("id").asInt();
        } catch (Exception e) {
            log.debug("Could not retrieve agent id for build {}: {}", buildId, e.getMessage());
            return null;
        }
    }

    private Integer pickAlternativeAgent(String configId, Set<Integer> excludedAgentIds) {
        try {
            String url = props.getUrl() + "/app/rest/agents" +
                    "?locator=compatible:(buildType:(id:" + configId + ")),enabled:true,connected:true,authorized:true" +
                    "&fields=agent(id,name)";
            HttpResponse<String> response = httpClient.send(
                    jsonRequest("GET", url, null), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.debug("Could not fetch compatible agents for {}: HTTP {}", configId, response.statusCode());
                return null;
            }
            JsonNode agents = objectMapper.readTree(response.body()).path("agent");
            if (!agents.isArray()) return null;
            for (JsonNode agent : agents) {
                int id = agent.path("id").asInt();
                if (!excludedAgentIds.contains(id)) {
                    log.debug("Selected alternative agent id={} name={}", id, agent.path("name").asText());
                    return id;
                }
            }
            log.debug("No compatible agent available outside excluded set {} for {}", excludedAgentIds, configId);
            return null;
        } catch (Exception e) {
            log.debug("Could not pick alternative agent for {}: {}", configId, e.getMessage());
            return null;
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
        if (artifact != null) {
            return props.getBuildConfigPattern()
                    .replace("{groupId}", artifact.getGroupId())
                    .replace("{artifactId}", artifact.getArtifactId())
                    .replace("{version}", artifact.getVersion());
        }
        throw new RuntimeException("Cannot resolve TeamCity config ID: no RepoConfig and no artifacts found for repo");
    }

    private String triggerBuild(String configId, String branchName) {
        return triggerBuild(configId, branchName, null);
    }

    private String triggerBuild(String configId, String branchName, Integer pinnedAgentId) {
        if (props.getUrl().isBlank()) {
            throw new RuntimeException("TeamCity is not configured: set teamcity.url and teamcity.token");
        }
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("buildType", Map.of("id", configId));
            if (!branchName.isBlank()) {
                payload.put("branchName", branchName);
            }
            if (pinnedAgentId != null) {
                payload.put("agent", Map.of("id", pinnedAgentId));
            }
            List<Map<String, String>> buildProps = new ArrayList<>();
            buildProps.add(Map.of("name", "system.skipTests", "value", "true"));
            props.getBuildParameters().forEach((k, v) -> buildProps.add(Map.of("name", k, "value", v)));
            payload.put("properties", Map.of("property", buildProps));
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

    private void waitForCompletion(String buildId, String repoName) {
        long startMs = System.currentTimeMillis();
        long deadline = startMs + props.getTimeoutMs();
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

                log.info("  [TC] Build {} ({}) state={} status={} elapsed={}",
                        buildId, repoName, state, status, elapsed(startMs));

                if ("finished".equalsIgnoreCase(state)) {
                    if (!"SUCCESS".equalsIgnoreCase(status)) {
                        throw new RuntimeException(
                                "TeamCity build " + buildId + " for " + repoName + " finished with status: " + status);
                    }
                    log.info("  [TC] Build {} for {} succeeded in {}", buildId, repoName, elapsed(startMs));
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for build " + buildId, e);
            } catch (IOException e) {
                log.warn("Error polling build {}", buildId, e);
            }
        }
        throw new RuntimeException("Timeout waiting for TeamCity build " + buildId + " (" + repoName + ")");
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
