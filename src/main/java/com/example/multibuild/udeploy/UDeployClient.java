package com.example.multibuild.udeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Low-level REST client for IBM UrbanCode Deploy (uDeploy 6.x / 7.x).
 * All public methods accept an explicit {@code baseUrl} so the caller can target
 * different uDeploy servers without needing separate client instances.
 *
 * Endpoints used:
 *   GET    /application                                              — list all apps (filter by name)
 *   GET    /application/{appId}/snapshots/false/0/-1                — list snapshots
 *   POST   /snapshot                                                — create snapshot
 *   GET    /component/{name}/false                                  — resolve component → id
 *   GET    /component/{compId}/versions/false/0/-1/true             — versions (newest first)
 *   GET    /snapshot/{snapshotId}/configuration/versions            — pinned component versions
 *   PUT    /snapshot/{snapshotId}/configuration/versions/{compId}   — pin version
 *   DELETE /snapshot/{snapshotId}/configuration/versions/{compId}   — unpin version
 */
@Component
@EnableConfigurationProperties(UDeployProperties.class)
class UDeployClient {

    private final UDeployProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    UDeployClient(UDeployProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    String resolveApplicationId(String baseUrl, String applicationName) {
        JsonNode apps = get(baseUrl, "/application");
        for (JsonNode app : apps) {
            if (applicationName.equals(app.path("name").asText())) {
                return app.get("id").asText();
            }
        }
        throw new RuntimeException("Application '" + applicationName + "' not found in uDeploy");
    }

    /** Returns the snapshot id, or {@code null} if no snapshot with that name exists. */
    String findSnapshotId(String baseUrl, String appId, String snapshotName) {
        JsonNode snapshots = get(baseUrl, "/application/" + appId + "/snapshots/false/0/-1");
        for (JsonNode s : snapshots) {
            if (snapshotName.equals(s.path("name").asText())) {
                return s.get("id").asText();
            }
        }
        return null;
    }

    /** Creates a new snapshot and returns its id. */
    String createSnapshot(String baseUrl, String appId, String snapshotName) {
        String body = "{\"name\":\"" + snapshotName + "\",\"application\":{\"id\":\"" + appId + "\"}}";
        JsonNode node = post(baseUrl, "/snapshot", body);
        return node.get("id").asText();
    }

    ComponentInfo resolveComponent(String baseUrl, String componentName) {
        JsonNode node = get(baseUrl, "/component/" + encode(componentName) + "/false");
        return new ComponentInfo(node.get("id").asText(), node.path("name").asText());
    }

    /** Returns all versions of a component sorted newest-first. */
    List<VersionInfo> getComponentVersions(String baseUrl, String componentId) {
        JsonNode array = get(baseUrl, "/component/" + componentId + "/versions/false/0/-1/true");
        List<VersionInfo> result = new ArrayList<>();
        for (JsonNode v : array) {
            result.add(new VersionInfo(v.get("id").asText(), v.path("name").asText()));
        }
        return result;
    }

    /**
     * Returns the component versions currently pinned in the snapshot.
     * Response: [{component:{id,name}, desiredVersion:{id,name}}, ...]
     */
    List<SnapshotEntry> getSnapshotVersions(String baseUrl, String snapshotId) {
        JsonNode array = get(baseUrl, "/snapshot/" + snapshotId + "/configuration/versions");
        List<SnapshotEntry> result = new ArrayList<>();
        for (JsonNode entry : array) {
            JsonNode comp = entry.path("component");
            JsonNode ver = entry.path("desiredVersion");
            if (comp.isMissingNode() || ver.isMissingNode() || ver.isNull()) continue;
            result.add(new SnapshotEntry(
                    comp.path("id").asText(),
                    comp.path("name").asText(),
                    ver.path("id").asText(),
                    ver.path("name").asText()));
        }
        return result;
    }

    void addComponentVersion(String baseUrl, String snapshotId, String componentId, String versionId) {
        String body = "{\"version\":{\"id\":\"" + versionId + "\"},\"type\":\"DESIRED\"}";
        put(baseUrl, "/snapshot/" + snapshotId + "/configuration/versions/" + componentId, body);
    }

    void removeComponentVersion(String baseUrl, String snapshotId, String componentId) {
        delete(baseUrl, "/snapshot/" + snapshotId + "/configuration/versions/" + componentId);
    }

    // ── HTTP primitives ──────────────────────────────────────────────────────

    private JsonNode get(String baseUrl, String path) {
        return execute(baseRequest(baseUrl, path).GET().build(), "GET " + path);
    }

    private JsonNode post(String baseUrl, String path, String body) {
        return execute(baseRequest(baseUrl, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), "POST " + path);
    }

    private void put(String baseUrl, String path, String body) {
        execute(baseRequest(baseUrl, path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), "PUT " + path);
    }

    private void delete(String baseUrl, String path) {
        execute(baseRequest(baseUrl, path).DELETE().build(), "DELETE " + path);
    }

    private HttpRequest.Builder baseRequest(String baseUrl, String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.stripTrailing() + path))
                .header("Authorization", "Basic " + basicAuth())
                .header("Accept", "application/json");
    }

    private JsonNode execute(HttpRequest req, String operation) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new RuntimeException("uDeploy " + operation + " failed (" +
                        resp.statusCode() + "): " + resp.body());
            }
            String body = resp.body();
            if (body == null || body.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("uDeploy request failed: " + operation, e);
        }
    }

    private String basicAuth() {
        return Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
