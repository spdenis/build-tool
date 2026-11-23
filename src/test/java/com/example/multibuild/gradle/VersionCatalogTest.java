package com.example.multibuild.gradle;

import com.example.multibuild.model.Dependency;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

// gradle/libs.versions.toml: parsing, alias generation and edits, plus how the parser and the dependency
// updater use it together with convention plugins in build-logic/.
class VersionCatalogTest {

    @TempDir
    Path repo;

    private static final String TOML = """
            [versions]
            spring = "6.1.5"   # shared
            lib-a = "1.0.0"

            [libraries]
            spring-context = { module = "org.springframework:spring-context", version.ref = "spring" }
            spring-test = { module = "org.springframework:spring-test", version.ref = "spring" }
            guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
            jackson-bom = "com.fasterxml.jackson:jackson-bom:2.17.0"
            lib-a = { module = "com.example:lib-a", version.ref = "lib-a" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
            strict = { module = "org.x:strict", version = { strictly = "1.2" } }

            [plugins]
            boot = { id = "org.springframework.boot", version = "3.2.0" }
            """;

    @Test
    void parsesEveryLibraryNotation() {
        VersionCatalog c = VersionCatalog.parse(TOML);
        assertThat(c.versions).containsKeys("spring", "lib-a");
        assertThat(c.versionOf(c.libraries.get("spring-context"))).isEqualTo("6.1.5");
        assertThat(c.versionOf(c.libraries.get("guava"))).isEqualTo("33.0.0-jre");
        assertThat(c.versionOf(c.libraries.get("jackson-bom"))).isEqualTo("2.17.0");
        assertThat(c.versionOf(c.libraries.get("junit-jupiter"))).isNull();
        assertThat(c.versionOf(c.libraries.get("strict"))).isEqualTo("1.2");
        assertThat(c.libraries).doesNotContainKey("boot");
        // accessors: '-', '_' and '.' are the same separator
        assertThat(c.byAccessor("spring.context").key()).isEqualTo("org.springframework:spring-context");
        assertThat(c.byAccessor("jackson_bom").key()).isEqualTo("com.fasterxml.jackson:jackson-bom");
    }

    @Test
    void withVersion_rewritesLiteralOrSharedVersionOnce_andLeavesRichVersions() {
        VersionCatalog c = VersionCatalog.parse(TOML);
        String text = c.withVersion("org.springframework:spring-context", "6.2.0");
        assertThat(text).contains("spring = \"6.2.0\"   # shared").contains("version.ref = \"spring\"");
        assertThat(VersionCatalog.parse(text).versionOf(VersionCatalog.parse(text).libraries.get("spring-test"))).isEqualTo("6.2.0");
        text = VersionCatalog.parse(text).withVersion("com.fasterxml.jackson:jackson-bom", "2.18.1");
        text = VersionCatalog.parse(text).withVersion("com.google.guava:guava", "33.3.0-jre");
        text = VersionCatalog.parse(text).withVersion("org.x:strict", "9");
        assertThat(text).contains("\"com.fasterxml.jackson:jackson-bom:2.18.1\"", "version = \"33.3.0-jre\"",
                "strictly = \"1.2\"");
    }

    @Test
    void builder_generatesValidUniqueAliases() {
        VersionCatalog.Builder b = new VersionCatalog.Builder();
        assertThat(b.library("org.springframework", "spring-context", null, "spring")).isEqualTo("spring-context");
        assertThat(b.library("org.springframework", "spring-context", null, "spring")).isEqualTo("spring-context");
        assertThat(b.library("com.acme", "spring-context", "1", null)).isEqualTo("acme-spring-context");
        assertThat(b.library("org.apache.logging.log4j", "log4j-1.2-api", "2.0", null)).isEqualTo("log4j12-api");
        assertThat(b.library("io.x", "versions-core", "1", null)).isEqualTo("lib-versions-core");
        assertThat(b.library("com.fasterxml.jackson.core", "jackson", "1", null)).isEqualTo("jackson");
        assertThat(b.library("com.fasterxml.jackson.core", "jackson-databind", "1", null)).isEqualTo("jackson-databind");
        // an alias that prefixes another one is reached through asProvider()
        assertThat(b.accessor("jackson")).isEqualTo("libs.jackson.asProvider()");
        assertThat(b.accessor("jackson-databind")).isEqualTo("libs.jackson.databind");
        assertThat(b.version("spring", "6.1.5")).isEqualTo("spring");
        assertThat(b.version("spring", "5.3.0")).isEqualTo("spring-2");
    }

    private void conventionBuild() {
        write(repo.resolve("settings.gradle.kts"), """
                pluginManagement { includeBuild("build-logic") }
                rootProject.name = "shop"
                include("core")
                include("app")
                """);
        write(repo.resolve("gradle.properties"), "group=com.acme\nversion=2.0.0\n");
        write(repo.resolve("gradle/libs.versions.toml"), TOML);
        write(repo.resolve("build.gradle.kts"), "// configured by build-logic\n");
        write(repo.resolve("build-logic/src/main/kotlin/acme.java-conventions.gradle.kts"), """
                plugins { id("java-library") }
                val libs = the<VersionCatalogsExtension>().named("libs")
                dependencies {
                    "testImplementation"(libs.findLibrary("spring-test").get())
                    "api"(platform("com.fasterxml.jackson:jackson-bom:2.17.0"))
                }
                """);
        write(repo.resolve("build-logic/src/main/kotlin/acme.service-conventions.gradle.kts"), """
                plugins { id("acme.java-conventions") }
                val libs = the<VersionCatalogsExtension>().named("libs")
                dependencies { "api"(libs.findLibrary("lib-a").get()) }
                """);
        write(repo.resolve("core/build.gradle.kts"), """
                plugins { id("acme.java-conventions") }
                dependencies {
                    api(libs.spring.context)
                    implementation(libs.guava)
                    // api(libs.junit.jupiter) — a comment, not a dependency
                }
                """);
        write(repo.resolve("app/build.gradle.kts"), """
                plugins { id("acme.service-conventions") }
                dependencies { api(project(":core")) }
                """);
    }

    @Test
    void parser_resolvesCatalogReferences_andDependenciesOfAppliedConventionPlugins() {
        conventionBuild();
        RepositoryProject project = new GradleParser().parse(repo);

        Map<String, List<String>> deps = new java.util.TreeMap<>();
        for (Module m : project.getModules()) {
            deps.put(m.getArtifact().getArtifactId(), m.getDependencies().stream().map(Dependency::getArtifact)
                    .map(Object::toString).sorted().toList());
        }
        assertThat(deps.keySet()).containsExactly("app", "core");
        assertThat(deps.get("core")).containsExactly("com.fasterxml.jackson:jackson-bom:2.17.0",
                "com.google.guava:guava:33.0.0-jre", "org.springframework:spring-context:6.1.5",
                "org.springframework:spring-test:6.1.5");
        // app applies service-conventions, which applies java-conventions
        assertThat(deps.get("app")).contains("com.example:lib-a:1.0.0", "org.springframework:spring-test:6.1.5",
                "com.fasterxml.jackson:jackson-bom:2.17.0");
    }

    @Test
    void dependencyUpdater_editsTheCatalog_andLiteralVersionsInConventionPlugins() {
        conventionBuild();
        boolean changed = !new GradleDependencyUpdater().update(List.of(repo), Map.of(
                "com.example:lib-a", "1.1.0",
                "org.springframework:spring-test", "6.2.0",
                "com.fasterxml.jackson:jackson-bom", "2.18.0")).isEmpty();

        assertThat(changed).isTrue();
        String toml = read(repo.resolve("gradle/libs.versions.toml"));
        assertThat(toml).contains("lib-a = \"1.1.0\"", "spring = \"6.2.0\"",
                "\"com.fasterxml.jackson:jackson-bom:2.18.0\"");
        assertThat(read(repo.resolve("build-logic/src/main/kotlin/acme.java-conventions.gradle.kts")))
                .contains("platform(\"com.fasterxml.jackson:jackson-bom:2.18.0\")");
        // spring-context shares the [versions] entry, as in Maven with a shared property
        assertThat(new GradleParser().parse(repo).getModules().stream()
                .flatMap(m -> m.getDependencies().stream()).map(d -> d.getArtifact().toString()))
                .contains("org.springframework:spring-context:6.2.0");
    }
}
