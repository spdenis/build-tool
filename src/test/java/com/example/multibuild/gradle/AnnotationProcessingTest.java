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

// Gradle runs annotation processors only from the annotationProcessor path; Maven also discovers them on
// the classpath. The migration must keep every processor and compiler argument working.
class AnnotationProcessingTest {

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

    private String migrate(GradleDsl dsl) {
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        RepositoryProject maven = mavenParser.parse(repo);
        MavenToGradleConverter.Conversion c = converter.convert(repo, dsl);
        converter.apply(c);
        assertThat(GradleMigrationService.verify(maven, new GradleParser().parse(repo), c)).isEmpty();
        return read(repo.resolve(dsl.buildFile()));
    }

    private void pom(String parent, String body) {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>%s
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  %s
                </project>""".formatted(parent, body));
    }

    @Test
    void annotationProcessorPaths_becomeProcessorDependencies_withCompilerArgs() {
        pom("", """
                <properties><mapstruct.version>1.5.5.Final</mapstruct.version></properties>
                <dependencies>
                  <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>${mapstruct.version}</version></dependency>
                  <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency>
                </dependencies>
                <build><plugins><plugin>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <configuration>
                    <parameters>true</parameters>
                    <annotationProcessorPaths>
                      <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version></path>
                      <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>${mapstruct.version}</version></path>
                    </annotationProcessorPaths>
                    <compilerArgs><arg>-Amapstruct.defaultComponentModel=spring</arg></compilerArgs>
                  </configuration>
                </plugin></plugins></build>
                """);

        assertThat(migrate(GradleDsl.GROOVY)).contains(
                "compileOnly 'org.projectlombok:lombok:1.18.30'",
                "annotationProcessor 'org.projectlombok:lombok:1.18.30'",
                "annotationProcessor \"org.mapstruct:mapstruct-processor:${mapstructVersion}\"",
                "testAnnotationProcessor 'org.projectlombok:lombok:1.18.30'",
                "testAnnotationProcessor \"org.mapstruct:mapstruct-processor:${mapstructVersion}\"",
                "options.compilerArgs += ['-parameters', '-Amapstruct.defaultComponentModel=spring']");
    }

    @Test
    void processorOnClasspath_isDiscoveredLikeMavenDoes() {
        pom("", """
                <dependencies>
                  <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency>
                  <dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-jpamodelgen</artifactId><version>6.4.1.Final</version><scope>test</scope></dependency>
                  <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.0.0-jre</version></dependency>
                </dependencies>
                """);

        String script = migrate(GradleDsl.GROOVY);
        assertThat(script).contains(
                "compileOnly 'org.projectlombok:lombok:1.18.30'",
                "annotationProcessor 'org.projectlombok:lombok:1.18.30'",
                "testCompileOnly 'org.projectlombok:lombok:1.18.30'",
                "testAnnotationProcessor 'org.projectlombok:lombok:1.18.30'",
                "testAnnotationProcessor 'org.hibernate.orm:hibernate-jpamodelgen:6.4.1.Final'")
                .doesNotContain("annotationProcessor 'org.hibernate.orm", "annotationProcessor 'com.google.guava", "compilerArgs");
    }

    private static final String IMPLICIT_GROOVY =
            "configurations.named('annotationProcessor') { extendsFrom(configurations.compileOnly, configurations.implementation) }";
    private static final String IMPLICIT_TEST_GROOVY =
            "configurations.named('testAnnotationProcessor') { extendsFrom(configurations.testCompileOnly, configurations.testImplementation) }";

    @Test
    void springBootParent_declaresCompilerPlugin_soProcessingIsImplicit() {
        pom("<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.2.0</version></parent>",
                """
                <dependencies>
                  <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
                  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-configuration-processor</artifactId><optional>true</optional></dependency>
                </dependencies>
                """);

        // the processors (and the Boot platform managing their versions) reach the processor path through
        // the compile configurations — no per-processor lines needed
        assertThat(migrate(GradleDsl.GROOVY))
                .contains(IMPLICIT_GROOVY, IMPLICIT_TEST_GROOVY, "options.compilerArgs += ['-parameters']")
                .doesNotContain("annotationProcessor 'org.projectlombok", "annotationProcessor platform(");
    }

    @Test
    void declaredCompilerPlugin_enablesImplicitProcessing_alsoForUnknownProcessors() {
        pom("", """
                <dependencies>
                  <dependency><groupId>com.acme.tools</groupId><artifactId>acme-codegen</artifactId><version>2.0</version><scope>provided</scope></dependency>
                  <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency>
                </dependencies>
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                  <configuration><release>21</release></configuration>
                </plugin></plugins></build>
                """);

        assertThat(migrate(GradleDsl.GROOVY))
                .contains(IMPLICIT_GROOVY, IMPLICIT_TEST_GROOVY,
                        "compileOnly 'com.acme.tools:acme-codegen:2.0'",
                        "testCompileOnly 'org.projectlombok:lombok:1.18.30'")
                .doesNotContain("annotationProcessor 'org.projectlombok", "annotationProcessor 'com.acme");
    }

    @Test
    void processorPaths_winOverImplicitProcessing_andProcNoneDisablesIt() {
        pom("", """
                <dependencies>
                  <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency>
                </dependencies>
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                  <configuration><annotationProcessorPaths>
                    <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version></path>
                  </annotationProcessorPaths></configuration>
                </plugin></plugins></build>
                """);
        assertThat(migrate(GradleDsl.GROOVY)).contains("annotationProcessor 'org.projectlombok:lombok:1.18.30'")
                .doesNotContain("extendsFrom");
    }

    @Test
    void compilerConfigFromExternalCorporateParent_isApplied() {
        write(m2.resolve("com/corp/corp-root/12/corp-root-12.pom"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.corp</groupId><artifactId>corp-root</artifactId><version>12</version><packaging>pom</packaging>
                  <properties><lombok.version>1.18.32</lombok.version></properties>
                  <build><pluginManagement><plugins><plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <annotationProcessorPaths>
                        <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>
                      </annotationProcessorPaths>
                      <compilerArgs><arg>-Xlint:unchecked</arg></compilerArgs>
                    </configuration>
                  </plugin></plugins></pluginManagement></build>
                </project>""");
        pom("<parent><groupId>com.corp</groupId><artifactId>corp-root</artifactId><version>12</version></parent>", "");

        assertThat(migrate(GradleDsl.GROOVY)).contains(
                "annotationProcessor 'org.projectlombok:lombok:1.18.32'",
                "options.compilerArgs += ['-Xlint:unchecked']");
    }

    @Test
    void parentProcessorConfig_isSharedOnce_andKotlinSyntax() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>a</module><module>b</module></modules>
                  <dependencies>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                    <configuration><compilerArgs><arg>-Xlint:all</arg></compilerArgs></configuration>
                  </plugin></plugins></build>
                </project>""");
        for (String m : new String[]{"a", "b"}) {
            write(repo.resolve(m + "/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                      <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                      <artifactId>%s</artifactId></project>""".formatted(m));
        }

        String root = migrate(GradleDsl.KOTLIN);
        assertThat(root).contains("subprojects {",
                "\"compileOnly\"(\"org.projectlombok:lombok:1.18.30\")",
                "\"testCompileOnly\"(\"org.projectlombok:lombok:1.18.30\")",
                "configurations.named(\"annotationProcessor\") { extendsFrom(configurations[\"compileOnly\"], configurations[\"implementation\"]) }",
                "configurations.named(\"testAnnotationProcessor\") { extendsFrom(configurations[\"testCompileOnly\"], configurations[\"testImplementation\"]) }",
                "options.compilerArgs.addAll(listOf(\"-Xlint:all\"))");
        assertThat(read(repo.resolve("a/build.gradle.kts"))).doesNotContain("lombok", "compilerArgs");
    }

    @Test
    void implicitProcessing_isSharedEvenWhenModulesHaveDifferentProcessors() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>a</module><module>b</module></modules>
                  <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId></plugin></plugins></build>
                </project>""");
        write(repo.resolve("a/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>a</artifactId>
                  <dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.30</version><scope>provided</scope></dependency></dependencies>
                </project>""");
        write(repo.resolve("b/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>b</artifactId></project>""");

        assertThat(migrate(GradleDsl.GROOVY)).contains("subprojects {", IMPLICIT_GROOVY, IMPLICIT_TEST_GROOVY);
        assertThat(read(repo.resolve("a/build.gradle")))
                .contains("compileOnly 'org.projectlombok:lombok:1.18.30'", "testCompileOnly 'org.projectlombok:lombok:1.18.30'")
                .doesNotContain("extendsFrom");
        assertThat(read(repo.resolve("b/build.gradle"))).doesNotContain("extendsFrom");
    }

    @Test
    void procNone_isKept_withoutImplicitProcessing() {
        pom("", """
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                  <configuration><proc>none</proc></configuration>
                </plugin></plugins></build>
                """);
        assertThat(migrate(GradleDsl.GROOVY)).contains("options.compilerArgs += ['-proc:none']").doesNotContain("extendsFrom");
    }
}
