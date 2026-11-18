package com.example.multibuild.gradle;

import com.example.multibuild.model.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class GradleVersionUpdaterTest {

    @TempDir
    Path repo;

    private final GradleVersionUpdater updater = new GradleVersionUpdater(new GradleParser());

    private void multiProject() {
        write(repo.resolve("settings.gradle"), "rootProject.name = 'agg'\ninclude 'core'\ninclude 'web'\n");
        write(repo.resolve("gradle.properties"), "# comment kept\ngroup=com.acme\nversion = 1.4.2-SNAPSHOT\nother=x\n");
        write(repo.resolve("build.gradle"), "allprojects { repositories { mavenCentral() } }\n");
        write(repo.resolve("core/build.gradle"), "plugins { id 'java-library' }\n");
        write(repo.resolve("web/build.gradle"), """
                plugins { id 'java-library' }
                version = '1.4.2-SNAPSHOT'
                dependencies {
                    implementation(group: 'x', name: 'y', version: '1.4.2-SNAPSHOT')
                }
                """);
    }

    @Test
    void rootVersion_comesFromGradleProperties() {
        multiProject();
        assertThat(updater.getRootVersion(repo)).isEqualTo("1.4.2-SNAPSHOT");
    }

    @Test
    void rootVersion_fallsBackToBuildScript() {
        write(repo.resolve("build.gradle.kts"), "plugins { java }\ngroup = \"g\"\nversion = \"0.9.0\"\n");
        assertThat(updater.getRootVersion(repo)).isEqualTo("0.9.0");
    }

    @Test
    void updateVersions_appendsBranchAndRewritesEveryDeclarationOfTheRootVersion() {
        multiProject();

        String newVersion = updater.updateVersions(repo, "feature-x");

        assertThat(newVersion).isEqualTo("1.4.2-feature-x-SNAPSHOT");
        assertThat(read(repo.resolve("gradle.properties")))
                .isEqualTo("# comment kept\ngroup=com.acme\nversion = 1.4.2-feature-x-SNAPSHOT\nother=x\n");
        assertThat(read(repo.resolve("web/build.gradle")))
                .contains("version = '1.4.2-feature-x-SNAPSHOT'")
                // dependency versions are not project versions
                .contains("version: '1.4.2-SNAPSHOT'");
        assertThat(new GradleParser().parse(repo).getModules())
                .allMatch(m -> m.getArtifact().getVersion().equals("1.4.2-feature-x-SNAPSHOT"));
    }

    @Test
    void updateVersionsBare_dropsQualifier() {
        multiProject();
        updater.setVersions(repo, "1.4.2-feature-x-SNAPSHOT");

        assertThat(updater.updateVersionsBare(repo)).isEqualTo("1.4.2-SNAPSHOT");
        assertThat(updater.getRootVersion(repo)).isEqualTo("1.4.2-SNAPSHOT");
    }

    @Test
    void setVersions_kotlinScriptVersion() {
        write(repo.resolve("build.gradle.kts"), """
                plugins { `java-library` }
                group = "g"
                version = "2.0.0-SNAPSHOT"
                """);

        updater.setVersions(repo, "2.0.0");

        assertThat(read(repo.resolve("build.gradle.kts"))).contains("version = \"2.0.0\"\n");
        assertThat(updater.getRootVersion(repo)).isEqualTo("2.0.0");
    }

    @Test
    void rootArtifact_isFirstPublishedModule() {
        multiProject();
        Artifact root = updater.getRootArtifact(repo);
        assertThat(root).isEqualTo(new Artifact("com.acme", "core", "1.4.2-SNAPSHOT"));
    }

    @Test
    void noVersion_returnsNullAndSetIsNoOp() {
        write(repo.resolve("build.gradle"), "plugins { id 'java' }\n");
        assertThat(updater.getRootVersion(repo)).isNull();
        updater.setVersions(repo, "1.0");
        assertThat(read(repo.resolve("build.gradle"))).isEqualTo("plugins { id 'java' }\n");
    }
}
