package com.example.multibuild.build;

import com.example.multibuild.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@EnableConfigurationProperties(LightspeedProperties.class)
public class MavenRepositoryClient {

    private static final Logger log = LoggerFactory.getLogger(MavenRepositoryClient.class);

    private final LightspeedProperties.MavenRepo mavenRepo;
    private final HttpClient httpClient;

    public MavenRepositoryClient(LightspeedProperties props, HttpClient httpClient) {
        this.mavenRepo = props.getMavenRepo();
        this.httpClient = httpClient;
    }

    public boolean isReleasesConfigured() {
        return !mavenRepo.getReleasesUrl().isBlank();
    }

    /** HEAD request to the release POM. Returns true if the artifact exists (HTTP 200). */
    public boolean isReleasePublished(Artifact a) {
        String url = releasePomUrl(a);
        try {
            HttpResponse<Void> resp = httpClient.send(
                    requestBuilder(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 200) return true;
            if (resp.statusCode() == 404) return false;
            log.warn("Unexpected status {} from {}", resp.statusCode(), url);
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
            HttpResponse<String> resp = httpClient.send(
                    requestBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            if (resp.statusCode() >= 300) {
                log.warn("Unexpected status {} from {}", resp.statusCode(), url);
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("Failed to fetch {}", url, e);
            return null;
        }
    }

    public HttpRequest.Builder requestBuilder(String url) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/xml");
        String user = mavenRepo.getUsername();
        if (!user.isBlank()) {
            String creds = Base64.getEncoder().encodeToString(
                    (user + ":" + mavenRepo.getPassword()).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + creds);
        }
        return builder;
    }

    public static String groupPath(String groupId) {
        return groupId.replace('.', '/');
    }
}
