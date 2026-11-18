package com.example.multibuild.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class GradleDependencyUpdaterTest {

    @TempDir
    Path tempDir;

    private final GradleDependencyUpdater updater = new GradleDependencyUpdater();

    @Test
    void literalVersions_areUpdatedInPlace() {
        Path repo = tempDir.resolve("r");
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        String script = """
                plugins { id 'java-library' }
                dependencies {
                    api 'com.example:lib-a:1.0.0-SNAPSHOT'
                    implementation("com.example:lib-b:1.0.0") { exclude group: 'x' }
                    api platform('com.example:bom:1.0.0')
                    constraints { api 'com.example:lib-c:0.1' }
                    implementation group: 'com.example', name: 'lib-d', version: '0.2'
                    implementation 'org.other:untouched:1.0.0-SNAPSHOT'
                }
                """;
        write(repo.resolve("build.gradle"), script);

        Set<Path> modified = updater.update(List.of(repo), Map.of(
                "com.example:lib-a", "1.0.0-int-SNAPSHOT",
                "com.example:lib-b", "1.1.0",
                "com.example:bom", "2.0.0",
                "com.example:lib-c", "0.2",
                "com.example:lib-d", "0.3"));

        assertThat(modified).containsExactly(repo);
        assertThat(read(repo.resolve("build.gradle"))).isEqualTo(script
                .replace("lib-a:1.0.0-SNAPSHOT", "lib-a:1.0.0-int-SNAPSHOT")
                .replace("lib-b:1.0.0", "lib-b:1.1.0")
                .replace("bom:1.0.0", "bom:2.0.0")
                .replace("lib-c:0.1", "lib-c:0.2")
                .replace("version: '0.2'", "version: '0.3'"));
    }

    @Test
    void gradlePropertyReference_updatesThePropertyOnce() {
        Path repo = tempDir.resolve("r");
        write(repo.resolve("settings.gradle"), "include 'a'\ninclude 'b'\n");
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\nlibAVersion=1.0.0\n");
        write(repo.resolve("a/build.gradle"), "plugins { id 'java' }\ndependencies { implementation \"com.example:lib-a:${libAVersion}\" }\n");
        write(repo.resolve("b/build.gradle.kts"), """
                plugins { java }
                val libAVersion: String by project
                dependencies { implementation("com.example:lib-a:$libAVersion") }
                """);

        assertThat(updater.update(List.of(repo), Map.of("com.example:lib-a", "2.0.0"))).containsExactly(repo);

        assertThat(GradleProperties.read(repo.resolve("gradle.properties"))).containsEntry("libAVersion", "2.0.0");
        assertThat(read(repo.resolve("a/build.gradle"))).contains("${libAVersion}");
    }

    @Test
    void scriptDefinitions_areUpdated() {
        Path repo = tempDir.resolve("r");
        write(repo.resolve("settings.gradle"), "include 'a'\n");
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        write(repo.resolve("build.gradle"), "ext { sharedVersion = '1.0' }\n");
        write(repo.resolve("a/build.gradle"), """
                plugins { id 'java' }
                def localVersion = '5.0'
                dependencies {
                    implementation "com.example:shared:${rootProject.ext.sharedVersion}"
                    implementation "com.example:local:$localVersion"
                }
                """);

        updater.update(List.of(repo), Map.of("com.example:shared", "1.1", "com.example:local", "5.1"));

        assertThat(read(repo.resolve("build.gradle"))).isEqualTo("ext { sharedVersion = '1.1' }\n");
        assertThat(read(repo.resolve("a/build.gradle"))).contains("def localVersion = '5.1'");
    }

    @Test
    void kotlinExtra_isUpdated() {
        Path repo = tempDir.resolve("r");
        write(repo.resolve("build.gradle.kts"), """
                plugins { java }
                group = "g"
                version = "1"
                val libVersion by extra("1.0")
                dependencies { implementation("com.example:lib:${property("libVersion")}") }
                """);

        updater.update(List.of(repo), Map.of("com.example:lib", "1.5"));

        assertThat(read(repo.resolve("build.gradle.kts"))).contains("val libVersion by extra(\"1.5\")");
    }

    @Test
    void noMatchingOrUnchanged_returnsEmptyAndLeavesFilesAlone() {
        Path repo = tempDir.resolve("r");
        String script = """
                buildscript { dependencies { classpath 'com.example:lib-a:0.1' } }
                plugins { id 'java' }
                // implementation 'com.example:lib-a:0.1'
                dependencies { implementation 'com.example:lib-a:1.0' }
                """;
        write(repo.resolve("build.gradle"), script);

        assertThat(updater.update(List.of(repo), Map.of("com.example:lib-a", "1.0"))).isEmpty();
        assertThat(updater.update(List.of(repo), Map.of("com.example:other", "9"))).isEmpty();
        assertThat(read(repo.resolve("build.gradle"))).isEqualTo(script);

        updater.update(List.of(repo), Map.of("com.example:lib-a", "2.0"));
        assertThat(read(repo.resolve("build.gradle")))
                .contains("classpath 'com.example:lib-a:0.1'", "// implementation 'com.example:lib-a:0.1'",
                        "implementation 'com.example:lib-a:2.0'");
    }

    @Test
    void projectVersionReference_isNotRewritten() {
        Path repo = tempDir.resolve("r");
        write(repo.resolve("gradle.properties"), "group=g\nversion=1.0\n");
        String script = "plugins { id 'java' }\ndependencies { implementation \"com.example:lib:${version}\" }\n";
        write(repo.resolve("build.gradle"), script);

        assertThat(updater.update(List.of(repo), Map.of("com.example:lib", "2.0"))).isEmpty();
        assertThat(GradleProperties.read(repo.resolve("gradle.properties"))).containsEntry("version", "1.0");
    }
}
