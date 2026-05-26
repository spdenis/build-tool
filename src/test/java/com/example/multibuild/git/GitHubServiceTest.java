package com.example.multibuild.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubServiceTest {

    @Test
    void parseCoords_httpsGithubCom() {
        var coords = GitHubService.parseCoords("https://github.com/acme/my-repo.git");
        assertThat(coords.host()).isEqualTo("github.com");
        assertThat(coords.owner()).isEqualTo("acme");
        assertThat(coords.repo()).isEqualTo("my-repo");
    }

    @Test
    void parseCoords_httpsWithoutDotGit() {
        var coords = GitHubService.parseCoords("https://github.com/acme/my-repo");
        assertThat(coords.owner()).isEqualTo("acme");
        assertThat(coords.repo()).isEqualTo("my-repo");
    }

    @Test
    void parseCoords_sshGithubCom() {
        var coords = GitHubService.parseCoords("git@github.com:acme/my-repo.git");
        assertThat(coords.host()).isEqualTo("github.com");
        assertThat(coords.owner()).isEqualTo("acme");
        assertThat(coords.repo()).isEqualTo("my-repo");
    }

    @Test
    void parseCoords_githubEnterprise() {
        var coords = GitHubService.parseCoords("https://github.example.com/org/service.git");
        assertThat(coords.host()).isEqualTo("github.example.com");
        assertThat(coords.owner()).isEqualTo("org");
        assertThat(coords.repo()).isEqualTo("service");
    }

    @Test
    void parseCoords_sshEnterprise() {
        var coords = GitHubService.parseCoords("git@github.example.com:org/service.git");
        assertThat(coords.host()).isEqualTo("github.example.com");
        assertThat(coords.owner()).isEqualTo("org");
        assertThat(coords.repo()).isEqualTo("service");
    }

    @Test
    void parseCoords_invalidPath_throws() {
        assertThatThrownBy(() -> GitHubService.parseCoords("https://github.com/only-owner"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("owner/repo");
    }
}
