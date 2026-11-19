package com.example.multibuild.gradle;

import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

// A parent pom's configuration ends up once in the root build script instead of in every module.
class SharedParentConfigTest {

    @TempDir
    Path repo;

    private final MavenToGradleConverter converter = new MavenToGradleConverter();

    private void parentWithModules() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                  </parent>
                  <groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>api</module><module>bom</module></modules>
                  <properties>
                    <java.version>17</java.version>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <guava.version>33.0.0-jre</guava.version>
                    <commons.version>3.14.0</commons.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
                    <dependency><groupId>com.acme.other</groupId><artifactId>other-bom</artifactId><version>2.0.0</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>${commons.version}</version></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.1</version><scope>test</scope></dependency>
                  </dependencies>
                  <distributionManagement>
                    <repository><id>acme-releases</id><url>https://nexus.acme/releases</url></repository>
                    <snapshotRepository><id>acme-snapshots</id><url>https://nexus.acme/snapshots</url></snapshotRepository>
                  </distributionManagement>
                </project>
                """);
        write(repo.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        write(repo.resolve("api/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>acme-api</artifactId>
                  <properties><commons.version>3.12.0</commons.version></properties>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.12</version></dependency>
                  </dependencies>
                </project>
                """);
        write(repo.resolve("bom/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>acme-bom</artifactId>
                  <packaging>pom</packaging>
                </project>
                """);
    }

    private MavenToGradleConverter.Conversion migrate(GradleDsl dsl) {
        RepositoryProject maven = mavenParser().parse(repo);
        MavenToGradleConverter.Conversion c = converter.convert(repo, dsl);
        converter.apply(c);
        assertThat(GradleMigrationService.verify(maven, new GradleParser().parse(repo), c)).isEmpty();
        return c;
    }

    @Test
    void groovy_parentConfigLivesOnceInRootScript() {
        parentWithModules();
        migrate(GradleDsl.GROOVY);

        assertThat(read(repo.resolve("build.gradle")))
                .contains("configure([project(':core'), project(':api')]) {",
                        "apply plugin: 'java-library'", "apply plugin: 'maven-publish'",
                        "sourceCompatibility = JavaVersion.toVersion('17')",
                        "api platform('org.springframework.boot:spring-boot-dependencies:3.2.0')",
                        "api platform('com.acme.other:other-bom:2.0.0')",
                        "api \"com.google.guava:guava:${guavaVersion}\"",
                        "testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'",
                        "options.encoding = 'UTF-8'", "useJUnitPlatform()",
                        "url = uri(version.toString().endsWith('SNAPSHOT') ? 'https://nexus.acme/snapshots'");

        String core = read(repo.resolve("core/build.gradle"));
        assertThat(core)
                .contains("api 'com.google.guava:guava'")
                .doesNotContain("plugins", "platform(", "junit", "java {", "encoding", "publishing", "constraints");
        String api = read(repo.resolve("api/build.gradle"));
        assertThat(api)
                .contains("api project(':core')", "publishing.publications.maven.artifactId = 'acme-api'")
                .doesNotContain("plugins", "junit", "repositories");
    }

    @Test
    void overriddenParentContributions_stayInEachModule() {
        parentWithModules();
        migrate(GradleDsl.GROOVY);

        // api redeclares slf4j and redefines commons.version: Maven's "child wins" is kept by declaring them
        // per module rather than in the shared block (Gradle would otherwise pick the highest version).
        String root = read(repo.resolve("build.gradle"));
        assertThat(root).doesNotContain("slf4j", "commons-lang3");
        assertThat(read(repo.resolve("core/build.gradle")))
                .contains("api 'org.slf4j:slf4j-api:2.0.9'", "api \"org.apache.commons:commons-lang3:${commonsVersion}\"");
        assertThat(read(repo.resolve("api/build.gradle")))
                .contains("api 'org.slf4j:slf4j-api:2.0.12'", "api \"org.apache.commons:commons-lang3:${acmeApiCommonsVersion}\"");
        assertThat(GradleProperties.read(repo.resolve("gradle.properties")))
                .containsEntry("commonsVersion", "3.14.0").containsEntry("acmeApiCommonsVersion", "3.12.0");
    }

    @Test
    void bomChild_isStandalonePlatformWithInheritedManagement() {
        parentWithModules();
        migrate(GradleDsl.GROOVY);

        assertThat(read(repo.resolve("bom/build.gradle")))
                .contains("id 'java-platform'", "api \"com.google.guava:guava:${guavaVersion}\"",
                        "from components.javaPlatform", "artifactId = 'acme-bom'")
                .doesNotContain("useJUnitPlatform", "tasks.named('test'");
    }

    @Test
    void kotlin_sharedBlockUsesAccessorFreeSyntax() {
        parentWithModules();
        migrate(GradleDsl.KOTLIN);

        assertThat(read(repo.resolve("build.gradle.kts")))
                .contains("configure(listOf(project(\":core\"), project(\":api\"))) {",
                        "apply(plugin = \"java-library\")", "val guavaVersion: String by project",
                        "configure<JavaPluginExtension> {",
                        "add(\"api\", \"com.google.guava:guava:${guavaVersion}\")",
                        "\"api\"(platform(\"org.springframework.boot:spring-boot-dependencies:3.2.0\"))",
                        "\"testRuntimeOnly\"(\"org.junit.platform:junit-platform-launcher\")",
                        "tasks.withType<Test>().configureEach {", "configure<PublishingExtension> {");
        // module scripts keep their plugins block so the type-safe accessors exist
        assertThat(read(repo.resolve("api/build.gradle.kts")))
                .contains("id(\"java-library\")", "api(project(\":core\"))",
                        "publishing.publications.named<MavenPublication>(\"maven\") { artifactId = \"acme-api\" }")
                .doesNotContain("junit", "JavaVersion");
    }

    @Test
    void everyModuleInheriting_usesSubprojectsBlock() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>p</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>a</module><module>b</module></modules>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
                  </dependencies>
                </project>
                """);
        for (String m : new String[]{"a", "b"}) {
            write(repo.resolve(m + "/pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent><groupId>com.acme</groupId><artifactId>p</artifactId><version>1.0</version></parent>
                      <artifactId>%s</artifactId>
                    </project>
                    """.formatted(m));
        }
        migrate(GradleDsl.GROOVY);

        assertThat(read(repo.resolve("build.gradle"))).contains("subprojects {", "api 'org.slf4j:slf4j-api:2.0.9'");
        assertThat(read(repo.resolve("a/build.gradle"))).doesNotContain("slf4j", "plugins", "publishing");
    }

    @Test
    void mixedPackagingInheritors_fallBackToPerModuleConfig() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>p</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>lib</module><module>web</module></modules>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
                  </dependencies>
                </project>
                """);
        write(repo.resolve("lib/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>p</artifactId><version>1.0</version></parent>
                  <artifactId>lib</artifactId></project>
                """);
        write(repo.resolve("web/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>p</artifactId><version>1.0</version></parent>
                  <artifactId>web</artifactId><packaging>war</packaging></project>
                """);
        migrate(GradleDsl.GROOVY);

        assertThat(read(repo.resolve("build.gradle"))).doesNotContain("configure(", "subprojects");
        assertThat(read(repo.resolve("lib/build.gradle"))).contains("id 'java-library'", "api 'org.slf4j:slf4j-api:2.0.9'");
        assertThat(read(repo.resolve("web/build.gradle"))).contains("id 'war'", "implementation 'org.slf4j:slf4j-api:2.0.9'");
    }

    @Test
    void sharedDependencies_areUpdatedByTheBuildTool() {
        parentWithModules();
        migrate(GradleDsl.GROOVY);

        new GradleDependencyUpdater().update(java.util.List.of(repo), java.util.Map.of(
                "com.google.guava:guava", "33.1.0-jre",
                "org.junit.jupiter:junit-jupiter", "5.11.0"));

        assertThat(GradleProperties.read(repo.resolve("gradle.properties"))).containsEntry("guavaVersion", "33.1.0-jre");
        assertThat(read(repo.resolve("build.gradle"))).contains("testImplementation 'org.junit.jupiter:junit-jupiter:5.11.0'");
        assertThat(new GradleParser().parse(repo).getModules())
                .filteredOn(m -> !m.getArtifact().getArtifactId().equals("acme-bom"))
                .allSatisfy(m -> assertThat(m.getDependencies()).extracting(d -> d.getArtifact().toString())
                        .contains("org.junit.jupiter:junit-jupiter:5.11.0", "com.google.guava:guava:33.1.0-jre",
                                "org.springframework.boot:spring-boot-dependencies:3.2.0"));
    }

    static MavenParserImpl mavenParser() {
        MavenParserImpl p = new MavenParserImpl();
        ReflectionTestUtils.setField(p, "declaredModulesOnly", true);
        return p;
    }
}
