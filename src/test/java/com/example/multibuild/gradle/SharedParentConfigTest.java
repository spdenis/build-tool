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

    // Root pom with the corporate parent; every module inherits the root; jar and war modules mixed.
    private void corporateParentWithMixedModules() {
        write(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.corp</groupId><artifactId>corp-root</artifactId><version>12</version></parent>
                  <groupId>com.acme</groupId><artifactId>acme</artifactId><version>2.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>lib-a</module><module>lib-b</module><module>web</module></modules>
                  <properties><java.version>21</java.version></properties>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
                  </dependencies>
                </project>
                """);
        for (String m : new String[]{"lib-a", "lib-b"}) {
            write(repo.resolve(m + "/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                      <parent><groupId>com.acme</groupId><artifactId>acme</artifactId><version>2.0.0-SNAPSHOT</version></parent>
                      <artifactId>%s</artifactId></project>
                    """.formatted(m));
        }
        write(repo.resolve("web/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>acme</artifactId><version>2.0.0-SNAPSHOT</version></parent>
                  <artifactId>web</artifactId><packaging>war</packaging>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>lib-a</artifactId><version>${project.version}</version></dependency>
                  </dependencies>
                </project>
                """);
    }

    @Test
    void corporateParent_isDeclaredOnceInRoot_notInEveryModule() {
        corporateParentWithMixedModules();
        migrate(GradleDsl.GROOVY);

        String root = read(repo.resolve("build.gradle"));
        assertThat(root)
                .contains("configure([project(':lib-a'), project(':lib-b')]) {",
                        "// shared configuration from pom.xml (jar modules)",
                        "api platform('com.corp:corp-root:12')", "api 'org.slf4j:slf4j-api:2.0.9'",
                        "configure([project(':web')]) {", "// shared configuration from pom.xml (war modules)",
                        "apply plugin: 'war'", "implementation platform('com.corp:corp-root:12')",
                        "implementation 'org.slf4j:slf4j-api:2.0.9'", "from components.web",
                        "sourceCompatibility = JavaVersion.toVersion('21')");
        for (String m : new String[]{"lib-a", "lib-b", "web"}) {
            assertThat(read(repo.resolve(m + "/build.gradle"))).as(m)
                    .doesNotContain("corp-root", "slf4j", "plugins", "publishing", "JavaVersion");
        }
        assertThat(read(repo.resolve("web/build.gradle"))).contains("implementation project(':lib-a')");
    }

    @Test
    void corporateParent_kotlin() {
        corporateParentWithMixedModules();
        migrate(GradleDsl.KOTLIN);

        assertThat(read(repo.resolve("build.gradle.kts")))
                .contains("\"api\"(platform(\"com.corp:corp-root:12\"))",
                        "\"implementation\"(platform(\"com.corp:corp-root:12\"))", "apply(plugin = \"war\")");
        assertThat(read(repo.resolve("lib-a/build.gradle.kts"))).doesNotContain("corp-root").contains("id(\"java-library\")");
        assertThat(read(repo.resolve("web/build.gradle.kts"))).doesNotContain("corp-root").contains("id(\"war\")");
    }

    private static final String SPRING_TEST = "<dependency><groupId>org.springframework</groupId>"
            + "<artifactId>spring-test</artifactId><version>6.1.2</version><scope>test</scope></dependency>";

    @Test
    void parentDependency_isDeclaredOnceForAllModules() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>a</module><module>b</module></modules>
                  <dependencies>%s</dependencies>
                </project>""".formatted(SPRING_TEST));
        for (String m : new String[]{"a", "b"}) {
            write(repo.resolve(m + "/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                      <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                      <artifactId>%s</artifactId></project>""".formatted(m));
        }
        migrate(GradleDsl.GROOVY);

        assertThat(read(repo.resolve("build.gradle")))
                .contains("subprojects {", "testImplementation 'org.springframework:spring-test:6.1.2'");
        assertThat(read(repo.resolve("a/build.gradle"))).doesNotContain("spring-test");
        assertThat(read(repo.resolve("b/build.gradle"))).doesNotContain("spring-test");
    }

    // pom.xml only aggregates; the modules' <parent> is the sibling parent/pom.xml (via relativePath),
    // which itself has the corporate parent.
    @Test
    void siblingParentModule_isTheModulesParent() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>parent</module><module>a</module><module>b</module></modules>
                </project>""");
        write(repo.resolve("parent/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.corp</groupId><artifactId>corp-root</artifactId><version>12</version></parent>
                  <groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1.0</version><packaging>pom</packaging>
                  <properties><guava.version>33.0.0-jre</guava.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>%s</dependencies>
                </project>""".formatted(SPRING_TEST));
        for (String m : new String[]{"a", "b"}) {
            write(repo.resolve(m + "/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                      <parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1.0</version>
                        <relativePath>../parent</relativePath></parent>
                      <artifactId>%s</artifactId>
                      <dependencies><dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency></dependencies>
                    </project>""".formatted(m));
        }
        MavenToGradleConverter.Conversion c = migrate(GradleDsl.GROOVY);

        assertThat(read(repo.resolve("build.gradle")))
                .contains("configure([project(':a'), project(':b')]) {", "// shared configuration from parent/pom.xml",
                        "api platform('com.corp:corp-root:12')",
                        "api \"com.google.guava:guava:${guavaVersion}\"",
                        "testImplementation 'org.springframework:spring-test:6.1.2'");
        for (String m : new String[]{"a", "b"}) {
            assertThat(read(repo.resolve(m + "/build.gradle"))).as(m)
                    .contains("api 'com.google.guava:guava'")
                    .doesNotContain("spring-test", "acme-parent", "corp-root", "constraints");
        }
        // the parent is still published (as a platform with its managed versions)
        assertThat(read(repo.resolve("parent/build.gradle")))
                .contains("id 'java-platform'", "artifactId = 'acme-parent'", "com.google.guava:guava:${guavaVersion}");
        assertThat(c.warnings()).noneMatch(w -> w.contains("spring-test"));
    }

    @Test
    void nestedAggregator_getsOneBlock() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>services</module></modules>
                  <dependencies>%s</dependencies>
                </project>""".formatted(SPRING_TEST));
        write(repo.resolve("services/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>services</artifactId><packaging>pom</packaging>
                  <modules><module>a</module></modules>
                </project>""");
        write(repo.resolve("services/a/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>services</artifactId><version>1.0</version></parent>
                  <artifactId>a</artifactId></project>""");
        migrate(GradleDsl.GROOVY);

        String root = read(repo.resolve("build.gradle"));
        assertThat(root).containsOnlyOnce("configure([project(':services:a')])").contains("spring-test");
        assertThat(read(repo.resolve("services/a/build.gradle"))).doesNotContain("spring-test");
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
