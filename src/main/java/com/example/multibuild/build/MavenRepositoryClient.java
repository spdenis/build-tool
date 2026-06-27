package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@EnableConfigurationProperties(LightspeedProperties.class)
public class MavenRepositoryClient {

    private static final Logger log = LoggerFactory.getLogger(MavenRepositoryClient.class);

    private final LightspeedProperties.MavenRepo mavenRepo;
    private final RestTemplate restTemplate;

    public MavenRepositoryClient(LightspeedProperties props, RestTemplate restTemplate) {
        this.mavenRepo = props.getMavenRepo();
        this.restTemplate = restTemplate;
    }

    public boolean isReleasesConfigured() {
        return !mavenRepo.getReleasesUrl().isBlank();
    }

    /** HEAD request to the release POM. Returns true if the artifact exists (HTTP 200). */
    public boolean isReleasePublished(Artifact a) {
        String url = releasePomUrl(a);
        try {
            ResponseEntity<Void> resp = restTemplate.exchange(
                    URI.create(url), HttpMethod.HEAD, new HttpEntity<>(mavenHeaders()), Void.class);
            if (resp.getStatusCode().value() == 200) return true;
            if (resp.getStatusCode().value() == 404) return false;
            log.warn("Unexpected status {} from {}", resp.getStatusCode().value(), url);
            return false;
        } catch (Exception e) {
            log.warn("Could not reach Maven repository at {}: {}", url, e.getMessage());
            return false;
        }
    }

    public String releasePomUrl(Artifact a) {
        return mavenRepo.getReleasesUrl().stripTrailing() + "/" +
                groupPath(a.getGroupId()) + "/" + a.getArtifactId() + "/" +
                a.getVersion() + "/" + a.getArtifactId() + "-" + a.getVersion() + ".pom";
    }

    /** GET request; returns the response body, or null on 404 or error. */
    public String fetchXml(String url) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(mavenHeaders()), String.class);
            if (resp.getStatusCode().value() == 404) return null;
            if (resp.getStatusCode().value() >= 300) {
                log.warn("Unexpected status {} from {}", resp.getStatusCode().value(), url);
                return null;
            }
            return resp.getBody();
        } catch (Exception e) {
            log.warn("Failed to fetch {}", url, e);
            return null;
        }
    }

    private HttpHeaders mavenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/xml");
        String user = mavenRepo.getUsername();
        if (!user.isBlank()) {
            String creds = Base64.getEncoder().encodeToString(
                    (user + ":" + mavenRepo.getPassword()).getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + creds);
        }
        return headers;
    }

    public static String groupPath(String groupId) {
        return groupId.replace('.', '/');
    }
}
