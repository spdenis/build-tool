package com.example.multibuild.udeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
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
 *   PUT  /cli/snapshot/addVersionToSnapshot?snapshot={id}&version={id}      — pin version
 *   PUT  /cli/snapshot/removeVersionFromSnapshot?snapshot={id}&version={id} — unpin version
 */
@Component
@EnableConfigurationProperties(UDeployProperties.class)
class UDeployClient {

    private final UDeployProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    UDeployClient(UDeployProperties props, ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
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
     * Returns one entry per pinned version per component.
     * Response: [{name: componentName, desiredVersions: [{id, name}, ...]}, ...]
     */
    List<SnapshotEntry> getSnapshotVersions(String baseUrl, String snapshotId) {
        JsonNode array = get(baseUrl, "/cli/snapshot/getSnapshotVersions?snapshot=" + snapshotId);
        List<SnapshotEntry> result = new ArrayList<>();
        for (JsonNode entry : array) {
            String componentName = entry.path("name").asText();
            JsonNode desiredVersions = entry.path("desiredVersions");
            if (desiredVersions.isMissingNode() || !desiredVersions.isArray()) continue;
            for (JsonNode ver : desiredVersions) {
                result.add(new SnapshotEntry(
                        componentName,
                        ver.path("id").asText(),
                        ver.path("name").asText()));
            }
        }
        return result;
    }

    void addComponentVersion(String baseUrl, String snapshotId, String versionId) {
        putNoBody(baseUrl, "/cli/snapshot/addVersionToSnapshot?snapshot=" + snapshotId + "&version=" + versionId);
    }

    void removeComponentVersion(String baseUrl, String snapshotId, String versionId) {
        putNoBody(baseUrl, "/cli/snapshot/removeVersionFromSnapshot?snapshot=" + snapshotId + "&version=" + versionId);
    }

    // ── HTTP primitives ──────────────────────────────────────────────────────

    private JsonNode get(String baseUrl, String path) {
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(baseUrl.stripTrailing() + path),
                HttpMethod.GET, new HttpEntity<>(udeployHeaders(null)), String.class);
        return parseResponse(resp, "GET " + path);
    }

    private JsonNode put(String baseUrl, String path, String body) {
        HttpHeaders headers = udeployHeaders(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(baseUrl.stripTrailing() + path),
                HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        return parseResponse(resp, "PUT " + path);
    }

    private void putNoBody(String baseUrl, String path) {
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(baseUrl.stripTrailing() + path),
                HttpMethod.PUT, new HttpEntity<>(udeployHeaders(null)), String.class);
        parseResponse(resp, "PUT " + path);
    }

    private JsonNode parseResponse(ResponseEntity<String> resp, String operation) {
        if (resp.getStatusCode().value() >= 300) {
            throw new RuntimeException("uDeploy " + operation + " failed (" +
                    resp.getStatusCode().value() + "): " + resp.getBody());
        }
        String body = resp.getBody();
        try {
            if (body == null || body.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("uDeploy response parse failed: " + operation, e);
        }
    }

    private HttpHeaders udeployHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + basicAuth());
        headers.set("Accept", "application/json");
        if (contentType != null) headers.setContentType(contentType);
        return headers;
    }

    private String basicAuth() {
        return Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
