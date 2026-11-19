package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.BuildSystem;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepositoryProject;
import com.example.multibuild.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class MavenToGradleConverterTest {

    @TempDir
    Path tempDir;

    private final MavenToGradleConverter converter = new MavenToGradleConverter();
    private final GradleParser gradleParser = new GradleParser();

    static Stream<Arguments> fixturesAndDsls() {
        List<String> fixtures = List.of("repo-a", "repo-a-parent", "repo-b", "repo-b-property",
                "repo-b-depmanagement", "repo-b-with-parent", "repo-c", "repo-multi", "repo-multi-child",
                "repo-multi-parent-dep");
        return fixtures.stream().flatMap(f -> Arrays.stream(GradleDsl.values()).map(d -> Arguments.of(f, d)));
    }

    // The core guarantee: the Gradle build publishes the same artifacts and keeps every
    // external dependency, so the cross-repo graph is unchanged.
    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("fixturesAndDsls")
    void convertedBuild_isEquivalentToMavenBuild(String fixture, GradleDsl dsl) {
        Path repo = Fixtures.copy(fixture, tempDir);
        RepositoryProject maven = mavenParser().parse(repo);

        MavenToGradleConverter.Conversion conversion = converter.convert(repo, dsl);
        converter.apply(conversion);

        assertThat(BuildSystem.detect(repo)).isEqualTo(BuildSystem.GRADLE);
        RepositoryProject gradle = gradleParser.parse(repo);
        assertThat(gradle.getModules()).isNotEmpty();
        assertThat(GradleMigrationService.verify(maven, gradle, conversion)).isEmpty();
        assertThat(conversion.warnings()).isEmpty();
    }

    @Test
    void singleModule_writesSingleProjectBuildAndRemovesPom() {
        Path repo = Fixtures.copy("repo-a", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(repo.resolve("pom.xml")).doesNotExist();
        assertThat(read(repo.resolve("settings.gradle"))).contains("rootProject.name = 'lib-a'");
        assertThat(GradleProperties.read(repo.resolve("gradle.properties")))
                .containsEntry("group", "com.example")
                .containsEntry("version", "1.0.0-SNAPSHOT");
        assertThat(read(repo.resolve("build.gradle")))
                .contains("id 'java-library'", "id 'maven-publish'", "mavenLocal()", "from components.java");
        assertThat(read(repo.resolve(".gitignore")))
                .contains(".gradle/", "build/", "!gradle/wrapper/gradle-wrapper.jar");
    }

    @Test
    void multiModule_includesModulesAndKeepsSharedConfigInRoot() {
        Path repo = Fixtures.copy("repo-multi", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(repo.resolve("pom.xml")).doesNotExist();
        assertThat(repo.resolve("module-core/pom.xml")).doesNotExist();
        assertThat(read(repo.resolve("settings.gradle")))
                .contains("rootProject.name = 'multi-parent'", "include 'module-core'", "include 'module-service'");
        assertThat(read(repo.resolve("build.gradle"))).contains("allprojects {").doesNotContain("plugins");
        assertThat(read(repo.resolve("module-service/build.gradle")))
                .contains("api 'com.example:lib-a:1.0.0-SNAPSHOT'");
    }

    @Test
    void propertyVersion_movesToGradleProperties() {
        Path repo = Fixtures.copy("repo-b-property", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(GradleProperties.read(repo.resolve("gradle.properties")))
                .containsEntry("libAVersion", "1.0.0-SNAPSHOT");
        assertThat(read(repo.resolve("build.gradle"))).contains("api \"com.example:lib-a:${libAVersion}\"");
    }

    @Test
    void propertyVersion_kotlinDeclaresDelegatedProperty() {
        Path repo = Fixtures.copy("repo-b-property", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.KOTLIN));

        assertThat(read(repo.resolve("build.gradle.kts")))
                .contains("val libAVersion: String by project", "api(\"com.example:lib-a:${libAVersion}\")");
        assertThat(read(repo.resolve("settings.gradle.kts"))).contains("rootProject.name = \"service-b-prop\"");
    }

    @Test
    void externalParent_becomesPlatform() {
        Path repo = Fixtures.copy("repo-b-with-parent", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(read(repo.resolve("build.gradle")))
                .contains("api platform('com.example:lib-a-parent:1.0.0-SNAPSHOT')");
    }

    @Test
    void parentPomWithoutModules_becomesJavaPlatform() {
        Path repo = Fixtures.copy("repo-a-parent", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(read(repo.resolve("build.gradle")))
                .contains("id 'java-platform'", "from components.javaPlatform")
                .doesNotContain("java-library");
    }

    @Test
    void dependencyManagement_becomesConstraints() {
        Path repo = Fixtures.copy("repo-b-depmanagement", tempDir);
        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(read(repo.resolve("build.gradle")))
                .contains("constraints {", "api 'com.example:lib-a:1.0.0-SNAPSHOT'", "api 'com.example:lib-a'\n");
    }

    @Test
    void aggregatorDependenciesAndParent_areSharedFromRootScript() {
        Path parentDep = Fixtures.copy("repo-multi-parent-dep", tempDir);
        converter.apply(converter.convert(parentDep, GradleDsl.GROOVY));
        assertThat(read(parentDep.resolve("build.gradle")))
                .contains("subprojects {", "api 'com.example:lib-a:1.0.0-SNAPSHOT'");
        assertThat(read(parentDep.resolve("module-y/build.gradle"))).doesNotContain("lib-a");

        Path multiChild = Fixtures.copy("repo-multi-child", tempDir);
        converter.apply(converter.convert(multiChild, GradleDsl.GROOVY));
        assertThat(read(multiChild.resolve("build.gradle")))
                .contains("api platform('com.example:lib-a-parent:1.0.0-SNAPSHOT')");
        assertThat(read(multiChild.resolve("module-x/build.gradle"))).doesNotContain("lib-a-parent");
    }

    @Test
    void richPom_mapsScopesExclusionsJavaVersionAndPublishing() {
        Path repo = tempDir.resolve("rich");
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>rich-parent</artifactId>
                  <version>5.1.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>web</module></modules>
                  <properties>
                    <java.version>17</java.version>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <guava.version>33.0.0-jre</guava.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId>
                      <version>5.10.2</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <repositories><repository><id>corp</id><url>https://nexus.acme.com/maven/</url></repository></repositories>
                  <distributionManagement>
                    <repository><id>acme-releases</id><url>https://nexus.acme.com/releases</url></repository>
                    <snapshotRepository><id>acme-snapshots</id><url>https://nexus.acme.com/snapshots</url></snapshotRepository>
                  </distributionManagement>
                  <build><plugins>
                    <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId></plugin>
                  </plugins></build>
                </project>
                """);
        write(repo.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>rich-parent</artifactId><version>5.1.0-SNAPSHOT</version></parent>
                  <artifactId>rich-core</artifactId>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version>
                      <exclusions><exclusion><groupId>com.google.code.findbugs</groupId><artifactId>jsr305</artifactId></exclusion></exclusions>
                    </dependency>
                    <dependency><groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId><version>6.0.0</version><scope>provided</scope></dependency>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.1</version><scope>runtime</scope></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
                    <dependency><groupId>com.sun</groupId><artifactId>tools</artifactId><version>1.8</version><scope>system</scope><systemPath>/x/tools.jar</systemPath></dependency>
                  </dependencies>
                </project>
                """);
        write(repo.resolve("web/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>rich-parent</artifactId><version>5.1.0-SNAPSHOT</version></parent>
                  <artifactId>rich-web</artifactId>
                  <packaging>war</packaging>
                  <dependencies>
                    <dependency><groupId>${project.groupId}</groupId><artifactId>rich-core</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId><version>6.0.0</version><scope>provided</scope></dependency>
                  </dependencies>
                </project>
                """);
        RepositoryProject maven = mavenParser().parse(repo);

        MavenToGradleConverter.Conversion conversion = converter.convert(repo, GradleDsl.GROOVY);
        converter.apply(conversion);

        // parent configuration: once per packaging in the root script
        String root = read(repo.resolve("build.gradle"));
        assertThat(root)
                .contains("configure([project(':core')]) {", "configure([project(':web')]) {")
                .contains("api platform('org.junit:junit-bom:5.10.2')", "implementation platform('org.junit:junit-bom:5.10.2')")
                .contains("testRuntimeOnly 'org.junit.platform:junit-platform-launcher'")
                .contains("useJUnitPlatform()")
                .contains("sourceCompatibility = JavaVersion.toVersion('17')")
                .contains("options.encoding = 'UTF-8'")
                .contains("apply plugin: 'war'", "from components.web", "from components.java")
                .contains("url = uri(version.toString().endsWith('SNAPSHOT') ? 'https://nexus.acme.com/snapshots' : 'https://nexus.acme.com/releases')")
                .contains("credentials(PasswordCredentials)");
        String core = read(repo.resolve("core/build.gradle"));
        assertThat(core)
                .contains("api(\"com.google.guava:guava:${guavaVersion}\") {\n        exclude group: 'com.google.code.findbugs', module: 'jsr305'\n    }")
                .contains("compileOnly 'jakarta.servlet:jakarta.servlet-api:6.0.0'")
                .contains("runtimeOnly 'org.postgresql:postgresql:42.7.1'")
                .contains("testImplementation 'org.junit.jupiter:junit-jupiter'")
                .contains("publishing.publications.maven.artifactId = 'rich-core'")
                .doesNotContain("com.sun:tools", "junit-bom", "JavaVersion", "useJUnitPlatform");
        String web = read(repo.resolve("web/build.gradle"));
        assertThat(web)
                .contains("implementation project(':core')",
                        "providedCompile 'jakarta.servlet:jakarta.servlet-api:6.0.0'",
                        "publishing.publications.maven.artifactId = 'rich-web'")
                .doesNotContain("junit-bom");
        assertThat(read(repo.resolve("build.gradle"))).contains("maven { url = uri('https://nexus.acme.com/maven') }");
        assertThat(GradleProperties.read(repo.resolve("gradle.properties")))
                .containsEntry("group", "com.acme")
                .containsEntry("version", "5.1.0-SNAPSHOT")
                .containsEntry("guavaVersion", "33.0.0-jre");

        assertThat(conversion.warnings())
                .anyMatch(w -> w.contains("exec-maven-plugin"))
                .anyMatch(w -> w.contains("system-scoped dependency com.sun:tools"))
                .anyMatch(w -> w.contains("acmeReleasesUsername"));
        assertThat(conversion.droppedKeys()).containsExactly("com.sun:tools");

        RepositoryProject gradle = gradleParser.parse(repo);
        assertThat(gradle.getModules()).extracting(m -> m.getArtifact().toString())
                .containsExactlyInAnyOrder("com.acme:rich-core:5.1.0-SNAPSHOT", "com.acme:rich-web:5.1.0-SNAPSHOT");
        assertThat(GradleMigrationService.verify(maven, gradle, conversion)).isEmpty();
    }

    @Test
    void richPom_kotlinDsl() {
        Path repo = tempDir.resolve("kts");
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.2.0-SNAPSHOT</version>
                  <packaging>war</packaging>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version>
                      <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>
                  </dependencies>
                </project>
                """);
        converter.apply(converter.convert(repo, GradleDsl.KOTLIN));

        assertThat(read(repo.resolve("build.gradle.kts")))
                .contains("id(\"war\")", "implementation(\"org.slf4j:slf4j-api:2.0.9\") {\n        isTransitive = false\n    }",
                        "create<MavenPublication>(\"maven\")", "from(components[\"web\"])");
        assertThat(gradleParser.parse(repo).getModules()).extracting(m -> m.getArtifact().toString())
                .containsExactly("com.acme:app:1.2.0-SNAPSHOT");
    }

    @Test
    void springBootParent_isReplacedByDependenciesBom() {
        Path repo = tempDir.resolve("boot");
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.2.3</version></parent>
                  <groupId>com.acme</groupId><artifactId>svc</artifactId><version>1.0.0-SNAPSHOT</version>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        RepositoryProject maven = mavenParser().parse(repo);
        MavenToGradleConverter.Conversion conversion = converter.convert(repo, GradleDsl.GROOVY);
        converter.apply(conversion);

        assertThat(read(repo.resolve("build.gradle")))
                .contains("api platform('org.springframework.boot:spring-boot-dependencies:3.2.3')",
                        "api 'org.springframework.boot:spring-boot-starter'");
        assertThat(GradleMigrationService.verify(maven, gradleParser.parse(repo), conversion)).isEmpty();
    }

    @Test
    void existingGitignoreAndMavenWrapper_areHandled() {
        Path repo = Fixtures.copy("repo-a", tempDir);
        write(repo.resolve(".gitignore"), "target/\n*.jar");
        write(repo.resolve("mvnw"), "#!/bin/sh");
        write(repo.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=x");

        converter.apply(converter.convert(repo, GradleDsl.GROOVY));

        assertThat(read(repo.resolve(".gitignore")))
                .startsWith("target/\n*.jar\n\n# Gradle\n")
                .contains("!gradle/wrapper/gradle-wrapper.jar");
        assertThat(repo.resolve("mvnw")).doesNotExist();
        assertThat(repo.resolve(".mvn/wrapper/maven-wrapper.properties")).doesNotExist();
    }

    @Test
    void moduleOutsideRepo_isReportedAndMissingFromBuild() {
        Path repo = tempDir.resolve("outside");
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>agg</artifactId><version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>../sibling</module></modules>
                </project>
                """);
        write(tempDir.resolve("sibling/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>sibling</artifactId><version>1.0.0</version>
                </project>
                """);

        MavenToGradleConverter.Conversion conversion = converter.convert(repo, GradleDsl.GROOVY);

        assertThat(conversion.warnings()).anyMatch(w -> w.contains("outside the repository root"));
        assertThat(conversion.writes().keySet()).noneMatch(p -> p.startsWith(tempDir.resolve("sibling")));
        assertThat(Files.exists(tempDir.resolve("sibling/pom.xml"))).isTrue();
    }

    @Test
    void gradleName_convertsMavenPropertyNames() {
        assertThat(MavenToGradleConverter.gradleName("lib-a.version")).isEqualTo("libAVersion");
        assertThat(MavenToGradleConverter.gradleName("spring_boot.version")).isEqualTo("springBootVersion");
        assertThat(MavenToGradleConverter.gradleName("1x")).isEqualTo("p1x");
        assertThat(MavenToGradleConverter.gradleName("version")).isEqualTo("versionProp");
    }

    private static MavenParserImpl mavenParser() {
        MavenParserImpl parser = new MavenParserImpl();
        ReflectionTestUtils.setField(parser, "declaredModulesOnly", true);
        return parser;
    }
}
