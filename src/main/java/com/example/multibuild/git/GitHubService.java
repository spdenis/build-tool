package com.example.multibuild.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    @Value("${github.token:}")
    private String token;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public GitHubService(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * Creates a PR from {@code head} into {@code base} in the GitHub repo identified by {@code repoUrl}.
     * If a PR already exists for this head→base pair, returns its URL instead of failing.
     * Works with both github.com and GitHub Enterprise (inferred from the URL host).
     */
    public String createOrFindPr(String repoUrl, String head, String base, String title) {
        RepoCoords coords = parseCoords(repoUrl);
        String pullsEndpoint = apiBaseUrl(coords.host()) + "/repos/" + coords.owner() + "/" + coords.repo() + "/pulls";

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("title", title);
            body.put("head", head);
            body.put("base", base);
            body.put("body", "");

            log.info("POST {} (repo: {}/{})", pullsEndpoint, coords.owner(), coords.repo());
            log.debug("  auth={}", token.isBlank() ? "none" : "Bearer ***");

            HttpHeaders headers = githubHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(pullsEndpoint), HttpMethod.POST, entity, String.class);
            log.info("  -> HTTP {}", response.getStatusCode().value());

            if (response.getStatusCode().value() == 201) {
                return objectMapper.readTree(response.getBody()).get("html_url").asText();
            }
            if (response.getStatusCode().value() == 422) {
                // A PR for this head→base pair already exists.
                return findExistingPr(pullsEndpoint, coords.owner(), head, base);
            }
            logErrorResponse(response);
            throw new RuntimeException("GitHub API error " + response.getStatusCode().value()
                    + " creating PR for " + coords.owner() + "/" + coords.repo()
                    + ": " + response.getBody());
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to create PR for " + coords.owner() + "/" + coords.repo(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create PR for " + coords.owner() + "/" + coords.repo(), e);
        }
    }

    private String findExistingPr(String pullsEndpoint, String owner, String head, String base) {
        String url = pullsEndpoint + "?head=" + owner + ":" + head + "&base=" + base + "&state=open";
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(githubHeaders()), String.class);
            if (response.getStatusCode().value() != 200) {
                throw new RuntimeException("GitHub API error " + response.getStatusCode().value()
                        + " listing PRs: " + response.getBody());
            }
            JsonNode prs = objectMapper.readTree(response.getBody());
            if (prs.isArray() && !prs.isEmpty()) {
                return prs.get(0).get("html_url").asText();
            }
            throw new RuntimeException("PR already exists for " + owner + "/" + head
                    + " but was not found in the open PR list");
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to list PRs for " + owner, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list PRs for " + owner, e);
        }
    }

    private void logErrorResponse(ResponseEntity<String> response) {
        int status = response.getStatusCode().value();
        log.warn("  GitHub API error response (status={}):", status);
        response.getHeaders().forEach((name, values) ->
                log.warn("    {}: {}", name, String.join(", ", values)));
        if (status == 407) {
            String proxyAuth = response.getHeaders().getFirst("proxy-authenticate");
            log.warn("  407 Proxy Authentication Required — proxy-authenticate: {}",
                    proxyAuth != null ? proxyAuth : "(header absent)");
            log.warn("  Check git.proxy.host / git.proxy.port in user.properties");
        }
        String body = response.getBody();
        if (body != null && !body.isBlank()) {
            log.warn("  Body: {}", body);
        }
    }

    private HttpHeaders githubHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isBlank()) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
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
        java.net.URI uri = java.net.URI.create(url);
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
