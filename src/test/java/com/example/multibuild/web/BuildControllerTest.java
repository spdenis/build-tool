package com.example.multibuild.web;

import com.example.multibuild.app.Main;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.session.BuildSession;
import com.example.multibuild.web.dto.BuildRequest;
import com.example.multibuild.web.dto.BuildStatusResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("manual")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Main.class)
@ActiveProfiles("test")
class BuildControllerTest {

    private static final Set<BuildSession.Status> ACTIVE_STATUSES =
            EnumSet.of(BuildSession.Status.PENDING, BuildSession.Status.RUNNING,
                    BuildSession.Status.SUCCESS, BuildSession.Status.FAILED,
                    BuildSession.Status.CANCELLED);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void startBuild_withRepos_returns202AndSessionId() {
        BuildRequest request = new BuildRequest(List.of(dummyRepo()), null, false, true, "test-branch", null, null);

        ResponseEntity<BuildStatusResponse> response = restTemplate.postForEntity(
                "/api/builds", request, BuildStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sessionId()).isNotBlank();
    }

    @Test
    void getStatus_afterStart_returnsSessionStatus() {
        BuildRequest request = new BuildRequest(List.of(dummyRepo()), null, false, true, "test-branch", null, null);
        ResponseEntity<BuildStatusResponse> startResponse = restTemplate.postForEntity(
                "/api/builds", request, BuildStatusResponse.class);
        assertThat(startResponse.getBody()).isNotNull();
        String sessionId = startResponse.getBody().sessionId();

        ResponseEntity<BuildStatusResponse> statusResponse = restTemplate.getForEntity(
                "/api/builds/" + sessionId, BuildStatusResponse.class);

        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().status()).isIn(ACTIVE_STATUSES);
    }

    @Test
    void listBuilds_afterOneStart_containsSession() {
        BuildRequest request = new BuildRequest(List.of(dummyRepo()), null, false, true, "test-branch", null, null);
        ResponseEntity<BuildStatusResponse> startResponse = restTemplate.postForEntity(
                "/api/builds", request, BuildStatusResponse.class);
        assertThat(startResponse.getBody()).isNotNull();
        String sessionId = startResponse.getBody().sessionId();

        ResponseEntity<BuildStatusResponse[]> listResponse = restTemplate.getForEntity(
                "/api/builds", BuildStatusResponse[].class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody())
                .extracting(BuildStatusResponse::sessionId)
                .contains(sessionId);
    }

    @Test
    void cancelBuild_runningSession_returns204() {
        BuildRequest request = new BuildRequest(List.of(dummyRepo()), null, false, true, "test-branch", null, null);
        ResponseEntity<BuildStatusResponse> startResponse = restTemplate.postForEntity(
                "/api/builds", request, BuildStatusResponse.class);
        assertThat(startResponse.getBody()).isNotNull();
        String sessionId = startResponse.getBody().sessionId();

        ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                "/api/builds/" + sessionId, HttpMethod.DELETE, null, Void.class);

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void getStatus_unknownId_returns404() {
        ResponseEntity<BuildStatusResponse> response = restTemplate.getForEntity(
                "/api/builds/non-existent-session-id", BuildStatusResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private RepoConfig dummyRepo() {
        RepoConfig config = new RepoConfig();
        config.setUrl("https://github.com/example/dummy-repo.git");
        return config;
    }
}
