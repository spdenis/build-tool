package com.example.multibuild.gradle;

import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

// A <parent> pom in another repository of the workspace: the parent repo publishes its build-logic and the
// child repo applies the parent's convention plugin instead of generating conventions of its own.
class CrossRepoParentTest {

    @TempDir
    Path tempDir;

    private Path parent;
    private Path child;
    private final MavenToGradleConverter converter = new MavenToGradleConverter();

    @BeforeEach
    void setUp() {
        parent = tempDir.resolve("ws/parent");
        child = tempDir.resolve("ws/repository1");
        ReflectionTestUtils.setField(converter, "localRepository", tempDir.resolve("m2").toString());
        write(parent.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.dummy.parent</groupId><artifactId>parent</artifactId><version>1.0.1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module></modules>
                  <properties><java.version>17</java.version><commons.version>3.14.0</commons.version></properties>
                  <dependencies>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>${commons.version}</version></dependency>
                  </dependencies>
                  <distributionManagement>
                    <repository><id>releases</id><url>https://repo.example.com/releases</url></repository>
                    <snapshotRepository><id>snapshots</id><url>https://repo.example.com/snapshots</url></snapshotRepository>
                  </distributionManagement>
                </project>""");
        write(parent.resolve("bom/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.dummy.parent</groupId><artifactId>parent</artifactId><version>1.0.1-SNAPSHOT</version></parent>
                  <artifactId>bom</artifactId><packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.2.1-jre</version></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.46</version></dependency>
                  </dependencies></dependencyManagement>
                </project>""");
        write(child.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.dummy.parent</groupId><artifactId>bom</artifactId><version>1.0.1-SNAPSHOT</version></parent>
                  <groupId>com.dummy.module1</groupId><artifactId>main1</artifactId><version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>artifact1</module></modules>
                </project>""");
        write(child.resolve("artifact1/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.dummy.module1</groupId><artifactId>main1</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>artifact1</artifactId>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.46</version><scope>provided</scope></dependency>
                  </dependencies>
                </project>""");
        write(child.resolve("artifact1/src/main/java/com/dummy/Item.java"), """
                package com.dummy;
                @lombok.Value public class Item {
                    String name;
                    public String upper() { return org.apache.commons.lang3.StringUtils.upperCase(name) + com.google.common.base.Strings.repeat("!", 1); }
                }""");
    }

    private MavenToGradleConverter.Conversion migrate(Path repo, GradleDsl dsl) throws IOException {
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        RepositoryProject maven = mavenParser.parse(repo);
        MavenToGradleConverter.Conversion c = converter.convert(repo, dsl, GradleMigrationService.workspace(repo));
        converter.apply(c);
        assertThat(GradleMigrationService.verify(maven, new GradleParser().parse(repo), c)).isEmpty();
        return c;
    }

    // -DbuildLogicDump=<dir>: copy the workspace there, to build it with a real Gradle.
    private void dump(String name) throws IOException {
        String dump = System.getProperty("buildLogicDump");
        if (dump == null) return;
        Path target = Path.of(dump).resolve(name);
        FileSystemUtils.deleteRecursively(target);
        FileSystemUtils.copyRecursively(tempDir.resolve("ws"), target);
    }

    @Test
    void parentRepo_publishesItsBuildLogic_childAppliesTheParentConvention_kotlin() throws IOException {
        // the child first: the result does not depend on the migration order
        MavenToGradleConverter.Conversion childConversion = migrate(child, GradleDsl.KOTLIN);
        migrate(parent, GradleDsl.KOTLIN);
        dump("cross-repo-kotlin");

        // parent: a convention for the pom the child inherits, published with the modules
        String bomConvention = read(parent.resolve("build-logic/src/main/kotlin/parent.bom-conventions.gradle.kts"));
        // what bom passes on, including what it inherits from the root pom; literal coordinates: the child's
        // catalog does not have the parent's aliases
        assertThat(bomConvention).contains("// maven-parent: com.dummy.parent:bom","id(\"parent.java-conventions\")",
                "\"api\"(\"org.apache.commons:commons-lang3:3.14.0\")", "sourceCompatibility = JavaVersion.toVersion(\"17\")")
                .doesNotContain("findLibrary", "VersionCatalogsExtension");
        assertThat(read(parent.resolve("build-logic/build.gradle.kts"))).contains("`maven-publish`",
                "group = repoProperties.getProperty(\"group\")", "version = repoProperties.getProperty(\"version\")",
                "https://repo.example.com/snapshots");
        assertThat(read(parent.resolve("build-logic/settings.gradle.kts"))).contains("rootProject.name = \"parent-build-logic\"");
        assertThat(read(parent.resolve("build.gradle.kts")))
                .contains("tasks.maybeCreate(name).dependsOn(gradle.includedBuild(\"build-logic\").task(\":$name\"))");

        // child: builds on the parent's conventions, no base / publishing conventions of its own
        Path conventions = child.resolve("build-logic/src/main/kotlin");
        try (var files = Files.list(conventions)) {
            assertThat(files.map(f -> f.getFileName().toString()).sorted().toList())
                    .containsExactly("module1.lombok-conventions.gradle.kts", "module1.main1-conventions.gradle.kts");
        }
        assertThat(read(conventions.resolve("module1.main1-conventions.gradle.kts")))
                .contains("apply(plugin = \"parent.bom-conventions\")", "\"api\"(platform(libs.findLibrary(\"bom\").get()))");
        assertThat(read(child.resolve("artifact1/build.gradle.kts")))
                .contains("id(\"module1.main1-conventions\")", "id(\"module1.lombok-conventions\")", "api(libs.guava)");
        assertThat(read(child.resolve("build-logic/build.gradle.kts")))
                .contains("implementation(libs.parent.bom.conventions.gradle.plugin)");
        assertThat(read(child.resolve("build-logic/settings.gradle.kts"))).contains("mavenLocal()");
        // the parent version is one [versions] entry: bumping the parent also bumps its build-logic
        assertThat(read(child.resolve("gradle/libs.versions.toml"))).contains("bom = \"1.0.1-SNAPSHOT\"",
                "bom = { module = \"com.dummy.parent:bom\", version.ref = \"bom\" }",
                "module = \"parent.bom-conventions:parent.bom-conventions.gradle.plugin\", version.ref = \"bom\"");
        assertThat(childConversion.warnings()).noneMatch(w -> w.contains("is also published"));

        new GradleDependencyUpdater().update(List.of(child), java.util.Map.of("com.dummy.parent:bom", "1.0.2-SNAPSHOT"));
        assertThat(read(child.resolve("gradle/libs.versions.toml"))).contains("bom = \"1.0.2-SNAPSHOT\"");
    }

    @Test
    void parentRepo_publishesItsBuildLogic_groovy() throws IOException {
        migrate(parent, GradleDsl.GROOVY);
        migrate(child, GradleDsl.GROOVY);
        dump("cross-repo-groovy");

        assertThat(read(parent.resolve("build-logic/build.gradle"))).contains("id 'maven-publish'",
                "file('../gradle.properties').withInputStream { repoProperties.load(it) }");
        assertThat(read(parent.resolve("build.gradle"))).contains("['publish', 'publishToMavenLocal'].each { name ->");
        assertThat(read(child.resolve("build-logic/src/main/groovy/module1.main1-conventions.gradle")))
                .contains("id 'parent.bom-conventions'");
        assertThat(child.resolve("build-logic/src/main/groovy/module1.java-conventions.gradle")).doesNotExist();
    }

    // Incremental migration: the root repo was migrated earlier (nobody inherited it yet, so its build-logic is
    // not published) and edited by hand; a repo inheriting its poms is migrated now.
    @Test
    void rootMigratedEarlierAndEditedByHand_childImportsItsCatalogAndConventions() throws IOException {
        converter.apply(converter.convert(parent, GradleDsl.KOTLIN, List.of()));
        Path javaConvention = parent.resolve("build-logic/src/main/kotlin/parent.java-conventions.gradle.kts");
        assertThat(javaConvention).exists();
        assertThat(read(parent.resolve("build-logic/build.gradle.kts"))).doesNotContain("maven-publish");
        // hand edits: a library of the root catalog for every Java module, and a new catalog entry
        write(javaConvention, read(javaConvention) + """

                val libs = the<VersionCatalogsExtension>().named("libs")

                dependencies {
                    "implementation"(libs.findLibrary("commons-lang3").get())
                }
                """);
        Path rootCatalog = parent.resolve("gradle/libs.versions.toml");
        write(rootCatalog, read(rootCatalog) + "slf4j-api = { module = \"org.slf4j:slf4j-api\", version = \"2.0.13\" }\n");
        write(child.resolve("artifact1/pom.xml"), read(child.resolve("artifact1/pom.xml")).replace("</dependencies>",
                "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version></dependency>"
                        + "<dependency><groupId>com.dummy.other</groupId><artifactId>shared</artifactId><version>3.1.0</version></dependency>"
                        + "</dependencies>"));
        write(child.resolve("artifact1/src/main/java/com/dummy/Item.java"), read(child.resolve("artifact1/src/main/java/com/dummy/Item.java"))
                .replace("String upper()", "org.slf4j.Logger log() { return org.slf4j.LoggerFactory.getLogger(Item.class); }\n    public String upper()"));

        MavenToGradleConverter.Conversion c = migrate(child, GradleDsl.KOTLIN);
        // the convention standing for bom (a platform): the root's base Java convention
        assertThat(c.imports()).singleElement().satisfies(imp -> {
            assertThat(imp.pluginId()).isEqualTo("parent.java-conventions");
            assertThat(imp.onDisk()).isTrue();
            assertThat(imp.catalogCoordinates()).isEqualTo("com.dummy.parent:parent-catalog");
        });
        assertThat(read(child.resolve("settings.gradle.kts"))).contains("create(\"parentLibs\")",
                "from(\"com.dummy.parent:parent-catalog:1.0.1-SNAPSHOT\")");
        // third-party libraries without versions (the root catalog's, also the alias its convention looks up);
        // a project module of another repo keeps its version
        assertThat(read(child.resolve("gradle/libs.versions.toml"))).contains(
                "guava = { module = \"com.google.guava:guava\" }",
                "slf4j-api = { module = \"org.slf4j:slf4j-api\" }",
                "commons-lang3 = { module = \"org.apache.commons:commons-lang3\" }",
                "shared = { module = \"com.dummy.other:shared\", version = \"3.1.0\" }");
        assertThat(read(child.resolve("build-logic/src/main/kotlin/module1.main1-conventions.gradle.kts")))
                .contains("apply(plugin = \"parent.java-conventions\")", "named(\"parentLibs\")");

        // the root repo gets the publication of its build-logic and catalog, without losing the hand edits
        BuildLogicExport.Result export = BuildLogicExport.export(parent, java.util.Map.of("com.dummy.parent:bom", "parent.java-conventions"));
        converter.apply(new MavenToGradleConverter.Conversion(parent, GradleDsl.KOTLIN, export.writes(), List.of(), List.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), List.of()));
        assertThat(export.warnings()).isEmpty();
        assertThat(read(parent.resolve("build-logic/build.gradle.kts"))).contains("`maven-publish`", "`version-catalog`",
                "import java.util.Properties", "artifactId = \"parent-catalog\"", "from(components[\"versionCatalog\"])",
                "https://repo.example.com/snapshots");
        assertThat(read(parent.resolve("build-logic/settings.gradle.kts"))).contains("rootProject.name = \"parent-build-logic\"");
        assertThat(read(parent.resolve("build.gradle.kts"))).contains("gradle.includedBuild(\"build-logic\")");
        assertThat(read(javaConvention)).contains("// maven-parent: com.dummy.parent:bom", "libs.findLibrary(\"commons-lang3\")");
        assertThat(BuildLogicExport.export(parent, java.util.Map.of("com.dummy.parent:bom", "parent.java-conventions")).isEmpty())
                .as("idempotent").isTrue();
        dump("incremental-kotlin");

        new GradleDependencyUpdater().update(List.of(child), java.util.Map.of("com.dummy.parent:bom", "1.0.2-SNAPSHOT"));
        assertThat(read(child.resolve("settings.gradle.kts"))).contains("from(\"com.dummy.parent:parent-catalog:1.0.2-SNAPSHOT\")");
        assertThat(read(child.resolve("gradle/libs.versions.toml"))).contains("bom = \"1.0.2-SNAPSHOT\"");
    }

    @Test
    void rootMigratedEarlier_groovy() throws IOException {
        converter.apply(converter.convert(parent, GradleDsl.GROOVY, List.of()));
        MavenToGradleConverter.Conversion c = migrate(child, GradleDsl.GROOVY);
        assertThat(c.imports()).singleElement().satisfies(imp -> assertThat(imp.pluginId()).isEqualTo("parent.java-conventions"));
        assertThat(read(child.resolve("settings.gradle"))).contains("parentLibs {", "from('com.dummy.parent:parent-catalog:1.0.1-SNAPSHOT')");
        assertThat(read(child.resolve("build-logic/src/main/groovy/module1.main1-conventions.gradle")))
                .contains("id 'parent.java-conventions'", "named('parentLibs')");

        BuildLogicExport.Result export = BuildLogicExport.export(parent, java.util.Map.of("com.dummy.parent:bom", "parent.java-conventions"));
        converter.apply(new MavenToGradleConverter.Conversion(parent, GradleDsl.GROOVY, export.writes(), List.of(), List.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), List.of()));
        assertThat(read(parent.resolve("build-logic/build.gradle"))).contains("id 'maven-publish'", "id 'version-catalog'",
                "catalog(MavenPublication) {", "artifactId = 'parent-catalog'", "from components.versionCatalog");
        assertThat(read(parent.resolve("build.gradle"))).contains("gradle.includedBuild('build-logic')");
        dump("incremental-groovy");
    }

    private GradleMigrationService service() {
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        GradleMigrationService service = new GradleMigrationService(converter, mavenParser, new GradleParser(),
                com.example.multibuild.testutil.TestGit.jgit(), new com.example.multibuild.service.CommitMessageFormatter(),
                GradleWrapperGeneratorTest.generator(tempDir, "", tempDir.resolve("served").toUri().toString()));
        ReflectionTestUtils.setField(service, "dsl", "kotlin");
        ReflectionTestUtils.setField(service, "generateWrapper", false);
        return service;
    }

    @Test
    void migratingTheChild_makesTheRootMigratedEarlierPublishItsBuildLogic() {
        converter.apply(converter.convert(parent, GradleDsl.KOTLIN, List.of()));
        GradleMigrationService service = service();

        assertThat(service.migrate(child, new com.example.multibuild.model.RepoConfig(), false).status())
                .isEqualTo(GradleMigrationService.Status.MIGRATED);
        assertThat(read(parent.resolve("build-logic/build.gradle.kts"))).contains("`version-catalog`", "\"parent-catalog\"");
        assertThat(read(parent.resolve("build-logic/src/main/kotlin/parent.java-conventions.gradle.kts")))
                .contains("// maven-parent: com.dummy.parent:bom");
        // nothing left to do when the root repo itself is migrated afterwards
        assertThat(service.migrate(parent, new com.example.multibuild.model.RepoConfig(), false).status())
                .isEqualTo(GradleMigrationService.Status.ALREADY_GRADLE);
    }

    @Test
    void migratingTheRootMigratedEarlier_publishesItsBuildLogicForTheMavenChildren() {
        converter.apply(converter.convert(parent, GradleDsl.KOTLIN, List.of()));

        assertThat(service().migrate(parent, new com.example.multibuild.model.RepoConfig(), false).status())
                .isEqualTo(GradleMigrationService.Status.BUILD_LOGIC_PUBLISHED);
        assertThat(read(parent.resolve("build-logic/build.gradle.kts"))).contains("`version-catalog`");
    }

    @Test
    void referenceRepo_onlyGetsThePublication_neverMigrated() throws IOException {
        // migrated earlier with the poms kept (migrate.keep.maven=true): a reference repo keeps them
        converter.apply(converter.convert(parent, GradleDsl.KOTLIN, List.of()).keepingMaven());
        GradleMigrationService service = service();

        GradleMigrationService.Result result = service.reference(parent, new com.example.multibuild.model.RepoConfig(), false, "GRADLE");

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.BUILD_LOGIC_PUBLISHED);
        assertThat(parent.resolve("pom.xml")).exists();
        assertThat(parent.resolve("bom/pom.xml")).exists();
        assertThat(read(parent.resolve("build-logic/build.gradle.kts"))).contains("`version-catalog`");
        assertThat(service.reference(parent, new com.example.multibuild.model.RepoConfig(), false, "GRADLE").status())
                .isEqualTo(GradleMigrationService.Status.REFERENCE);
    }

    @Test
    void referenceRepo_stillOnMaven_fails() {
        GradleMigrationService.Result result = service().reference(parent, new com.example.multibuild.model.RepoConfig(), false, "GRADLE");

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.FAILED);
        assertThat(result.errors()).singleElement().asString().contains("no Gradle build on branch GRADLE");
        assertThat(parent.resolve("build-logic")).doesNotExist();
    }

    @Test
    void withoutTheParentRepoInTheWorkspace_theChildKeepsItsOwnConventions() throws IOException {
        MavenToGradleConverter.Conversion c = converter.convert(child, GradleDsl.KOTLIN, List.of());
        assertThat(c.writes()).containsKey(child.resolve("build-logic/src/main/kotlin/module1.java-conventions.gradle.kts"));
        assertThat(c.writes().get(child.resolve("build-logic/build.gradle.kts"))).doesNotContain("gradle.plugin");
    }
}
