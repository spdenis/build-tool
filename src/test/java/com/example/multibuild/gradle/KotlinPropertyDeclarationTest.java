package com.example.multibuild.gradle;

import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepositoryProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

// Kotlin DSL has no dynamic property lookup: a "${springVersion}" template only compiles when the script
// (or the enclosing block) declares `val springVersion: String by project`. Properties defined in a
// parent pom end up in the root gradle.properties, so every script that references one must declare it.
class KotlinPropertyDeclarationTest {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{(\\w+)}");
    private static final Set<String> PROJECT_MEMBERS = Set.of("version", "group", "name");

    @TempDir
    Path tempDir;

    private Path repo;
    private final MavenToGradleConverter converter = com.example.multibuild.testutil.Converters.rootScriptLayout();

    @BeforeEach
    void setUp() {
        repo = tempDir.resolve("repo");
        ReflectionTestUtils.setField(converter, "localRepository", tempDir.resolve("m2").toString());
    }

    private void migrate() {
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        RepositoryProject maven = mavenParser.parse(repo);
        MavenToGradleConverter.Conversion c = converter.convert(repo, GradleDsl.KOTLIN);
        converter.apply(c);
        assertThat(GradleMigrationService.verify(maven, new GradleParser().parse(repo), c)).isEmpty();
    }

    // Every ${name} in a script is declared in that script, before its first use.
    private List<String> undeclaredReferences() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repo)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".gradle.kts")).toList()) {
                String text = read(f);
                Matcher m = REFERENCE.matcher(text);
                while (m.find()) {
                    String name = m.group(1);
                    if (PROJECT_MEMBERS.contains(name)) continue;
                    int declared = text.indexOf("val " + name + ": String by project");
                    if (declared < 0 || declared > m.start()) problems.add(repo.relativize(f) + ": " + name);
                }
            }
        }
        return problems;
    }

    @Test
    void parentProperties_usedByModules_areDeclaredInEveryKotlinScript() throws IOException {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>app</module></modules>
                  <properties>
                    <spring.version>6.1.5</spring.version>
                    <guava.version>33.0.0-jre</guava.version>
                    <jackson.version>2.17.0</jackson.version>
                    <lombok.version>1.18.30</lombok.version>
                    <mapstruct.version>1.5.5.Final</mapstruct.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.fasterxml.jackson</groupId><artifactId>jackson-bom</artifactId>
                      <version>${jackson.version}</version><type>pom</type><scope>import</scope></dependency>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.springframework</groupId><artifactId>spring-test</artifactId>
                      <version>${spring.version}</version><scope>test</scope></dependency>
                  </dependencies>
                </project>""");
        write(repo.resolve("core/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>${spring.version}</version></dependency>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><scope>provided</scope></dependency>
                  </dependencies>
                </project>""");
        write(repo.resolve("app/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>${mapstruct.version}</version></dependency>
                    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><annotationProcessorPaths>
                      <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>${mapstruct.version}</version></path>
                    </annotationProcessorPaths></configuration>
                  </plugin></plugins></build>
                </project>""");

        migrate();

        assertThat(undeclaredReferences()).isEmpty();
        assertThat(read(repo.resolve("build.gradle.kts")))
                .contains("val springVersion: String by project", "val guavaVersion: String by project",
                        "val jacksonVersion: String by project", "val lombokVersion: String by project");
        // versionless processor: pinned from the parent's dependencyManagement, still via the property
        assertThat(read(repo.resolve("core/build.gradle.kts"))).contains(
                "val springVersion: String by project",
                "val lombokVersion: String by project",
                "annotationProcessor(\"org.projectlombok:lombok:${lombokVersion}\")");
        assertThat(read(repo.resolve("app/build.gradle.kts")))
                .contains("val mapstructVersion: String by project")
                .doesNotContain("val springVersion");
        assertThat(undeclaredReferences()).isEmpty();
    }

    @Test
    void bomVersionProperty_onProcessorPath_isDeclaredInTheModule() throws IOException {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>core</module></modules>
                  <properties><acme.bom.version>7.0</acme.bom.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.acme.platform</groupId><artifactId>acme-bom</artifactId>
                      <version>${acme.bom.version}</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>""");
        write(repo.resolve("core/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><scope>provided</scope></dependency>
                  </dependencies>
                </project>""");

        migrate();

        assertThat(undeclaredReferences()).isEmpty();
        // single inheriting module: the processor setup is shared in the root block, declaration included
        assertThat(read(repo.resolve("build.gradle.kts"))).contains(
                "val acmeBomVersion: String by project",
                "\"annotationProcessor\"(platform(\"com.acme.platform:acme-bom:${acmeBomVersion}\"))");
    }
}
