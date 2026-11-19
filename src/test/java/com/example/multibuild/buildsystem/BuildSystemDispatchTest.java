package com.example.multibuild.buildsystem;

import com.example.multibuild.gradle.GradleDependencyUpdater;
import com.example.multibuild.gradle.GradleParser;
import com.example.multibuild.gradle.GradleVersionUpdater;
import com.example.multibuild.maven.DependencyVersionUpdater;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class BuildSystemDispatchTest {

    @TempDir
    Path tempDir;

    private Path gradleRepo() {
        Path repo = tempDir.resolve("gradle-b");
        write(repo.resolve("settings.gradle"), "rootProject.name = 'service-g'\n");
        write(repo.resolve("gradle.properties"), "group=com.example\nversion=5.0.0-SNAPSHOT\n");
        write(repo.resolve("build.gradle"), """
                plugins { id 'java-library' }
                dependencies { api 'com.example:lib-a:0.0.1' }
                """);
        return repo;
    }

    @Test
    void detect_prefersGradleAndRecognisesEveryRootFile() {
        Path maven = Fixtures.copy("repo-a", tempDir);
        assertThat(BuildSystem.detect(maven)).isEqualTo(BuildSystem.MAVEN);

        write(maven.resolve("settings.gradle.kts"), "");
        assertThat(BuildSystem.detect(maven)).isEqualTo(BuildSystem.GRADLE);

        for (String f : BuildSystem.GRADLE_ROOT_FILES) {
            Path dir = write(tempDir.resolve("only-" + f).resolve(f), "").getParent();
            assertThat(BuildSystem.detect(dir)).as(f).isEqualTo(BuildSystem.GRADLE);
        }
        assertThat(BuildSystem.detect(tempDir.resolve("only-build.gradle").getParent())).isEqualTo(BuildSystem.NONE);
        assertThat(BuildSystem.hasBuildFile(tempDir)).isFalse();
    }

    @Test
    void parser_routesEachRepoToItsBuildSystem() {
        MavenParserImpl maven = new MavenParserImpl();
        ReflectionTestUtils.setField(maven, "declaredModulesOnly", true);
        ProjectParserDispatcher parser = new ProjectParserDispatcher(maven, new GradleParser());

        assertThat(parser.parse(Fixtures.copy("repo-a", tempDir)).getModules().get(0).getArtifact().toString())
                .isEqualTo("com.example:lib-a:1.0.0-SNAPSHOT");
        assertThat(parser.parse(gradleRepo()).getModules().get(0).getArtifact().toString())
                .isEqualTo("com.example:service-g:5.0.0-SNAPSHOT");
        assertThat(parser.parse(write(tempDir.resolve("docs/README.md"), "").getParent()).getModules()).isEmpty();
    }

    @Test
    void versionUpdater_routesEachRepoToItsBuildSystem() {
        ProjectVersionUpdater updater = new ProjectVersionUpdater(new PomVersionUpdater(),
                new GradleVersionUpdater(new GradleParser()));
        Path maven = Fixtures.copy("repo-a", tempDir);
        Path gradle = gradleRepo();

        assertThat(updater.updateVersions(maven, "int")).isEqualTo("1.0.0-int-SNAPSHOT");
        assertThat(updater.updateVersions(gradle, "int")).isEqualTo("5.0.0-int-SNAPSHOT");
        assertThat(updater.getRootVersion(gradle)).isEqualTo("5.0.0-int-SNAPSHOT");
        assertThat(updater.updateVersionsBare(gradle)).isEqualTo("5.0.0-SNAPSHOT");
        updater.setVersions(maven, "1.0.0");
        assertThat(updater.getRootArtifact(maven).toString()).isEqualTo("com.example:lib-a:1.0.0");
        assertThat(updater.getRootArtifact(gradle).toString()).isEqualTo("com.example:service-g:5.0.0-SNAPSHOT");
    }

    @Test
    void dependencyUpdater_updatesMavenAndGradleReposInCallerOrder() {
        ProjectDependencyUpdater updater = new ProjectDependencyUpdater(new DependencyVersionUpdater(),
                new GradleDependencyUpdater());
        Path gradle = gradleRepo();
        Path maven = Fixtures.copy("repo-b", tempDir);

        assertThat(updater.update(List.of(gradle, maven), Map.of("com.example:lib-a", "1.2.3")))
                .containsExactly(gradle, maven);
        assertThat(read(gradle.resolve("build.gradle"))).contains("com.example:lib-a:1.2.3");
        assertThat(read(maven.resolve("pom.xml"))).contains("<version>1.2.3</version>");
    }

    // repo-b converted to Gradle with its pom kept (migrate.keep.maven=true)
    private Path migratingRepo() {
        Path repo = Fixtures.copy("repo-b", tempDir.resolve("migrating"));
        com.example.multibuild.gradle.MavenToGradleConverter converter = new com.example.multibuild.gradle.MavenToGradleConverter();
        converter.apply(converter.convert(repo, com.example.multibuild.gradle.GradleDsl.GROOVY).keepingMaven());
        return repo;
    }

    @Test
    void keptMavenBuild_gradleIsParsed_butVersionsAndDependenciesUpdateBoth() {
        Path repo = migratingRepo();
        assertThat(repo.resolve("pom.xml")).exists();
        assertThat(BuildSystem.detect(repo)).isEqualTo(BuildSystem.GRADLE);
        String pomVersion = new PomVersionUpdater().getRootVersion(repo);

        ProjectVersionUpdater versions = new ProjectVersionUpdater(new PomVersionUpdater(),
                new GradleVersionUpdater(new GradleParser()));
        String newVersion = versions.updateVersions(repo, "int");
        assertThat(newVersion).endsWith("-int-SNAPSHOT").isNotEqualTo(pomVersion);
        assertThat(new GradleVersionUpdater(new GradleParser()).getRootVersion(repo)).isEqualTo(newVersion);
        assertThat(new PomVersionUpdater().getRootVersion(repo)).isEqualTo(newVersion);

        versions.updateVersionsBare(repo);
        assertThat(new PomVersionUpdater().getRootVersion(repo)).isEqualTo(pomVersion);

        ProjectDependencyUpdater deps = new ProjectDependencyUpdater(new DependencyVersionUpdater(),
                new GradleDependencyUpdater());
        assertThat(deps.update(List.of(repo), Map.of("com.example:lib-a", "9.9.9"))).containsExactly(repo);
        assertThat(read(repo.resolve("build.gradle"))).contains("com.example:lib-a:9.9.9");
        assertThat(read(repo.resolve("pom.xml"))).contains("<version>9.9.9</version>");

        MavenParserImpl maven = new MavenParserImpl();
        ReflectionTestUtils.setField(maven, "declaredModulesOnly", true);
        // Gradle has priority: the dispatcher parses the Gradle build
        ProjectParserDispatcher parser = new ProjectParserDispatcher(maven, new GradleParser());
        assertThat(parser.parse(repo).getModules().get(0).getDirectory()).isEqualTo(repo);
        write(repo.resolve("build.gradle"), read(repo.resolve("build.gradle")).replace("9.9.9", "7.7.7"));
        assertThat(parser.parse(repo).getModules().get(0).getDependencies())
                .anyMatch(d -> d.getArtifact().toString().equals("com.example:lib-a:7.7.7"));
    }
}
