package com.example.multibuild.service;

import com.example.multibuild.build.LightspeedProperties;
import com.example.multibuild.build.MavenRepositoryClient;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchVersionResolverTest {

    @TempDir
    Path repoDir;

    @Test
    void previousRelease_decrementsLastSegment() {
        assertThat(PatchVersionResolver.previousRelease("1.1.1")).isEqualTo("1.1.0");
        assertThat(PatchVersionResolver.previousRelease("2.0.10")).isEqualTo("2.0.9");
    }

    @Test
    void previousRelease_failsWhenLastSegmentIsZero() {
        assertThatThrownBy(() -> PatchVersionResolver.previousRelease("1.2.0"))
                .hasMessageContaining("last segment is 0");
    }

    @Test
    void resolve_firstPatchWhenNothingReleased() throws IOException {
        writePom("1.1.1-SNAPSHOT");
        assertThat(resolver(Set.of()).resolve(repoDir)).isEqualTo("1.1.0.1");
    }

    @Test
    void resolve_skipsAlreadyReleasedPatches() throws IOException {
        writePom("1.1.1-SNAPSHOT");
        assertThat(resolver(Set.of("1.1.0.1", "1.1.0.2")).resolve(repoDir)).isEqualTo("1.1.0.3");
    }

    @Test
    void resolve_keepsExistingPatchVersion() throws IOException {
        writePom("1.1.0.2-feature-SNAPSHOT");
        assertThat(resolver(Set.of("1.1.0.2")).resolve(repoDir)).isEqualTo("1.1.0.2");
    }

    @Test
    void resolve_failsWithoutReleasesUrl() throws IOException {
        writePom("1.1.1-SNAPSHOT");
        PatchVersionResolver resolver = new PatchVersionResolver(new PomVersionUpdater(),
                new MavenRepositoryClient(new LightspeedProperties(), new RestTemplate()));
        assertThatThrownBy(() -> resolver.resolve(repoDir)).hasMessageContaining("releases-url");
    }

    private PatchVersionResolver resolver(Set<String> releasedVersions) {
        MavenRepositoryClient client = new MavenRepositoryClient(new LightspeedProperties(), new RestTemplate()) {
            @Override public boolean isReleasesConfigured() { return true; }
            @Override public boolean releaseExists(Artifact a) {
                assertThat(a.key()).isEqualTo("com.example:lib");
                return releasedVersions.contains(a.getVersion());
            }
        };
        return new PatchVersionResolver(new PomVersionUpdater(), client);
    }

    private void writePom(String version) throws IOException {
        Files.writeString(repoDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version));
    }
}
