package com.example.multibuild.build;

import com.example.multibuild.gradle.GradleProperties;
import com.example.multibuild.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class LightspeedTriggerTest {

    @TempDir
    Path tempDir;

    @Test
    void gradleRepo_triggerGoesToGradleProperties() throws Exception {
        Path repo = write(tempDir.resolve("g/build.gradle"), "plugins { id 'java' }\n").getParent();
        write(repo.resolve("gradle.properties"), "# keep\ngroup=g\nversion=1.0.0-SNAPSHOT\n");

        assertThat(LightspeedBuildService.injectTrigger(repo, "2026-01-01T00:00:00Z")).isTrue();
        assertThat(LightspeedBuildService.injectTrigger(repo, "2026-01-02T00:00:00Z")).isTrue();

        assertThat(read(repo.resolve("gradle.properties")))
                .isEqualTo("# keep\ngroup=g\nversion=1.0.0-SNAPSHOT\nbuild.trigger.timestamp=2026-01-02T00:00:00Z\n");
        assertThat(read(repo.resolve("build.gradle"))).isEqualTo("plugins { id 'java' }\n");
    }

    @Test
    void gradleRepoWithoutProperties_createsFile() throws Exception {
        Path repo = write(tempDir.resolve("g/settings.gradle"), "").getParent();

        LightspeedBuildService.injectTrigger(repo, "t1");

        assertThat(GradleProperties.read(repo.resolve("gradle.properties")))
                .containsEntry("build.trigger.timestamp", "t1");
    }

    @Test
    void mavenRepo_triggerGoesToPomProperties() throws Exception {
        Path repo = Fixtures.copy("repo-a", tempDir);

        assertThat(LightspeedBuildService.injectTrigger(repo, "t1")).isTrue();

        assertThat(read(repo.resolve("pom.xml"))).contains("<build.trigger.timestamp>t1</build.trigger.timestamp>");
    }

    @Test
    void repoWithoutBuild_isNotTriggered() throws Exception {
        assertThat(LightspeedBuildService.injectTrigger(tempDir, "t1")).isFalse();
    }

    @Test
    void gradleRepoWithKeptPom_triggersBothBuilds() throws Exception {
        Path repo = Fixtures.copy("repo-a", tempDir);
        write(repo.resolve("settings.gradle"), "rootProject.name = 'lib-a'\n");

        LightspeedBuildService.injectTrigger(repo, "t2");

        assertThat(GradleProperties.read(repo.resolve("gradle.properties"))).containsEntry("build.trigger.timestamp", "t2");
        assertThat(read(repo.resolve("pom.xml"))).contains("t2");
    }
}
