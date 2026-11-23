package com.example.multibuild.gradle;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Translates Maven build plugins into Gradle plugins and their configuration.
//
// Core Gradle plugins (jacoco, checkstyle, pmd, signing) are applied by id; third-party Gradle plugins
// (Spring Boot, Shadow, SpotBugs) are reported with the plugin artifact build-logic needs on its classpath.
// The generated configuration avoids type-safe accessors (configure<T>, tasks.named<T>) so it compiles both
// in module build scripts and in precompiled convention plugins. maven-compiler-plugin is handled by the
// converter itself (Java version, annotation processing, compiler arguments).
final class MavenPluginTranslator {

    static final String SPRING_BOOT = "org.springframework.boot";
    static final String SHADOW = "com.gradleup.shadow";
    static final String SPOTBUGS = "com.github.spotbugs";

    // Gradle plugin artifacts for the third-party plugins and the versions used when the pom gives none.
    static final Map<String, String[]> THIRD_PARTY = Map.of(
            SPRING_BOOT, new String[]{"org.springframework.boot", "spring-boot-gradle-plugin", null},
            SHADOW, new String[]{"com.gradleup.shadow", "shadow-gradle-plugin", "8.3.11"},
            SPOTBUGS, new String[]{"com.github.spotbugs.snom", "spotbugs-gradle-plugin", "6.0.28"});

    private static final Set<String> NO_OP = Set.of(
            "maven-install-plugin", "maven-deploy-plugin", "maven-clean-plugin", "maven-site-plugin",
            "maven-release-plugin", "versions-maven-plugin", "maven-help-plugin", "flatten-maven-plugin",
            "maven-project-info-reports-plugin", "maven-compiler-plugin", "nexus-staging-maven-plugin");

    record Execution(String id, String phase, List<String> goals, Xpp3Dom config) {}

    // config is the effective plugin configuration (pluginManagement merged in); dependencies maps the
    // plugin's own dependencies groupId:artifactId → version.
    record MavenPlugin(String groupId, String artifactId, String version, Xpp3Dom config,
                       List<Execution> executions, Map<String, String> dependencies) {
        String key() { return (groupId == null ? "org.apache.maven.plugins" : groupId) + ":" + artifactId; }

        boolean hasGoal(String... goals) {
            Set<String> wanted = Set.of(goals);
            return executions.stream().anyMatch(e -> e.goals().stream().anyMatch(wanted::contains));
        }

        // Configuration of the plugin merged with that of the executions running one of the goals.
        Xpp3Dom configFor(String... goals) {
            Xpp3Dom result = config != null ? new Xpp3Dom(config) : new Xpp3Dom("configuration");
            Set<String> wanted = Set.of(goals);
            for (Execution e : executions) {
                if (e.config() != null && e.goals().stream().anyMatch(wanted::contains)) {
                    result = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(e.config()), result);
                }
            }
            return result;
        }
    }

    // A third-party Gradle plugin and the artifact build-logic depends on; version is the Maven expression
    // ("3.2.5", "${spring-boot.version}") or null for the default.
    record ThirdParty(String pluginId, String group, String artifact, String version) {}

    interface Context {
        boolean kts();
        String where();
        // Expands ${...} placeholders; null when one cannot be resolved.
        String resolve(String raw);
        boolean junitPlatform();
        // Maven expression of the Spring Boot version (parent, BOM or plugin), or null.
        String springBootVersion();
        boolean war();
    }

    static final class Result {
        final Set<String> corePlugins = new LinkedHashSet<>();
        final Map<String, ThirdParty> thirdParty = new LinkedHashMap<>();
        final List<String> blocks = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        boolean isEmpty() {
            return corePlugins.isEmpty() && thirdParty.isEmpty() && blocks.isEmpty();
        }
    }

    private final Context ctx;
    private final boolean kts;
    private final Result result = new Result();

    private MavenPluginTranslator(Context ctx) {
        this.ctx = ctx;
        this.kts = ctx.kts();
    }

    static Result translate(List<MavenPlugin> plugins, Context ctx) {
        MavenPluginTranslator t = new MavenPluginTranslator(ctx);
        for (MavenPlugin p : plugins) t.translate(p);
        return t.result;
    }

    private void translate(MavenPlugin p) {
        String a = p.artifactId();
        if (a == null || NO_OP.contains(a)) return;
        switch (a) {
            case "maven-surefire-plugin" -> surefire(p);
            case "maven-failsafe-plugin" -> failsafe(p);
            case "maven-jar-plugin" -> jar(p);
            case "maven-source-plugin" -> sources(p);
            case "maven-javadoc-plugin" -> javadoc(p);
            case "maven-war-plugin" -> war(p);
            case "maven-resources-plugin" -> resources(p);
            case "jacoco-maven-plugin" -> jacoco(p);
            case "maven-checkstyle-plugin" -> checkstyle(p);
            case "maven-pmd-plugin" -> pmd(p);
            case "spotbugs-maven-plugin" -> spotbugs(p);
            case "maven-shade-plugin" -> shade(p);
            case "spring-boot-maven-plugin" -> springBoot(p);
            case "maven-gpg-plugin" -> gpg(p);
            case "build-helper-maven-plugin" -> buildHelper(p);
            case "maven-enforcer-plugin" -> warn(p, "enforcer rules have no Gradle equivalent and were not migrated");
            default -> todo(p);
        }
    }

    // ── test plugins ──────────────────────────────────────────────────────

    private void surefire(MavenPlugin p) {
        Xpp3Dom cfg = p.configFor("test");
        List<String> lines = new ArrayList<>();
        if (isTrue(cfg, "skipTests") || isTrue(cfg, "skip")) lines.add("enabled = false");
        lines.addAll(testSettings(p, cfg));
        List<String> includes = patterns(cfg, "includes", "include");
        if (!includes.isEmpty()) lines.add(call("include", includes));
        List<String> excludes = patterns(cfg, "excludes", "exclude");
        if (!excludes.isEmpty()) lines.add(call("exclude", excludes));
        if (lines.isEmpty()) return;
        block(kts ? "tasks.named<Test>(\"test\")" : "tasks.named('test', Test)", lines,
                "maven-surefire-plugin");
    }

    private void failsafe(MavenPlugin p) {
        if (!p.hasGoal("integration-test") && p.executions().isEmpty()) {
            warn(p, "declared without the integration-test goal — integrationTest task not generated");
            return;
        }
        Xpp3Dom cfg = p.configFor("integration-test", "verify");
        List<String> includes = patterns(cfg, "includes", "include");
        if (includes.isEmpty()) includes = List.of("**/IT*.class", "**/*IT.class", "**/*ITCase.class");
        List<String> lines = new ArrayList<>();
        lines.add("description = " + q("Runs the integration tests (maven-failsafe-plugin)."));
        lines.add("group = " + q("verification"));
        if (kts) {
            lines.add("val test = project.the<SourceSetContainer>()[\"test\"]");
            lines.add("testClassesDirs = test.output.classesDirs");
            lines.add("classpath = test.runtimeClasspath");
        } else {
            lines.add("testClassesDirs = sourceSets.test.output.classesDirs");
            lines.add("classpath = sourceSets.test.runtimeClasspath");
        }
        lines.add(call("include", includes));
        lines.add(kts ? "shouldRunAfter(\"test\")" : "shouldRunAfter 'test'");
        if (ctx.junitPlatform()) lines.add("useJUnitPlatform()");
        if (isTrue(cfg, "skipITs") || isTrue(cfg, "skipTests") || isTrue(cfg, "skip")) lines.add("enabled = false");
        lines.addAll(testSettings(p, cfg));
        StringBuilder sb = new StringBuilder("// maven-failsafe-plugin: integration tests run after the unit tests, on `check`\n");
        sb.append(kts ? "val integrationTest = tasks.register<Test>(\"integrationTest\") {\n"
                : "def integrationTest = tasks.register('integrationTest', Test) {\n");
        for (String l : lines) sb.append("    ").append(l).append('\n');
        sb.append("}\n");
        sb.append(kts ? "tasks.named<Test>(\"test\") {\n" : "tasks.named('test', Test) {\n");
        sb.append("    ").append(call("exclude", includes)).append('\n').append("}\n");
        sb.append(kts ? "tasks.named(\"check\") { dependsOn(integrationTest) }" : "tasks.named('check') { dependsOn integrationTest }");
        result.blocks.add(sb.toString());
    }

    // argLine, system properties, environment, forks, groups — shared by surefire and failsafe.
    private List<String> testSettings(MavenPlugin p, Xpp3Dom cfg) {
        List<String> lines = new ArrayList<>();
        Xpp3Dom argNode = cfg.getChild("argLine");     // resolved per token: one unknown property drops only itself
        String argLine = argNode == null || argNode.getValue() == null ? null : argNode.getValue().trim();
        if (argLine != null) {
            // @{argLine} / ${argLine} hand over the JaCoCo agent in Maven; Gradle's jacoco plugin does that itself.
            String cleaned = argLine.replaceAll("[@$]\\{argLine}", "").trim();
            List<String> args = cleaned.isEmpty() ? List.of() : Arrays.asList(cleaned.split("\\s+"));
            List<String> resolved = new ArrayList<>();
            for (String arg : args) {
                String r = ctx.resolve(arg);
                if (r == null) warn(p, "argLine token '" + arg + "' could not be resolved — dropped");
                else resolved.add(r);
            }
            if (!resolved.isEmpty()) lines.add(call("jvmArgs", resolved));
        }
        for (Map.Entry<String, String> e : children(cfg, "systemPropertyVariables").entrySet()) {
            lines.add(kts ? "systemProperty(" + q(e.getKey()) + ", " + q(e.getValue()) + ")"
                    : "systemProperty " + q(e.getKey()) + ", " + q(e.getValue()));
        }
        for (Map.Entry<String, String> e : children(cfg, "environmentVariables").entrySet()) {
            lines.add(kts ? "environment(" + q(e.getKey()) + ", " + q(e.getValue()) + ")"
                    : "environment " + q(e.getKey()) + ", " + q(e.getValue()));
        }
        String forks = value(cfg, "forkCount");
        if (forks != null && forks.matches("\\d+") && Integer.parseInt(forks) > 1) {
            lines.add("maxParallelForks = " + forks);
        } else if (forks != null && forks.matches("\\d+(\\.\\d+)?C")) {
            lines.add("maxParallelForks = Runtime.getRuntime().availableProcessors()");
        }
        if (isTrue(cfg, "testFailureIgnore")) lines.add("ignoreFailures = true");
        List<String> groups = csv(value(cfg, "groups"));
        List<String> excluded = csv(value(cfg, "excludedGroups"));
        if (!groups.isEmpty() || !excluded.isEmpty()) {
            List<String> inner = new ArrayList<>();
            String include = ctx.junitPlatform() ? "includeTags" : "includeCategories";
            String exclude = ctx.junitPlatform() ? "excludeTags" : "excludeCategories";
            if (!groups.isEmpty()) inner.add(call(include, groups));
            if (!excluded.isEmpty()) inner.add(call(exclude, excluded));
            lines.add((ctx.junitPlatform() ? "useJUnitPlatform" : "useJUnit") + " { " + String.join("; ", inner) + " }");
        }
        return lines;
    }

    // ── packaging plugins ─────────────────────────────────────────────────

    private void jar(MavenPlugin p) {
        Xpp3Dom cfg = p.configFor("jar");
        Map<String, String> attributes = manifest(p, cfg);
        if (!attributes.isEmpty()) {
            block(kts ? "tasks.named<Jar>(\"jar\")" : "tasks.named('jar', Jar)",
                    List.of(manifestLine(attributes)), "maven-jar-plugin");
        }
        if (p.hasGoal("test-jar")) {
            warn(p, "test-jar goal not migrated — use the java-test-fixtures plugin to share test code");
        }
    }

    private Map<String, String> manifest(MavenPlugin p, Xpp3Dom cfg) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Xpp3Dom archive = cfg.getChild("archive");
        if (archive == null) return attributes;
        Xpp3Dom manifest = archive.getChild("manifest");
        if (manifest != null) {
            String main = value(manifest, "mainClass");
            if (main != null) attributes.put("Main-Class", main);
            if (isTrue(manifest, "addClasspath")) warn(p, "manifest Class-Path (addClasspath) not generated");
            if (isTrue(manifest, "addDefaultImplementationEntries")) {
                attributes.put("Implementation-Title", "${project.name}");
                attributes.put("Implementation-Version", "${project.version}");
            }
        }
        attributes.putAll(children(archive, "manifestEntries"));
        return attributes;
    }

    private String manifestLine(Map<String, String> attributes) {
        List<String> pairs = new ArrayList<>();
        attributes.forEach((k, v) -> {
            String value = v.startsWith("${project.") ? (kts ? "project." + v.substring(10, v.length() - 1)
                    : "project." + v.substring(10, v.length() - 1)) : q(v);
            pairs.add(kts ? q(k) + " to " + value : q(k) + ": " + value);
        });
        return kts ? "manifest { attributes(mapOf(" + String.join(", ", pairs) + ")) }"
                : "manifest { attributes(" + String.join(", ", pairs) + ") }";
    }

    private void sources(MavenPlugin p) {
        if (!p.hasGoal("jar", "jar-no-fork", "test-jar", "test-jar-no-fork")) return;
        result.blocks.add("// maven-source-plugin: publish a -sources.jar\n"
                + (kts ? "configure<JavaPluginExtension> { withSourcesJar() }" : "java { withSourcesJar() }"));
    }

    private void javadoc(MavenPlugin p) {
        Xpp3Dom cfg = p.configFor("jar", "javadoc");
        List<String> lines = new ArrayList<>();
        String doclint = value(cfg, "doclint");
        String additional = value(cfg, "additionalparam");
        boolean noLint = "none".equals(doclint) || (additional != null && additional.contains("-Xdoclint:none"));
        if ("false".equals(value(cfg, "failOnError"))) lines.add(kts ? "isFailOnError = false" : "failOnError = false");
        if (noLint) {
            lines.add(kts ? "(options as StandardJavadocDocletOptions).addStringOption(\"Xdoclint:none\", \"-quiet\")"
                    : "options.addStringOption('Xdoclint:none', '-quiet')");
        }
        StringBuilder sb = new StringBuilder();
        if (p.hasGoal("jar", "aggregate-jar")) {
            sb.append("// maven-javadoc-plugin: publish a -javadoc.jar\n")
                    .append(kts ? "configure<JavaPluginExtension> { withJavadocJar() }" : "java { withJavadocJar() }");
        }
        if (!lines.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(kts ? "tasks.withType<Javadoc>().configureEach {\n" : "tasks.withType(Javadoc).configureEach {\n");
            for (String l : lines) sb.append("    ").append(l).append('\n');
            sb.append('}');
        }
        if (sb.length() > 0) result.blocks.add(sb.toString());
    }

    private void war(MavenPlugin p) {
        Xpp3Dom cfg = p.configFor("war");
        List<String> lines = new ArrayList<>();
        String name = value(cfg, "warName");
        if (name != null) lines.add(kts ? "archiveFileName.set(" + q(name + ".war") + ")" : "archiveFileName = " + q(name + ".war"));
        String webapp = path(p, value(cfg, "warSourceDirectory"));
        if (webapp != null) lines.add(kts ? "webAppDirectory.set(file(" + q(webapp) + "))" : "webAppDirectory = file(" + q(webapp) + ")");
        List<String> excludes = csv(value(cfg, "packagingExcludes"));
        if (!excludes.isEmpty()) lines.add(call("exclude", excludes));
        Map<String, String> attributes = manifest(p, cfg);
        if (!attributes.isEmpty()) lines.add(manifestLine(attributes));
        if (cfg.getChild("webResources") != null) warn(p, "webResources not migrated — add them to the war task's from(...)");
        if (lines.isEmpty()) return;
        block(kts ? "tasks.named<War>(\"war\")" : "tasks.named('war', War)", lines, "maven-war-plugin");
    }

    private void resources(MavenPlugin p) {
        Xpp3Dom cfg = p.configFor("resources");
        String encoding = value(cfg, "encoding");
        if (encoding == null) return;
        block(kts ? "tasks.withType<ProcessResources>().configureEach" : "tasks.withType(ProcessResources).configureEach",
                List.of("filteringCharset = " + q(encoding)),
                "maven-resources-plugin");
    }

    private void shade(MavenPlugin p) {
        thirdParty(SHADOW, null);
        Xpp3Dom cfg = p.configFor("shade");
        List<String> lines = new ArrayList<>();
        Xpp3Dom transformers = cfg.getChild("transformers");
        if (transformers != null) {
            for (Xpp3Dom t : transformers.getChildren()) {
                String impl = t.getAttribute("implementation");
                if (impl == null) continue;
                if (impl.endsWith("ServicesResourceTransformer")) lines.add("mergeServiceFiles()");
                else if (impl.endsWith("ManifestResourceTransformer")) {
                    Map<String, String> attributes = new LinkedHashMap<>();
                    String main = value(t, "mainClass");
                    if (main != null) attributes.put("Main-Class", main);
                    attributes.putAll(children(t, "manifestEntries"));
                    if (!attributes.isEmpty()) lines.add(manifestLine(attributes));
                } else {
                    warn(p, "transformer " + impl.substring(impl.lastIndexOf('.') + 1) + " not migrated");
                }
            }
        }
        if (isTrue(cfg, "minimizeJar")) lines.add("minimize()");
        Xpp3Dom relocations = cfg.getChild("relocations");
        if (relocations != null) {
            for (Xpp3Dom r : relocations.getChildren()) {
                String from = value(r, "pattern");
                String to = value(r, "shadedPattern");
                if (from != null && to != null) lines.add(kts ? "relocate(" + q(from) + ", " + q(to) + ")" : "relocate " + q(from) + ", " + q(to));
            }
        }
        if (!isTrue(cfg, "shadedArtifactAttached")) {
            warn(p, "the shaded jar replaced the main artifact in Maven; Gradle builds it as the separate -all.jar (shadowJar)");
        }
        block(kts ? "tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>(\"shadowJar\")"
                : "tasks.named('shadowJar')", lines, "maven-shade-plugin → Shadow");
    }

    private void springBoot(MavenPlugin p) {
        String version = p.version() != null ? p.version() : ctx.springBootVersion();
        if (version == null) {
            warn(p, "Spring Boot version unknown — set spring-boot-gradle-plugin's version in gradle/libs.versions.toml");
        }
        thirdParty(SPRING_BOOT, version);
        Xpp3Dom cfg = p.configFor("repackage", "run", "build-info");
        List<String> lines = new ArrayList<>();
        String main = value(cfg, "mainClass");
        if (main == null) main = value(cfg, "start-class");
        if (main != null) lines.add(kts ? "mainClass.set(" + q(main) + ")" : "mainClass = " + q(main));
        if (p.hasGoal("build-info")) lines.add("buildInfo()");
        StringBuilder sb = new StringBuilder();
        if (!lines.isEmpty()) {
            sb.append(kts ? "configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {\n" : "springBoot {\n");
            for (String l : lines) sb.append("    ").append(l).append('\n');
            sb.append("}\n");
        }
        if (isTrue(cfg, "skip")) {
            sb.append(kts ? "tasks.named(\"bootJar\") { enabled = false }\n" : "tasks.named('bootJar') { enabled = false }\n");
        } else if (!ctx.war()) {
            // repackage replaced the main artifact with the executable jar: publish bootJar the same way
            // (the plain jar keeps the 'plain' classifier the Spring Boot plugin gives it).
            sb.append("// spring-boot-maven-plugin repackage: the executable jar is the published main artifact\n");
            sb.append(kts ? "configure<PublishingExtension> {\n    publications.named<MavenPublication>(\"maven\") { artifact(tasks.named(\"bootJar\")) }\n}\n"
                    : "publishing {\n    publications.named('maven') { artifact tasks.named('bootJar') }\n}\n");
        }
        if (cfg.getChild("layers") != null || cfg.getChild("image") != null || p.hasGoal("build-image")) {
            warn(p, "layers / image settings not migrated — configure bootJar / bootBuildImage");
        }
        if (sb.length() > 0) result.blocks.add(sb.toString().stripTrailing());
    }

    // ── quality / coverage ────────────────────────────────────────────────

    private void jacoco(MavenPlugin p) {
        result.corePlugins.add("jacoco");
        StringBuilder sb = new StringBuilder();
        String version = p.version() != null ? ctx.resolve(p.version()) : null;
        if (version != null) {
            sb.append(kts ? "configure<JacocoPluginExtension> { toolVersion = " + q(version) + " }\n"
                    : "jacoco { toolVersion = " + q(version) + " }\n");
        }
        if (p.hasGoal("report") || p.executions().isEmpty()) {
            sb.append(kts ? """
                    tasks.named<JacocoReport>("jacocoTestReport") {
                        reports {
                            xml.required.set(true)
                            html.required.set(true)
                        }
                    }
                    tasks.named("test") { finalizedBy("jacocoTestReport") }
                    """ : """
                    tasks.named('jacocoTestReport', JacocoReport) {
                        reports {
                            xml.required = true
                            html.required = true
                        }
                    }
                    tasks.named('test') { finalizedBy 'jacocoTestReport' }
                    """);
        }
        if (p.hasGoal("check")) {
            List<String> limits = new ArrayList<>();
            Xpp3Dom rules = p.configFor("check").getChild("rules");
            if (rules != null) {
                for (Xpp3Dom rule : rules.getChildren()) {
                    Xpp3Dom ls = rule.getChild("limits");
                    if (ls == null) continue;
                    for (Xpp3Dom limit : ls.getChildren()) {
                        String counter = value(limit, "counter");
                        String val = value(limit, "value");
                        String min = value(limit, "minimum");
                        String max = value(limit, "maximum");
                        List<String> parts = new ArrayList<>();
                        if (counter != null) parts.add("counter = " + q(counter));
                        if (val != null) parts.add("value = " + q(val));
                        if (min != null) parts.add("minimum = " + decimal(min));
                        if (max != null) parts.add("maximum = " + decimal(max));
                        if (!parts.isEmpty()) limits.add("limit { " + String.join("; ", parts) + " }");
                    }
                }
            }
            if (!limits.isEmpty()) {
                sb.append(kts ? "tasks.named<JacocoCoverageVerification>(\"jacocoTestCoverageVerification\") {\n"
                        : "tasks.named('jacocoTestCoverageVerification', JacocoCoverageVerification) {\n");
                sb.append("    violationRules {\n        rule {\n");
                for (String l : limits) sb.append("            ").append(l).append('\n');
                sb.append("        }\n    }\n}\n");
            }
            sb.append(kts ? "tasks.named(\"check\") { dependsOn(\"jacocoTestCoverageVerification\") }\n"
                    : "tasks.named('check') { dependsOn 'jacocoTestCoverageVerification' }\n");
        }
        if (sb.length() > 0) result.blocks.add("// jacoco-maven-plugin\n" + sb.toString().stripTrailing());
    }

    private String decimal(String v) {
        String n = v.endsWith("%") ? String.valueOf(Double.parseDouble(v.substring(0, v.length() - 1)) / 100) : v;
        return kts ? q(n) + ".toBigDecimal()" : n;
    }

    private void checkstyle(MavenPlugin p) {
        result.corePlugins.add("checkstyle");
        Xpp3Dom cfg = p.configFor("check", "checkstyle");
        List<String> lines = new ArrayList<>();
        String tool = p.dependencies().get("com.puppycrawl.tools:checkstyle");
        if (tool != null && ctx.resolve(tool) != null) lines.add("toolVersion = " + q(ctx.resolve(tool)));
        String location = value(cfg, "configLocation");
        if (location != null) {
            String file = path(p, location);
            if (file != null && (file.contains("/") || file.contains("\\") || !file.endsWith("_checks.xml"))) {
                lines.add("configFile = rootProject.file(" + q(file) + ")");
            } else {
                warn(p, "configLocation '" + location + "' is a classpath resource of the Maven plugin — copy it into the repo and set checkstyle.configFile");
            }
        }
        if ("false".equals(value(cfg, "failOnViolation")) || "false".equals(value(cfg, "failsOnError"))) {
            lines.add(kts ? "isIgnoreFailures = true" : "ignoreFailures = true");
        }
        block(kts ? "configure<CheckstyleExtension>" : "checkstyle", lines, "maven-checkstyle-plugin");
    }

    private void pmd(MavenPlugin p) {
        result.corePlugins.add("pmd");
        Xpp3Dom cfg = p.configFor("check", "pmd");
        List<String> lines = new ArrayList<>();
        Xpp3Dom rulesets = cfg.getChild("rulesets");
        List<String> files = new ArrayList<>();
        if (rulesets != null) {
            for (Xpp3Dom r : rulesets.getChildren()) {
                String f = path(p, r.getValue());
                if (f != null && !f.startsWith("/rulesets/") && !f.startsWith("rulesets/")) files.add(f);
            }
        }
        if (!files.isEmpty()) {
            lines.add(kts ? "ruleSets = listOf()" : "ruleSets = []");
            lines.add("ruleSetFiles = rootProject.files(" + String.join(", ", files.stream().map(this::q).toList()) + ")");
        }
        if ("false".equals(value(cfg, "failOnViolation"))) lines.add(kts ? "isIgnoreFailures = true" : "ignoreFailures = true");
        block(kts ? "configure<PmdExtension>" : "pmd", lines, "maven-pmd-plugin");
    }

    private void spotbugs(MavenPlugin p) {
        thirdParty(SPOTBUGS, null);
        Xpp3Dom cfg = p.configFor("check", "spotbugs");
        List<String> lines = new ArrayList<>();
        String effort = value(cfg, "effort");
        if (effort != null) lines.add("effort.set(com.github.spotbugs.snom.Effort.valueOf(" + q(effort.toUpperCase(Locale.ROOT)) + "))");
        String threshold = value(cfg, "threshold");
        if (threshold != null) {
            String level = switch (threshold.toLowerCase(Locale.ROOT)) {
                case "high" -> "HIGH";
                case "low", "exp", "experimental" -> "LOW";
                case "medium" -> "MEDIUM";
                default -> "DEFAULT";
            };
            lines.add("reportLevel.set(com.github.spotbugs.snom.Confidence.valueOf(" + q(level) + "))");
        }
        String exclude = path(p, value(cfg, "excludeFilterFile"));
        if (exclude != null) lines.add("excludeFilter.set(rootProject.file(" + q(exclude) + "))");
        if ("false".equals(value(cfg, "failOnError"))) lines.add("ignoreFailures.set(true)");
        block(kts ? "configure<com.github.spotbugs.snom.SpotBugsExtension>" : "spotbugs", lines, "spotbugs-maven-plugin");
    }

    private void gpg(MavenPlugin p) {
        result.corePlugins.add("signing");
        result.blocks.add("// maven-gpg-plugin: sign the published artifacts with the gpg command\n" + (kts
                ? "configure<SigningExtension> {\n    useGpgCmd()\n    sign(project.the<PublishingExtension>().publications)\n}"
                : "signing {\n    useGpgCmd()\n    sign publishing.publications\n}"));
    }

    private void buildHelper(MavenPlugin p) {
        List<String> lines = new ArrayList<>();
        for (Execution e : p.executions()) {
            for (String goal : e.goals()) {
                String set;
                String kind;
                String child;
                switch (goal) {
                    case "add-source" -> { set = "main"; kind = "java"; child = "sources"; }
                    case "add-test-source" -> { set = "test"; kind = "java"; child = "sources"; }
                    case "add-resource" -> { set = "main"; kind = "resources"; child = "resources"; }
                    case "add-test-resource" -> { set = "test"; kind = "resources"; child = "resources"; }
                    default -> {
                        warn(p, "goal " + goal + " not migrated");
                        continue;
                    }
                }
                Xpp3Dom cfg = e.config() != null ? e.config().getChild(child) : null;
                if (cfg == null) continue;
                for (Xpp3Dom entry : cfg.getChildren()) {
                    String raw = entry.getChild("directory") != null ? entry.getChild("directory").getValue() : entry.getValue();
                    String dir = directory(p, raw);
                    if (dir == null) continue;
                    lines.add(kts ? "project.the<SourceSetContainer>()[\"" + set + "\"]." + kind + ".srcDir(" + dir + ")"
                            : "sourceSets." + set + "." + kind + ".srcDir " + dir);
                }
            }
        }
        if (!lines.isEmpty()) result.blocks.add("// build-helper-maven-plugin: extra source directories\n" + String.join("\n", lines));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void thirdParty(String id, String version) {
        String[] artifact = THIRD_PARTY.get(id);
        result.thirdParty.putIfAbsent(id, new ThirdParty(id, artifact[0], artifact[1], version != null ? version : artifact[2]));
    }

    private void todo(MavenPlugin p) {
        Set<String> goals = new LinkedHashSet<>();
        p.executions().forEach(e -> goals.addAll(e.goals()));
        result.blocks.add("// TODO " + p.key() + (goals.isEmpty() ? "" : " (goals: " + String.join(", ", goals) + ")")
                + " has no generated Gradle equivalent — migrate it by hand");
        result.warnings.add(ctx.where() + ": Maven plugin " + p.key() + " has no generated Gradle equivalent (TODO left in the build script)");
    }

    private void warn(MavenPlugin p, String message) {
        result.warnings.add(ctx.where() + ": " + p.artifactId() + ": " + message);
    }

    private void block(String header, List<String> lines, String comment) {
        if (lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder("// ").append(comment).append('\n').append(header).append(" {\n");
        for (String l : lines) sb.append("    ").append(l).append('\n');
        result.blocks.add(sb.append('}').toString());
    }

    private String call(String method, List<String> args) {
        String joined = String.join(", ", args.stream().map(this::q).toList());
        return kts ? method + "(" + joined + ")" : method + " " + joined;
    }

    String q(String s) {
        if (kts) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String value(Xpp3Dom cfg, String name) {
        Xpp3Dom c = cfg != null ? cfg.getChild(name) : null;
        if (c == null || c.getValue() == null || c.getValue().isBlank()) return null;
        String raw = c.getValue().trim();
        String v = ctx.resolve(raw);
        if (v == null) {
            result.warnings.add(ctx.where() + ": plugin setting <" + name + ">" + raw + "</" + name + "> could not be resolved — not migrated");
        }
        return v;
    }

    private boolean isTrue(Xpp3Dom cfg, String name) {
        Xpp3Dom c = cfg != null ? cfg.getChild(name) : null;
        return c != null && c.getValue() != null && "true".equals(ctx.resolve(c.getValue().trim()));
    }

    private Map<String, String> children(Xpp3Dom cfg, String name) {
        Map<String, String> result = new LinkedHashMap<>();
        Xpp3Dom c = cfg != null ? cfg.getChild(name) : null;
        if (c == null) return result;
        for (Xpp3Dom e : c.getChildren()) {
            if (e.getValue() == null) continue;
            String v = ctx.resolve(e.getValue().trim());
            if (v != null) result.put(e.getName(), v);
            else this.result.warnings.add(ctx.where() + ": " + name + "/" + e.getName() + " could not be resolved — not migrated");
        }
        return result;
    }

    // <includes><include>**/*Test.java</include></includes> → **/*Test.class (Gradle filters compiled classes).
    private List<String> patterns(Xpp3Dom cfg, String list, String item) {
        List<String> result = new ArrayList<>();
        Xpp3Dom c = cfg != null ? cfg.getChild(list) : null;
        if (c == null) return result;
        for (Xpp3Dom e : c.getChildren(item)) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            String v = ctx.resolve(e.getValue().trim());
            if (v == null) continue;
            result.add(v.endsWith(".java") ? v.substring(0, v.length() - 5) + ".class" : v);
        }
        return result;
    }

    private static List<String> csv(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // A path relative to the module (or the repo root for rootProject.file): ${project.basedir}/ stripped.
    private String path(MavenPlugin p, String raw) {
        if (raw == null) return null;
        String v = raw.replaceFirst("^\\$\\{(?:project\\.basedir|basedir|maven\\.multiModuleProjectDirectory|session\\.executionRootDirectory)}/?", "");
        if (v.contains("${")) {
            v = ctx.resolve(v);
            if (v == null) {
                warn(p, "path '" + raw + "' could not be resolved — not migrated");
                return null;
            }
        }
        return v.isEmpty() ? "." : v;
    }

    // A directory expression: file("src/x") or layout.buildDirectory.dir("generated-sources/x").
    private String directory(MavenPlugin p, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.startsWith("${project.build.directory}")) {
            String rest = v.substring("${project.build.directory}".length()).replaceFirst("^/", "");
            return "layout.buildDirectory.dir(" + q(rest) + ")";
        }
        String rel = path(p, v);
        return rel == null ? null : "file(" + q(rel) + ")";
    }

}
