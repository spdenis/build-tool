package com.example.multibuild.gradle;

import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

// A dependency without <scope> takes the scope (and optional / exclusions) from dependencyManagement,
// wherever that comes from — like Maven does. The Gradle configuration must follow the effective scope.
class ManagedScopeTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private Path m2;
    private final MavenToGradleConverter converter = new MavenToGradleConverter();

    @BeforeEach
    void setUp() {
        repo = tempDir.resolve("repo");
        m2 = tempDir.resolve("m2");
        ReflectionTestUtils.setField(converter, "localRepository", m2.toString());
    }

    private MavenToGradleConverter.Conversion migrate() {
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        RepositoryProject maven = mavenParser.parse(repo);
        MavenToGradleConverter.Conversion c = converter.convert(repo, GradleDsl.GROOVY);
        converter.apply(c);
        assertThat(GradleMigrationService.verify(maven, new GradleParser().parse(repo), c)).isEmpty();
        return c;
    }

    private void installPom(String g, String a, String v, String body) {
        write(m2.resolve(g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ".pom"),
                "<project><modelVersion>4.0.0</modelVersion>" + body + "</project>");
    }

    private static String dep(String g, String a) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId></dependency>";
    }

    @Test
    void ownDependencyManagement_scopeIsApplied_andConstraintUsesThatConfiguration() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.8.0</version><scope>test</scope></dependency>
                    <dependency><groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId><version>6.0.0</version><scope>provided</scope></dependency>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.1</version><scope>runtime</scope></dependency>
                    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><version>2.2.224</version><scope>test</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    %s %s %s
                    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
                  </dependencies>
                </project>
                """.formatted(dep("org.mockito", "mockito-core"), dep("jakarta.servlet", "jakarta.servlet-api"),
                dep("org.postgresql", "postgresql")));

        migrate();

        assertThat(read(repo.resolve("build.gradle"))).contains(
                "testImplementation 'org.mockito:mockito-core:5.8.0'",
                "compileOnly 'jakarta.servlet:jakarta.servlet-api:6.0.0'",
                "runtimeOnly 'org.postgresql:postgresql:42.7.1'",
                "testImplementation 'org.mockito:mockito-core'\n",
                "compileOnly 'jakarta.servlet:jakarta.servlet-api'\n",
                "runtimeOnly 'org.postgresql:postgresql'\n",
                // a declared scope wins over the managed one
                "runtimeOnly 'com.h2database:h2'\n");
    }

    @Test
    void parentPomManagement_scopeIsAppliedInModules() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module></modules>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>3.25.1</version><scope>test</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write(repo.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(dep("org.assertj", "assertj-core")));

        migrate();

        assertThat(read(repo.resolve("build.gradle"))).contains("testImplementation 'org.assertj:assertj-core:3.25.1'");
        assertThat(read(repo.resolve("core/build.gradle"))).contains("testImplementation 'org.assertj:assertj-core'\n")
                .doesNotContain("api 'org.assertj");
    }

    @Test
    void importedInRepoBom_scopeIsApplied() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>agg</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module><module>core</module></modules>
                </project>
                """);
        write(repo.resolve("bom/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>acme-bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><version>1.19.3</version><scope>test</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write(repo.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>core</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>acme-bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(dep("org.testcontainers", "postgresql")));

        migrate();

        assertThat(read(repo.resolve("core/build.gradle")))
                .contains("api platform(project(':bom'))", "testImplementation 'org.testcontainers:postgresql'\n");
    }

    @Test
    void externalBomFromLocalRepository_scopeOptionalAndExclusionsAreApplied() {
        // BOM with a parent chain and ${project.groupId}/${project.version} placeholders, importing another BOM.
        installPom("com.corp", "corp-parent", "7", """
                <groupId>com.corp</groupId><artifactId>corp-parent</artifactId><version>7</version><packaging>pom</packaging>
                <properties><wiremock.version>3.3.1</wiremock.version></properties>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.wiremock</groupId><artifactId>wiremock</artifactId><version>${wiremock.version}</version><scope>test</scope></dependency>
                </dependencies></dependencyManagement>
                """);
        installPom("com.corp", "corp-bom", "2.0", """
                <parent><groupId>com.corp</groupId><artifactId>corp-parent</artifactId><version>7</version></parent>
                <artifactId>corp-bom</artifactId><version>2.0</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>
                  <dependency><groupId>${project.groupId}</groupId><artifactId>corp-test-kit</artifactId><version>${project.version}</version><scope>test</scope></dependency>
                  <dependency><groupId>com.corp</groupId><artifactId>corp-client</artifactId><version>${project.version}</version>
                    <optional>true</optional>
                    <exclusions><exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion></exclusions>
                  </dependency>
                  <dependency><groupId>com.other</groupId><artifactId>other-bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>
                </dependencies></dependencyManagement>
                """);
        installPom("com.other", "other-bom", "1", """
                <groupId>com.other</groupId><artifactId>other-bom</artifactId><version>1</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.other</groupId><artifactId>fixtures</artifactId><version>1</version><scope>test</scope></dependency>
                </dependencies></dependencyManagement>
                """);
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.corp</groupId><artifactId>corp-bom</artifactId><version>2.0</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>%s %s %s %s</dependencies>
                </project>
                """.formatted(dep("com.corp", "corp-test-kit"), dep("com.corp", "corp-client"),
                dep("org.wiremock", "wiremock"), dep("com.other", "fixtures")));

        MavenToGradleConverter.Conversion c = migrate();

        assertThat(read(repo.resolve("build.gradle"))).contains(
                "api platform('com.corp:corp-bom:2.0')",
                "testImplementation 'com.corp:corp-test-kit'\n",
                "testImplementation 'org.wiremock:wiremock'\n",     // from the BOM's parent
                "testImplementation 'com.other:fixtures'\n",        // from a BOM the BOM imports
                "    implementation('com.corp:corp-client') {\n        exclude group: 'commons-logging', module: 'commons-logging'\n    }");
        assertThat(c.warnings()).isEmpty();
    }

    @Test
    void externalParentFromLocalRepository_scopeIsApplied() {
        installPom("com.corp", "corp-parent", "7", """
                <groupId>com.corp</groupId><artifactId>corp-parent</artifactId><version>7</version><packaging>pom</packaging>
                <dependencyManagement><dependencies>
                  <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.1</version><scope>test</scope></dependency>
                </dependencies></dependencyManagement>
                """);
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.corp</groupId><artifactId>corp-parent</artifactId><version>7</version></parent>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(dep("org.junit.jupiter", "junit-jupiter")));

        migrate();

        assertThat(read(repo.resolve("build.gradle")))
                .contains("testImplementation 'org.junit.jupiter:junit-jupiter'\n", "useJUnitPlatform()");
    }

    @Test
    void bomMissingFromLocalRepository_isReported() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.corp</groupId><artifactId>corp-bom</artifactId><version>9.9</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(dep("com.corp", "corp-test-kit")));

        MavenToGradleConverter.Conversion c = migrate();

        assertThat(read(repo.resolve("build.gradle"))).contains("api 'com.corp:corp-test-kit'\n");
        assertThat(c.warnings()).anyMatch(w -> w.contains("com.corp:corp-test-kit is managed by com.corp:corp-bom:9.9")
                && w.contains("scope assumed compile"));
    }
}
