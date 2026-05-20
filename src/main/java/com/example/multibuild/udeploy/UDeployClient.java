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
 *   GET  /cli/application/info?application={name}                           — resolve app → id
 *   GET  /cli/application/snapshotsInApplication?application={nameOrId}     — list snapshots
 *   PUT  /cli/snapshot/createSnapshot                                        — create snapshot
 *   GET  /cli/component/info?component={name}                               — resolve component → id
 *   GET  /cli/component/versions?component={nameOrId}                       — versions (newest first)
 *   GET  /cli/snapshot/getSnapshotVersions?snapshot={snapshotId}            — pinned component versions
 *   PUT  /cli/snapshot/addVersionToSnapshot?snapshot={id}&version={id}      — pin/update version
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
        JsonNode node = get(baseUrl, "/cli/application/info?application=" + encode(applicationName));
        return node.get("id").asText();
    }

    /** Returns the snapshot id, or {@code null} if no snapshot with that name exists. */
    String findSnapshotId(String baseUrl, String appId, String snapshotName) {
        JsonNode snapshots = get(baseUrl, "/cli/application/snapshotsInApplication?application=" + appId);
        for (JsonNode s : snapshots) {
            if (snapshotName.equals(s.path("name").asText())) {
                return s.get("id").asText();
            }
        }
        return null;
    }

    /** Creates a new snapshot and returns its id. */
    String createSnapshot(String baseUrl, String appId, String snapshotName) {
        String body = "{\"name\":\"" + snapshotName + "\",\"application\":\"" + appId + "\"}";
        JsonNode node = put(baseUrl, "/cli/snapshot/createSnapshot", body);
        return node.get("id").asText();
    }

    ComponentInfo resolveComponent(String baseUrl, String componentName) {
        JsonNode node = get(baseUrl, "/cli/component/info?component=" + encode(componentName));
        return new ComponentInfo(node.get("id").asText(), node.path("name").asText());
    }

    /** Returns all versions of a component sorted newest-first. */
    List<VersionInfo> getComponentVersions(String baseUrl, String componentId) {
        JsonNode array = get(baseUrl, "/cli/component/versions?component=" + componentId);
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
        JsonNode array = get(baseUrl, "/cli/snapshot/getSnapshotVersions?snapshot=" + snapshotId);
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

    void addComponentVersion(String baseUrl, String snapshotId, String versionId) {
        putNoBody(baseUrl, "/cli/snapshot/addVersionToSnapshot?snapshot=" + snapshotId + "&version=" + versionId);
    }

    // ── HTTP primitives ──────────────────────────────────────────────────────

    private JsonNode get(String baseUrl, String path) {
        return execute(baseRequest(baseUrl, path).GET().build(), "GET " + path);
    }

    private JsonNode put(String baseUrl, String path, String body) {
        return execute(baseRequest(baseUrl, path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), "PUT " + path);
    }

    private void putNoBody(String baseUrl, String path) {
        execute(baseRequest(baseUrl, path)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build(), "PUT " + path);
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
