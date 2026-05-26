package com.example.multibuild.web;

import com.example.multibuild.app.Main;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.web.dto.BuildRequest;
import com.example.multibuild.web.dto.GraphResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("manual")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Main.class)
@ActiveProfiles("test")
class GraphControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void previewGraph_withValidRepos_returnsGraphResponse() {
        RepoConfig repoA = repoConfig("https://github.com/example/repo-a.git");
        RepoConfig repoB = repoConfig("https://github.com/example/repo-b.git");
        BuildRequest request = new BuildRequest(List.of(repoA, repoB), null, null, null, null, null, null);

        ResponseEntity<GraphResponse> response = restTemplate.postForEntity("/api/graph", request, GraphResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().layers()).isNotNull();
        assertThat(response.getBody().nodes()).hasSize(2);
    }

    @Test
    void previewGraph_withEmptyRepos_returnsEmptyGraph() {
        BuildRequest request = new BuildRequest(List.of(), null, null, null, null, null, null);

        ResponseEntity<GraphResponse> response = restTemplate.postForEntity("/api/graph", request, GraphResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().nodes()).isEmpty();
        assertThat(response.getBody().layers()).satisfiesAnyOf(
                layers -> assertThat(layers).isEmpty(),
                layers -> assertThat(layers).allMatch(List::isEmpty)
        );
    }

    private RepoConfig repoConfig(String url) {
        RepoConfig config = new RepoConfig();
        config.setUrl(url);
        return config;
    }
}
