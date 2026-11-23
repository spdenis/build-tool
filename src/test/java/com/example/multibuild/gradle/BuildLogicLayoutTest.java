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

// The default build-logic layout: parent poms become convention plugins in an included build-logic build,
// dependency versions live in gradle/libs.versions.toml, publishing (distributionManagement) is defined once
// and Lombok comes from the base convention.
class BuildLogicLayoutTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private final MavenToGradleConverter converter = new MavenToGradleConverter();

    @BeforeEach
    void setUp() {
        repo = tempDir.resolve("repo");
        ReflectionTestUtils.setField(converter, "localRepository", tempDir.resolve("m2").toString());
    }

    private void migrate(GradleDsl dsl) throws IOException {
        migrate(dsl, null);
    }

    // dumpName: copy the result to ${buildLogicDump}/<dumpName> (to build it with a real Gradle).
    private void migrate(GradleDsl dsl, String dumpName) throws IOException {
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        RepositoryProject maven = mavenParser.parse(repo);
        MavenToGradleConverter.Conversion c = converter.convert(repo, dsl);
        converter.apply(c);
        assertThat(GradleMigrationService.verify(maven, new GradleParser().parse(repo), c)).isEmpty();
        String dump = System.getProperty("buildLogicDump");
        if (dump != null && dumpName != null) {
            Path target = Path.of(dump).resolve(dumpName);
            FileSystemUtils.deleteRecursively(target);
            FileSystemUtils.copyRecursively(repo, target);
        }
    }

    private String file(String path) {
        return read(repo.resolve(path));
    }

    // root: parent of every module, with plugins, managed versions, a shared test dependency and the
    // distributionManagement; bom: in-repo BOM; core: Lombok; app: Spring Boot; web: war.
    private void multiModuleRepo() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.shop</groupId><artifactId>shop-parent</artifactId><version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>bom</module><module>core</module><module>app</module><module>web</module></modules>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <guava.version>33.2.1-jre</guava.version>
                    <junit.version>5.10.2</junit.version>
                    <lombok.version>1.18.46</lombok.version>
                    <spring-boot.version>4.0.9</spring-boot.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>${junit.version}</version>
                      <type>pom</type><scope>import</scope></dependency>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
                  </dependencies>
                  <build>
                    <pluginManagement><plugins>
                      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId>
                        <version>${spring-boot.version}</version></plugin>
                    </plugins></pluginManagement>
                    <plugins>
                      <plugin><artifactId>maven-compiler-plugin</artifactId><configuration><parameters>true</parameters></configuration></plugin>
                      <plugin><artifactId>maven-surefire-plugin</artifactId><configuration>
                        <argLine>@{argLine} -Xmx512m</argLine>
                        <systemPropertyVariables><env>test</env></systemPropertyVariables>
                      </configuration></plugin>
                      <plugin><artifactId>maven-failsafe-plugin</artifactId>
                        <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions></plugin>
                      <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version>
                        <executions><execution><id>prepare</id><goals><goal>prepare-agent</goal></goals></execution>
                          <execution><id>report</id><goals><goal>report</goal></goals></execution></executions></plugin>
                      <plugin><artifactId>maven-source-plugin</artifactId>
                        <executions><execution><goals><goal>jar-no-fork</goal></goals></execution></executions></plugin>
                      <plugin><artifactId>maven-antrun-plugin</artifactId>
                        <executions><execution><goals><goal>run</goal></goals></execution></executions></plugin>
                    </plugins>
                  </build>
                  <distributionManagement>
                    <repository><id>acme-releases</id><url>https://artifactory.acme.com/libs-release</url></repository>
                    <snapshotRepository><id>acme-snapshots</id><url>https://artifactory.acme.com/libs-snapshot</url></snapshotRepository>
                  </distributionManagement>
                </project>""");
        write(repo.resolve("bom/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.shop</groupId><artifactId>shop-bom</artifactId><version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.acme.shop</groupId><artifactId>core</artifactId><version>1.0.0-SNAPSHOT</version></dependency>
                  </dependencies></dependencyManagement>
                </project>""");
        write(repo.resolve("core/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme.shop</groupId><artifactId>shop-parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><scope>provided</scope></dependency>
                  </dependencies>
                </project>""");
        write(repo.resolve("app/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme.shop</groupId><artifactId>shop-parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>shop-app</artifactId>
                  <dependencies>
                    <dependency><groupId>com.acme.shop</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId>
                      <configuration><mainClass>com.acme.shop.App</mainClass></configuration></plugin>
                  </plugins></build>
                </project>""");
        write(repo.resolve("web/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme.shop</groupId><artifactId>shop-parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>web</artifactId><packaging>war</packaging>
                  <dependencies>
                    <dependency><groupId>com.acme.shop</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId><version>6.0.0</version><scope>provided</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin><artifactId>maven-war-plugin</artifactId>
                    <configuration><warName>shop</warName><failOnMissingWebXml>false</failOnMissingWebXml></configuration></plugin></plugins></build>
                </project>""");
        // sources so that a real Gradle run compiles Lombok code and runs a test in every module
        write(repo.resolve("core/src/main/java/com/acme/shop/Item.java"), """
                package com.acme.shop;
                @lombok.Value @lombok.Builder
                public class Item { String name; int price; }""");
        write(repo.resolve("core/src/test/java/com/acme/shop/ItemTest.java"), """
                package com.acme.shop;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ItemTest {
                    @lombok.Data static class Probe { String value; }
                    @Test void builder() {
                        assertEquals("test", System.getProperty("env"));
                        assertEquals(3, Item.builder().name("a").price(3).build().getPrice());
                        Probe p = new Probe(); p.setValue("x"); assertEquals("x", p.getValue());
                    }
                }""");
        write(repo.resolve("app/src/main/java/com/acme/shop/App.java"), """
                package com.acme.shop;
                public class App { public static void main(String[] args) { System.out.println(Item.builder().name("x").build()); } }""");
        write(repo.resolve("web/src/main/java/com/acme/shop/web/Hello.java"), """
                package com.acme.shop.web;
                public class Hello extends jakarta.servlet.http.HttpServlet { }""");
    }

    @Test
    void parentPom_becomesConventionPlugin_versionsGoToCatalog_kotlin() throws IOException {
        multiModuleRepo();
        migrate(GradleDsl.KOTLIN, "kotlin");

        // settings: convention plugins from the included build-logic build, repositories in one place
        assertThat(file("settings.gradle.kts")).contains(
                "pluginManagement {", "includeBuild(\"build-logic\")", "dependencyResolutionManagement {",
                "mavenCentral()", "rootProject.name = \"shop-parent\"", "include(\"core\")");

        // rule 1: the parent's managed versions (and their properties) form the version catalog
        String toml = file("gradle/libs.versions.toml");
        assertThat(toml).contains("[versions]", "guava = \"33.2.1-jre\"", "lombok = \"1.18.46\"", "spring-boot = \"4.0.9\"",
                "guava = { module = \"com.google.guava:guava\", version.ref = \"guava\" }",
                "junit-bom = { module = \"org.junit:junit-bom\", version.ref = \"junit\" }",
                "lombok = { module = \"org.projectlombok:lombok\", version.ref = \"lombok\" }",
                "spring-boot-gradle-plugin = { module = \"org.springframework.boot:spring-boot-gradle-plugin\", version.ref = \"spring-boot\" }");
        assertThat(file("gradle.properties")).contains("group=com.acme.shop", "version=1.0.0-SNAPSHOT")
                .doesNotContain("guavaVersion");

        // rule 2: build-logic with the parent's convention, its plugins translated
        assertThat(file("build-logic/settings.gradle.kts")).contains("from(files(\"../gradle/libs.versions.toml\"))");
        assertThat(file("build-logic/build.gradle.kts")).contains("`kotlin-dsl`",
                "implementation(libs.spring.boot.gradle.plugin)");
        String parent = file("build-logic/src/main/kotlin/shop.shop-parent-conventions.gradle.kts");
        assertThat(parent).contains(
                "id(\"shop.java-conventions\")", "id(\"jacoco\")",
                "val libs = the<VersionCatalogsExtension>().named(\"libs\")",
                "add(\"api\", libs.findLibrary(\"guava\").get())",
                "\"api\"(platform(libs.findLibrary(\"junit-bom\").get()))",
                "\"testImplementation\"(libs.findLibrary(\"junit-jupiter\").get())",
                "sourceCompatibility = JavaVersion.toVersion(\"17\")",
                "options.compilerArgs.addAll(listOf(\"-parameters\"))",
                "tasks.named<Test>(\"test\") {", "jvmArgs(\"-Xmx512m\")", "systemProperty(\"env\", \"test\")",
                "val integrationTest = tasks.register<Test>(\"integrationTest\")",
                "tasks.named<JacocoReport>(\"jacocoTestReport\")", "toolVersion = \"0.8.12\"",
                "configure<JavaPluginExtension> { withSourcesJar() }",
                "// TODO org.apache.maven.plugins:maven-antrun-plugin (goals: run)");

        // rule 5: Lombok lives in the base convention only
        String base = file("build-logic/src/main/kotlin/shop.java-conventions.gradle.kts");
        assertThat(base).contains("id(\"java-library\")", "id(\"shop.publishing-conventions\")",
                "\"compileOnly\"(libs.findLibrary(\"lombok\").get())",
                "\"annotationProcessor\"(libs.findLibrary(\"lombok\").get())",
                "\"testCompileOnly\"(libs.findLibrary(\"lombok\").get())",
                "\"testAnnotationProcessor\"(libs.findLibrary(\"lombok\").get())");
        assertThat(file("core/build.gradle.kts")).contains("id(\"shop.shop-parent-conventions\")", "api(libs.guava)")
                .doesNotContain("lombok", "version");

        // rule 4: distributionManagement once, in the publishing convention
        String publishing = file("build-logic/src/main/kotlin/shop.publishing-conventions.gradle.kts");
        assertThat(publishing).contains("id(\"maven-publish\")", "create<MavenPublication>(\"maven\")",
                "https://artifactory.acme.com/libs-snapshot", "https://artifactory.acme.com/libs-release",
                "credentials(PasswordCredentials::class)");
        for (String module : List.of("core", "app", "web", "bom")) {
            assertThat(file(module + "/build.gradle.kts")).doesNotContain("artifactory", "repositories");
        }

        // a third-party plugin used by one module: a feature convention on the build-logic classpath
        assertThat(file("app/build.gradle.kts")).contains("id(\"shop.shop-parent-conventions\")",
                "id(\"shop.spring-boot-conventions\")", "api(project(\":core\"))",
                "configure<org.springframework.boot.gradle.dsl.SpringBootExtension>", "mainClass.set(\"com.acme.shop.App\")",
                "artifact(tasks.named(\"bootJar\"))",
                "publishing.publications.named<MavenPublication>(\"maven\") { artifactId = \"shop-app\" }");
        assertThat(file("build-logic/src/main/kotlin/shop.spring-boot-conventions.gradle.kts"))
                .contains("id(\"org.springframework.boot\")");
        assertThat(file("web/build.gradle.kts")).contains("id(\"war\")", "providedCompile(libs.jakarta.servlet.api)",
                "tasks.named<War>(\"war\")", "archiveFileName.set(\"shop.war\")");
        // the in-repo BOM stays a java-platform (other repos may import it)
        assertThat(file("bom/build.gradle.kts")).contains("id(\"java-platform\")", "id(\"shop.publishing-conventions\")",
                "constraints {", "api(project(\":core\"))");
        assertThat(file("build.gradle.kts")).doesNotContain("subprojects", "allprojects", "configure(");
    }

    @Test
    void parentPom_becomesConventionPlugin_groovy() throws IOException {
        multiModuleRepo();
        migrate(GradleDsl.GROOVY, "groovy");

        assertThat(file("settings.gradle")).contains("includeBuild 'build-logic'");
        assertThat(file("build-logic/build.gradle")).contains("id 'groovy-gradle-plugin'",
                "implementation libs.spring.boot.gradle.plugin");
        assertThat(file("build-logic/settings.gradle")).contains("from(files('../gradle/libs.versions.toml'))");
        assertThat(file("build-logic/src/main/groovy/shop.java-conventions.gradle")).contains(
                "def libs = extensions.getByType(VersionCatalogsExtension).named('libs')",
                "compileOnly libs.findLibrary('lombok').get()", "annotationProcessor libs.findLibrary('lombok').get()");
        assertThat(file("build-logic/src/main/groovy/shop.shop-parent-conventions.gradle")).contains(
                "id 'shop.java-conventions'", "api libs.findLibrary('guava').get()",
                "def integrationTest = tasks.register('integrationTest', Test)");
        assertThat(file("build-logic/src/main/groovy/shop.publishing-conventions.gradle")).contains(
                "maven(MavenPublication) { publication ->", "https://artifactory.acme.com/libs-release");
        assertThat(file("core/build.gradle")).contains("id 'shop.shop-parent-conventions'", "api libs.guava")
                .doesNotContain("lombok");
        assertThat(file("app/build.gradle")).contains("id 'shop.spring-boot-conventions'", "springBoot {",
                "mainClass = 'com.acme.shop.App'");
    }

    @Test
    void singleModule_getsBaseConvention_andItsPluginsInTheModule() throws IOException {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>tool</artifactId><version>2.0</version>
                  <dependencies>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.46</version><scope>provided</scope></dependency>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.2.1-jre</version></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><artifactId>maven-jar-plugin</artifactId><configuration><archive><manifest>
                      <mainClass>org.example.Tool</mainClass></manifest></archive></configuration></plugin>
                    <plugin><artifactId>maven-checkstyle-plugin</artifactId><configuration>
                      <configLocation>config/checkstyle.xml</configLocation><failOnViolation>false</failOnViolation></configuration></plugin>
                  </plugins></build>
                </project>""");
        migrate(GradleDsl.KOTLIN);

        assertThat(file("build.gradle.kts")).contains("id(\"example.java-conventions\")", "id(\"checkstyle\")",
                "api(libs.guava)", "tasks.named<Jar>(\"jar\")", "attributes(mapOf(\"Main-Class\" to \"org.example.Tool\"))",
                "configure<CheckstyleExtension>", "configFile = rootProject.file(\"config/checkstyle.xml\")",
                "isIgnoreFailures = true").doesNotContain("lombok", "repositories");
        assertThat(file("gradle/libs.versions.toml")).contains(
                "guava = { module = \"com.google.guava:guava\", version = \"33.2.1-jre\" }",
                "lombok = { module = \"org.projectlombok:lombok\", version = \"1.18.46\" }");
        assertThat(file("settings.gradle.kts")).contains("includeBuild(\"build-logic\")").doesNotContain("include(\"");
        assertThat(Files.exists(repo.resolve("build-logic/src/main/kotlin/example.publishing-conventions.gradle.kts"))).isTrue();
    }

    @Test
    void lombokWithoutVersion_getsTheConfiguredDefault_andConventionPrefixIsConfigurable() throws IOException {
        ReflectionTestUtils.setField(converter, "conventionPrefix", "corp");
        ReflectionTestUtils.setField(converter, "pluginRepository", "https://artifactory.acme.com/gradle-plugins");
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>tool</artifactId><version>2.0</version>
                </project>""");
        migrate(GradleDsl.KOTLIN);

        assertThat(file("build.gradle.kts")).contains("id(\"corp.java-conventions\")");
        assertThat(file("gradle/libs.versions.toml")).contains("lombok = { module = \"org.projectlombok:lombok\", version = \"1.18.48\" }");
        assertThat(file("build-logic/settings.gradle.kts")).contains(
                "maven { url = uri(\"https://artifactory.acme.com/gradle-plugins\") }").doesNotContain("gradlePluginPortal()");
    }

    // Third-party and core quality/packaging plugins in one module (built with a real Gradle via the dump).
    private void toolPluginsRepo() {
        write(repo.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>tool</artifactId><version>2.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.2.1-jre</version></dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin><groupId>com.github.spotbugs</groupId><artifactId>spotbugs-maven-plugin</artifactId><version>4.8.6.2</version>
                      <configuration><effort>Max</effort><threshold>Low</threshold><failOnError>false</failOnError></configuration></plugin>
                    <plugin><artifactId>maven-shade-plugin</artifactId><executions><execution><goals><goal>shade</goal></goals>
                      <configuration><transformers>
                        <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                        <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>org.example.Tool</mainClass></transformer>
                      </transformers></configuration></execution></executions></plugin>
                    <plugin><artifactId>maven-pmd-plugin</artifactId><configuration><failOnViolation>false</failOnViolation></configuration></plugin>
                    <plugin><artifactId>maven-javadoc-plugin</artifactId><configuration><doclint>none</doclint></configuration>
                      <executions><execution><goals><goal>jar</goal></goals></execution></executions></plugin>
                    <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId>
                      <executions><execution><goals><goal>add-source</goal></goals>
                        <configuration><sources><source>src/extra/java</source></sources></configuration></execution></executions></plugin>
                    <plugin><artifactId>maven-gpg-plugin</artifactId><executions><execution><goals><goal>sign</goal></goals></execution></executions></plugin>
                  </plugins></build>
                </project>""");
        write(repo.resolve("src/main/java/org/example/Tool.java"), """
                package org.example;
                /** Tool. */
                public class Tool { public static void main(String[] a) { System.out.println(Extra.NAME + com.google.common.base.Strings.repeat("!", 1)); } }""");
        write(repo.resolve("src/extra/java/org/example/Extra.java"), """
                package org.example;
                class Extra { static final String NAME = "extra"; }""");
    }

    @Test
    void toolPlugins_kotlin() throws IOException {
        toolPluginsRepo();
        migrate(GradleDsl.KOTLIN, "tools-kotlin");
        assertThat(file("build.gradle.kts")).contains("id(\"example.spotbugs-conventions\")", "id(\"example.shadow-conventions\")",
                "id(\"pmd\")", "id(\"signing\")", "project.the<SourceSetContainer>()[\"main\"].java.srcDir(file(\"src/extra/java\"))");
        assertThat(file("build-logic/build.gradle.kts")).contains("implementation(libs.spotbugs.gradle.plugin)",
                "implementation(libs.shadow.gradle.plugin)");
        assertThat(file("gradle/libs.versions.toml")).contains(
                "spotbugs-gradle-plugin = { module = \"com.github.spotbugs.snom:spotbugs-gradle-plugin\", version = \"6.0.28\" }",
                "shadow-gradle-plugin = { module = \"com.gradleup.shadow:shadow-gradle-plugin\", version = \"8.3.11\" }");
    }

    @Test
    void toolPlugins_groovy() throws IOException {
        toolPluginsRepo();
        migrate(GradleDsl.GROOVY, "tools-groovy");
        assertThat(file("build.gradle")).contains("id 'example.spotbugs-conventions'", "spotbugs {", "tasks.named('shadowJar') {",
                "sourceSets.main.java.srcDir file('src/extra/java')");
    }
}
