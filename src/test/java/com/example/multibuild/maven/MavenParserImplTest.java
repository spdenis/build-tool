package com.example.multibuild.maven;

import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class MavenParserImplTest {

    private final MavenParserImpl parser = parser();

    @Test
    void parse_propertyVersionInSamePom_resolvesVersion() {
        RepositoryProject project = parser.parse(fixture("repo-b-property"));

        String version = depVersion(project, "lib-a");
        assertThat(version).isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    void parse_managedVersionInSamePom_resolvesVersion() {
        RepositoryProject project = parser.parse(fixture("repo-b-depmanagement"));

        String version = depVersion(project, "lib-a");
        assertThat(version).isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    void parse_propertyInParentAggregatorPom_resolvesVersionInChildModule() {
        RepositoryProject project = parser.parse(fixture("repo-multi-with-props"));

        String version = depVersion(project, "lib-a");
        assertThat(version)
                .as("${lib-a.version} defined in parent aggregator must be resolved")
                .isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    void parse_managedVersionInParentAggregatorPom_resolvesVersionInChildModule() {
        RepositoryProject project = parser.parse(fixture("repo-multi-with-props"));

        String version = depVersion(project, "lib-c");
        assertThat(version)
                .as("version managed in parent aggregator's <dependencyManagement> must be resolved")
                .isEqualTo("1.0.0-SNAPSHOT");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String depVersion(RepositoryProject project, String artifactId) {
        return project.getModules().stream()
                .flatMap(m -> m.getDependencies().stream())
                .filter(d -> artifactId.equals(d.getArtifact().getArtifactId()))
                .map(d -> d.getArtifact().getVersion())
                .findFirst()
                .orElseThrow(() -> new AssertionError("dependency " + artifactId + " not found"));
    }

    private static Path fixture(String name) {
        try {
            URI uri = MavenParserImplTest.class.getClassLoader().getResource("repos/" + name).toURI();
            return Paths.get(uri);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MavenParserImpl parser() {
        MavenParserImpl p = new MavenParserImpl();
        ReflectionTestUtils.setField(p, "declaredModulesOnly", true);
        return p;
    }
}
