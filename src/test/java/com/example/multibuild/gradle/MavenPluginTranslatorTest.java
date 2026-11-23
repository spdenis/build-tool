package com.example.multibuild.gradle;

import com.example.multibuild.gradle.MavenPluginTranslator.Execution;
import com.example.multibuild.gradle.MavenPluginTranslator.MavenPlugin;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Maven build plugin → Gradle plugin + configuration, in both DSLs.
class MavenPluginTranslatorTest {

    private static Xpp3Dom xml(String s) {
        try {
            return Xpp3DomBuilder.build(new StringReader("<configuration>" + s + "</configuration>"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MavenPlugin plugin(String group, String artifact, String version, String config, Execution... executions) {
        return new MavenPlugin(group, artifact, version, config == null ? null : xml(config), List.of(executions), Map.of());
    }

    private static Execution exec(String... goals) {
        return new Execution("default", null, List.of(goals), null);
    }

    private static MavenPluginTranslator.Result translate(boolean kts, MavenPlugin... plugins) {
        Map<String, String> props = Map.of("skip.it", "true", "boot.version", "3.3.5");
        return MavenPluginTranslator.translate(List.of(plugins), new MavenPluginTranslator.Context() {
            public boolean kts() { return kts; }
            public String where() { return "pom.xml"; }
            public String resolve(String raw) {
                String v = raw;
                for (Map.Entry<String, String> e : props.entrySet()) v = v.replace("${" + e.getKey() + "}", e.getValue());
                return v.contains("${") ? null : v;
            }
            public boolean junitPlatform() { return true; }
            public String springBootVersion() { return "${boot.version}"; }
            public boolean war() { return false; }
        });
    }

    private static String blocks(MavenPluginTranslator.Result r) {
        return String.join("\n", r.blocks);
    }

    @Test
    void spotbugsAndShade_areThirdPartyPlugins_withTheirArtifacts() {
        MavenPluginTranslator.Result r = translate(true,
                plugin("com.github.spotbugs", "spotbugs-maven-plugin", "4.8.6.2",
                        "<effort>Max</effort><threshold>Low</threshold><excludeFilterFile>spotbugs-exclude.xml</excludeFilterFile>"),
                plugin(null, "maven-shade-plugin", "3.5.0", """
                        <transformers>
                          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>a.Main</mainClass></transformer>
                        </transformers>
                        <relocations><relocation><pattern>com.google</pattern><shadedPattern>shaded.google</shadedPattern></relocation></relocations>""",
                        exec("shade")));
        assertThat(r.thirdParty).containsKeys("com.github.spotbugs", "com.gradleup.shadow");
        assertThat(r.thirdParty.get("com.github.spotbugs").artifact()).isEqualTo("spotbugs-gradle-plugin");
        assertThat(blocks(r)).contains(
                "configure<com.github.spotbugs.snom.SpotBugsExtension> {",
                "effort.set(com.github.spotbugs.snom.Effort.valueOf(\"MAX\"))",
                "reportLevel.set(com.github.spotbugs.snom.Confidence.valueOf(\"LOW\"))",
                "excludeFilter.set(rootProject.file(\"spotbugs-exclude.xml\"))",
                "mergeServiceFiles()", "manifest { attributes(mapOf(\"Main-Class\" to \"a.Main\")) }",
                "relocate(\"com.google\", \"shaded.google\")");
        assertThat(r.warnings).anyMatch(w -> w.contains("-all.jar"));
    }

    @Test
    void springBoot_takesTheBootVersion_andPublishesTheExecutableJar_groovy() {
        MavenPluginTranslator.Result r = translate(false, plugin("org.springframework.boot", "spring-boot-maven-plugin", null,
                "<mainClass>a.App</mainClass>", exec("repackage"), exec("build-info")));
        assertThat(r.thirdParty.get("org.springframework.boot").version()).isEqualTo("${boot.version}");
        assertThat(blocks(r)).contains("springBoot {", "mainClass = 'a.App'", "buildInfo()",
                "publications.named('maven') { artifact tasks.named('bootJar') }");
    }

    @Test
    void qualityAndPackagingPlugins_groovy() {
        MavenPluginTranslator.Result r = translate(false,
                plugin(null, "maven-pmd-plugin", null, "<rulesets><ruleset>config/pmd.xml</ruleset></rulesets><failOnViolation>false</failOnViolation>"),
                plugin(null, "maven-javadoc-plugin", null, "<doclint>none</doclint><failOnError>false</failOnError>", exec("jar")),
                plugin(null, "maven-gpg-plugin", null, null, exec("sign")),
                plugin("org.codehaus.mojo", "build-helper-maven-plugin", null, null,
                        new Execution("gen", "generate-sources", List.of("add-source"),
                                xml("<sources><source>${project.build.directory}/generated-sources/api</source></sources>"))),
                plugin(null, "maven-failsafe-plugin", null, "<skipITs>${skip.it}</skipITs>", exec("integration-test", "verify")),
                plugin(null, "maven-enforcer-plugin", null, null, exec("enforce")),
                plugin("com.github.eirslett", "frontend-maven-plugin", null, null, exec("npm")));
        assertThat(r.corePlugins).containsExactly("pmd", "signing");
        assertThat(blocks(r)).contains(
                "pmd {", "ruleSets = []", "ruleSetFiles = rootProject.files('config/pmd.xml')", "ignoreFailures = true",
                "java { withJavadocJar() }", "failOnError = false", "options.addStringOption('Xdoclint:none', '-quiet')",
                "signing {", "useGpgCmd()", "sign publishing.publications",
                "sourceSets.main.java.srcDir layout.buildDirectory.dir('generated-sources/api')",
                "def integrationTest = tasks.register('integrationTest', Test) {", "enabled = false",
                "testClassesDirs = sourceSets.test.output.classesDirs",
                "// TODO com.github.eirslett:frontend-maven-plugin (goals: npm)");
        assertThat(r.warnings).anyMatch(w -> w.contains("enforcer"))
                .anyMatch(w -> w.contains("frontend-maven-plugin has no generated Gradle equivalent"));
    }

    @Test
    void surefire_includesGroupsAndUnresolvedSettings() {
        MavenPluginTranslator.Result r = translate(true, plugin(null, "maven-surefire-plugin", null, """
                <includes><include>**/*Spec.java</include></includes>
                <groups>fast,unit</groups><excludedGroups>slow</excludedGroups>
                <forkCount>2</forkCount><argLine>${unknown.agent} -ea</argLine>
                <environmentVariables><TZ>UTC</TZ></environmentVariables>"""));
        assertThat(blocks(r)).contains("tasks.named<Test>(\"test\") {", "include(\"**/*Spec.class\")",
                "useJUnitPlatform { includeTags(\"fast\", \"unit\"); excludeTags(\"slow\") }", "maxParallelForks = 2",
                "jvmArgs(\"-ea\")", "environment(\"TZ\", \"UTC\")");
        assertThat(r.warnings).anyMatch(w -> w.contains("${unknown.agent}"));
    }

    @Test
    void noOpPlugins_produceNothing() {
        MavenPluginTranslator.Result r = translate(true, plugin(null, "maven-deploy-plugin", null, null),
                plugin(null, "maven-install-plugin", null, null), plugin(null, "maven-compiler-plugin", null, "<release>17</release>"),
                plugin(null, "maven-source-plugin", null, null));
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.warnings).isEmpty();
    }
}
