package com.example.multibuild.build;

import com.example.multibuild.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class LocalBuildServiceTest {

    @TempDir
    Path tempDir;

    private LocalBuildService service(String gradleCommand) {
        LocalBuildService s = new LocalBuildService();
        ReflectionTestUtils.setField(s, "goals", "clean install");
        ReflectionTestUtils.setField(s, "gradleTasks", "clean build publishToMavenLocal");
        ReflectionTestUtils.setField(s, "gradleCommand", gradleCommand);
        return s;
    }

    @Test
    void mavenRepo_runsMavenGoals() {
        Path repo = Fixtures.copy("repo-a", tempDir);
        assertThat(service("").command(repo)).containsExactly("mvn", "clean", "install");
    }

    @Test
    void gradleRepoWithWrapper_runsWrapper() {
        Path repo = write(tempDir.resolve("g/build.gradle"), "").getParent();
        write(repo.resolve("gradlew"), "#!/bin/sh");

        assertThat(service("/opt/gradle/bin/gradle").command(repo)).containsExactly(
                repo.resolve("gradlew").toAbsolutePath().toString(), "clean", "build", "publishToMavenLocal");
    }

    @Test
    void gradleRepoWithoutWrapper_usesConfiguredOrPathGradle() {
        Path repo = write(tempDir.resolve("g/settings.gradle.kts"), "").getParent();

        assertThat(service("").command(repo)).containsExactly("gradle", "clean", "build", "publishToMavenLocal");
        assertThat(service("/opt/gradle/bin/gradle").command(repo).get(0)).isEqualTo("/opt/gradle/bin/gradle");
    }
}
