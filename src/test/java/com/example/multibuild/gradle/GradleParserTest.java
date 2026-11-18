package com.example.multibuild.gradle;

import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class GradleParserTest {

    @TempDir
    Path repo;

    private final GradleParser parser = new GradleParser();

    @Test
    void singleProjectGroovy_readsCoordinatesFromGradleProperties() {
        write(repo.resolve("settings.gradle"), "rootProject.name = 'lib-a'\n");
        write(repo.resolve("gradle.properties"), "group=com.example\nversion=1.0.0-SNAPSHOT\nlibB=2.0.0\n");
        write(repo.resolve("build.gradle"), """
                plugins { id 'java-library' }
                dependencies {
                    api 'com.example:lib-b:$libB'
                    implementation "com.example:lib-c:${libB}"
                    testImplementation group: 'junit', name: 'junit', version: '4.13.2'
                    implementation project(':ignored')
                }
                """);

        RepositoryProject project = parser.parse(repo);

        assertThat(project.getModules()).hasSize(1);
        Module m = project.getModules().get(0);
        assertThat(m.getArtifact().toString()).isEqualTo("com.example:lib-a:1.0.0-SNAPSHOT");
        assertThat(deps(m)).containsExactly("com.example:lib-b:2.0.0", "com.example:lib-c:2.0.0", "junit:junit:4.13.2");
        assertThat(project.getAggregatorParents()).isEmpty();
    }

    @Test
    void singleProject_withoutSettings_usesDirectoryNameAndScriptCoordinates() {
        write(repo.resolve("build.gradle"), """
                plugins { id 'java' }
                group = 'org.acme'
                version = '3.1.0'
                """);

        Module m = parser.parse(repo).getModules().get(0);

        assertThat(m.getArtifact().toString()).isEqualTo("org.acme:" + repo.getFileName() + ":3.1.0");
    }

    @Test
    void multiProjectKotlin_subprojectsBlockAndArtifactIdOverride() {
        write(repo.resolve("settings.gradle.kts"), """
                rootProject.name = "platform"
                include(":core", ":api")
                include("tools:cli")
                """);
        write(repo.resolve("build.gradle.kts"), """
                allprojects {
                    group = "com.acme"
                    version = "2.5.0-SNAPSHOT"
                }
                subprojects {
                    apply(plugin = "java-library")
                    dependencies {
                        "implementation"("org.slf4j:slf4j-api:2.0.9")
                    }
                }
                """);
        write(repo.resolve("core/build.gradle.kts"), """
                val guavaVersion: String by project
                dependencies {
                    api("com.google.guava:guava:$guavaVersion")
                    api(platform("com.acme:bom:1.0.0"))
                }
                publishing { publications { create<MavenPublication>("maven") { artifactId = "acme-core" } } }
                """);
        write(repo.resolve("api/build.gradle.kts"), """
                dependencies {
                    implementation(group = "com.acme", name = "shared", version = "4.0.0")
                }
                """);
        write(repo.resolve("gradle.properties"), "guavaVersion=33.0.0-jre\n");

        RepositoryProject project = parser.parse(repo);

        assertThat(project.getModules()).extracting(m -> m.getArtifact().toString())
                .containsExactly("com.acme:acme-core:2.5.0-SNAPSHOT", "com.acme:api:2.5.0-SNAPSHOT",
                        "com.acme:tools:2.5.0-SNAPSHOT", "com.acme:cli:2.5.0-SNAPSHOT");
        Module core = project.getModules().get(0);
        assertThat(core.getDirectory()).isEqualTo(repo.resolve("core"));
        assertThat(deps(core)).contains("org.slf4j:slf4j-api:2.0.9", "com.google.guava:guava:33.0.0-jre", "com.acme:bom:1.0.0");
        assertThat(deps(project.getModules().get(1))).contains("com.acme:shared:4.0.0");
    }

    @Test
    void multiProject_rootWithoutPluginIsNotAModule() {
        write(repo.resolve("settings.gradle"), "rootProject.name = 'agg'\ninclude 'a', \n        'b'\n");
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        write(repo.resolve("build.gradle"), "allprojects { repositories { mavenCentral() } }\n");
        write(repo.resolve("a/build.gradle"), "plugins { id 'java-library' }\n");
        write(repo.resolve("b/build.gradle"), "plugins { id 'base' }\n");   // no artifact, no src/main

        assertThat(parser.parse(repo).getModules()).extracting(m -> m.getArtifact().getArtifactId())
                .containsExactly("a");
    }

    @Test
    void conventionPluginAndSrcMain_makeSubprojectsModules() {
        write(repo.resolve("settings.gradle"), "include 'conv'\ninclude 'plain'\n");
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        write(repo.resolve("conv/build.gradle"), "plugins { id 'acme.java-conventions' }\n");
        write(repo.resolve("plain/src/main/java/X.java"), "class X {}");

        assertThat(parser.parse(repo).getModules()).extracting(m -> m.getArtifact().getArtifactId())
                .containsExactly("conv", "plain");
    }

    @Test
    void commentsBuildscriptAndStringsAreIgnored() {
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        write(repo.resolve("build.gradle"), """
                buildscript {
                    dependencies { classpath 'com.acme:gradle-plugin:1.0' }
                }
                plugins { id 'java' }
                // dependencies { implementation 'com.acme:commented:1.0' }
                /* implementation 'com.acme:block-commented:1.0' */
                def note = "dependencies { implementation 'com.acme:in-string:1.0' }"
                dependencies {
                    implementation 'com.acme:real:1.0' // trailing { comment
                }
                """);

        assertThat(deps(parser.parse(repo).getModules().get(0))).containsExactly("com.acme:real:1.0");
    }

    @Test
    void projectDirOverride_isHonoured() {
        write(repo.resolve("settings.gradle"), """
                include 'core'
                project(':core').projectDir = file('modules/core-impl')
                """);
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        write(repo.resolve("modules/core-impl/build.gradle"), "plugins { id 'java-library' }\n");

        Module m = parser.parse(repo).getModules().get(0);
        assertThat(m.getArtifact().getArtifactId()).isEqualTo("core");
        assertThat(m.getDirectory()).isEqualTo(repo.resolve("modules/core-impl"));
    }

    @Test
    void extAndDefVersions_areResolved() {
        write(repo.resolve("gradle.properties"), "group=g\nversion=1\n");
        write(repo.resolve("build.gradle"), """
                plugins { id 'java' }
                ext { libX = '1.1' }
                ext.libY = '2.2'
                def libZ = '3.3'
                dependencies {
                    implementation "com.acme:x:${libX}"
                    implementation "com.acme:y:${project.libY}"
                    implementation "com.acme:z:$libZ"
                    implementation "com.acme:w:${property('libX')}"
                }
                """);

        assertThat(deps(parser.parse(repo).getModules().get(0)))
                .containsExactly("com.acme:x:1.1", "com.acme:y:2.2", "com.acme:z:3.3", "com.acme:w:1.1");
    }

    private static List<String> deps(Module m) {
        return m.getDependencies().stream().map(d -> d.getArtifact().toString()).toList();
    }
}
