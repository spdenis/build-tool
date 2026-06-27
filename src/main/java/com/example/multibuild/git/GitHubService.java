package com.example.multibuild.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    @Value("${github.token:}")
    private String token;

    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public GitHubService(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.http = httpClient;
    }

    /**
     * Creates a PR from {@code head} into {@code base} in the GitHub repo identified by {@code repoUrl}.
     * If a PR already exists for this head→base pair, returns its URL instead of failing.
     * Works with both github.com and GitHub Enterprise (inferred from the URL host).
     */
    public String createOrFindPr(String repoUrl, String head, String base, String title) {
        RepoCoords coords = parseCoords(repoUrl);
        String pullsEndpoint = apiBaseUrl(coords.host()) + "/repos/" + coords.owner() + "/" + coords.repo() + "/pulls";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("head", head);
        body.put("base", base);
        body.put("body", "");

        try {
            HttpRequest request = requestBuilder(pullsEndpoint)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                return objectMapper.readTree(response.body()).get("html_url").asText();
            }
            if (response.statusCode() == 422) {
                // A PR for this head→base pair already exists.
                return findExistingPr(pullsEndpoint, coords.owner(), head, base);
            }
            throw new RuntimeException("GitHub API error " + response.statusCode() + " creating PR for "
                    + coords.owner() + "/" + coords.repo() + ": " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to create PR for " + coords.owner() + "/" + coords.repo(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create PR for " + coords.owner() + "/" + coords.repo(), e);
        }
    }

    private String findExistingPr(String pullsEndpoint, String owner, String head, String base) {
        String url = pullsEndpoint + "?head=" + owner + ":" + head + "&base=" + base + "&state=open";
        try {
            HttpResponse<String> response = http.send(
                    requestBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("GitHub API error " + response.statusCode()
                        + " listing PRs: " + response.body());
            }
            JsonNode prs = objectMapper.readTree(response.body());
            if (prs.isArray() && !prs.isEmpty()) {
                return prs.get(0).get("html_url").asText();
            }
            throw new RuntimeException("PR already exists for " + owner + "/" + head
                    + " but was not found in the open PR list");
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to list PRs for " + owner, e);
        }
    }

    private HttpRequest.Builder requestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static String apiBaseUrl(String host) {
        return "github.com".equalsIgnoreCase(host)
                ? "https://api.github.com"
                : "https://" + host + "/api/v3";
    }

    static RepoCoords parseCoords(String url) {
        if (url.startsWith("git@")) {
            // git@host:owner/repo.git
            String rest = url.substring(4);
            int colon = rest.indexOf(':');
            String host = rest.substring(0, colon);
            String path = rest.substring(colon + 1);
            return ownerRepo(host, path);
        }
        URI uri = URI.create(url);
        return ownerRepo(uri.getHost(), uri.getPath());
    }

    private static RepoCoords ownerRepo(String host, String path) {
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
        String[] parts = path.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new RuntimeException("Cannot parse owner/repo from: " + path);
        }
        return new RepoCoords(host, parts[0], parts[1]);
    }

    record RepoCoords(String host, String owner, String repo) {}
}
