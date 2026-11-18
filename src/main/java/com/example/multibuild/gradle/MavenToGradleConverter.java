package com.example.multibuild.gradle;

import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Converts the Maven build of a repository into an equivalent Gradle build.
//
// Layout written (Groovy shown; Kotlin DSL is equivalent):
//   settings.gradle      rootProject.name = '<root artifactId>' + one include per module
//   gradle.properties    group, version (single source of truth for the version) and every Maven
//                        property used as a dependency version (lib-a.version → libAVersion)
//   build.gradle         root: shared repositories; single-module repo: the module itself
//   <module>/build.gradle  plugins, dependencies, publishing
//
// Mapping:
//   packaging jar → java-library, war → war, pom without modules (parent/BOM) → java-platform,
//   pom with modules → plain aggregator project.
//   scope compile → api (implementation for optional / war), provided → compileOnly (providedCompile for war),
//   runtime → runtimeOnly, test → testImplementation.
//   <parent> outside the repo → platform(parent) so managed versions and the cross-repo edge are kept;
//   dependencyManagement → constraints { } (scope import → platform()).
//   Dependencies and dependencyManagement inherited from in-repo parents are inlined into each module.
//   Dependencies on modules of the same repo → project(':path').
//   Every module applies maven-publish so `publishToMavenLocal` / `publish` replace `mvn install` / `mvn deploy`.
// Anything without a generated equivalent (build plugins, profiles, system-scoped dependencies) is reported
// as a warning.
@Component
public class MavenToGradleConverter {

    static final String HEADER = "Migrated from Maven pom.xml by multibuild migrate-gradle.";

    private static final Set<String> HANDLED_PLUGINS = Set.of(
            "maven-compiler-plugin", "maven-surefire-plugin", "maven-jar-plugin", "maven-war-plugin",
            "maven-install-plugin", "maven-deploy-plugin", "maven-resources-plugin", "maven-clean-plugin",
            "maven-source-plugin", "maven-site-plugin");
    private static final Set<String> CENTRAL_URLS = Set.of(
            "https://repo.maven.apache.org/maven2", "https://repo1.maven.org/maven2", "http://repo1.maven.org/maven2");
    private static final Set<String> PROJECT_VERSION_PROPS = Set.of(
            "project.version", "version", "pom.version", "project.parent.version", "parent.version");
    private static final Set<String> RESERVED_GRADLE_PROPS = Set.of(
            "version", "group", "name", "description", "status", "path", "buildDir", "project", "parent");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    // Result of a conversion. Nothing is written until apply() is called.
    //   internalKeys:  groupId:artifactId of every pom in the repo (modules, aggregators, parents)
    //   droppedKeys:   external dependencies intentionally not carried over (reported in warnings)
    //   substitutions: external keys replaced by an equivalent (e.g. spring-boot-starter-parent → spring-boot-dependencies)
    public record Conversion(Path repoRoot, GradleDsl dsl, Map<Path, String> writes, List<Path> deletes,
                             List<String> warnings, Set<String> internalKeys, Set<String> droppedKeys,
                             Map<String, String> substitutions) {}

    public Conversion convert(Path repoRoot, GradleDsl dsl) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException("No pom.xml in " + repoRoot);
        }
        return new Run(repoRoot, dsl).convert(rootPom);
    }

    // Writes the generated files and deletes the Maven build files.
    public void apply(Conversion conversion) {
        try {
            for (Map.Entry<Path, String> e : conversion.writes().entrySet()) {
                Files.createDirectories(e.getKey().getParent());
                Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
            }
            for (Path p : conversion.deletes()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Gradle build in " + conversion.repoRoot(), e);
        }
    }

    // lib-a.version → libAVersion
    static String gradleName(String mavenProperty) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : mavenProperty.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upper && sb.length() > 0 ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) sb.insert(0, "p");
        String name = sb.toString();
        return RESERVED_GRADLE_PROPS.contains(name) ? name + "Prop" : name;
    }

    // ── POM model ────────────────────────────────────────────────────────────

    private enum Kind { LIBRARY, WAR, PLATFORM, AGGREGATOR }

    private static final class Pom {
        final Path file;
        final Path dir;
        final Model model;
        final Pom parent;         // in-repo inheritance parent (null when the <parent> is external or absent)
        final List<Pom> children = new ArrayList<>();
        String gradlePath;        // ":" for the repo root, ":a:b" for a/b

        Pom(Path file, Model model, Pom parent) {
            this.file = file;
            this.dir = file.getParent();
            this.model = model;
            this.parent = parent;
        }

        Kind kind() {
            String packaging = model.getPackaging() == null ? "jar" : model.getPackaging();
            return switch (packaging) {
                case "pom" -> model.getModules().isEmpty() ? Kind.PLATFORM : Kind.AGGREGATOR;
                case "war" -> Kind.WAR;
                default -> Kind.LIBRARY;
            };
        }

        String groupId() {
            if (model.getGroupId() != null) return model.getGroupId();
            return model.getParent() != null ? model.getParent().getGroupId() : null;
        }

        String version() {
            if (model.getVersion() != null) return model.getVersion();
            return model.getParent() != null ? model.getParent().getVersion() : null;
        }

        String artifactId() { return model.getArtifactId(); }

        String key() { return groupId() + ":" + artifactId(); }

        Parent externalParent() {
            if (parent != null) return parent.externalParent();
            return model.getParent();
        }

        Map<String, String> properties() {
            Map<String, String> props = parent != null ? new LinkedHashMap<>(parent.properties()) : new LinkedHashMap<>();
            model.getProperties().forEach((k, v) -> props.put(k.toString(), v.toString().trim()));
            return props;
        }

        // Effective dependencies: inherited from in-repo parents, a re-declaration in the child wins.
        List<org.apache.maven.model.Dependency> dependencies() {
            return merge(parent != null ? parent.dependencies() : List.of(), model.getDependencies());
        }

        List<org.apache.maven.model.Dependency> managed() {
            return merge(parent != null ? parent.managed() : List.of(),
                    model.getDependencyManagement() != null ? model.getDependencyManagement().getDependencies() : List.of());
        }

        private static List<org.apache.maven.model.Dependency> merge(List<org.apache.maven.model.Dependency> inherited,
                                                                     List<org.apache.maven.model.Dependency> own) {
            Map<String, org.apache.maven.model.Dependency> byKey = new LinkedHashMap<>();
            for (org.apache.maven.model.Dependency d : inherited) byKey.put(d.getManagementKey(), d);
            for (org.apache.maven.model.Dependency d : own) byKey.put(d.getManagementKey(), d);
            return new ArrayList<>(byKey.values());
        }

        DistributionManagement distribution() {
            if (model.getDistributionManagement() != null) return model.getDistributionManagement();
            return parent != null ? parent.distribution() : null;
        }

        String relativePath(Path repoRoot) {
            String rel = repoRoot.relativize(file).toString().replace('\\', '/');
            return rel.isEmpty() ? "pom.xml" : rel;
        }
    }

    // ── conversion run ───────────────────────────────────────────────────────

    private static final class Run {
        final Path repoRoot;
        final GradleDsl dsl;
        final boolean kts;
        final List<Pom> poms = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> droppedKeys = new LinkedHashSet<>();
        final Map<String, String> substitutions = new LinkedHashMap<>();
        final Map<String, String> gradleProps = new LinkedHashMap<>();
        Map<String, Pom> internalByKey = new LinkedHashMap<>();
        String rootGroup;
        String rootVersion;

        Run(Path repoRoot, GradleDsl dsl) {
            this.repoRoot = repoRoot;
            this.dsl = dsl;
            this.kts = dsl == GradleDsl.KOTLIN;
        }

        Conversion convert(Path rootPomFile) {
            Pom root = load(rootPomFile, null, new ArrayDeque<>());
            if (root == null) throw new IllegalArgumentException("Cannot read " + rootPomFile);
            for (Pom p : poms) internalByKey.putIfAbsent(p.key(), p);

            rootGroup = root.groupId();
            rootVersion = root.version();
            if (rootGroup != null) gradleProps.put("group", rootGroup);
            else warnings.add("pom.xml: no groupId — set 'group' in gradle.properties");
            if (rootVersion != null) gradleProps.put("version", rootVersion);
            else warnings.add("pom.xml: no version — set 'version' in gradle.properties");

            reportUnconverted();

            Map<Path, String> writes = new LinkedHashMap<>();
            boolean single = root.kind() != Kind.AGGREGATOR;
            List<Pom> modules = poms.stream()
                    .filter(p -> p.kind() != Kind.AGGREGATOR && p.gradlePath != null)
                    .toList();
            if (single) {
                writes.put(repoRoot.resolve(dsl.buildFile()), moduleScript(root, true));
            } else {
                writes.put(repoRoot.resolve(dsl.buildFile()), rootScript());
                for (Pom m : modules) {
                    writes.put(m.dir.resolve(dsl.buildFile()), moduleScript(m, false));
                }
            }
            writes.put(repoRoot.resolve(dsl.settingsFile()), settings(root, single ? List.of() : modules));
            writes.put(repoRoot.resolve(GradleProperties.FILE_NAME), gradleProperties());
            writes.put(repoRoot.resolve(".gitignore"), gitignore());

            List<Path> deletes = new ArrayList<>();
            for (Pom p : poms) deletes.add(p.file);
            for (String f : List.of("mvnw", "mvnw.cmd")) {
                if (Files.isRegularFile(repoRoot.resolve(f))) deletes.add(repoRoot.resolve(f));
            }
            Path mvnWrapper = repoRoot.resolve(".mvn/wrapper");
            if (Files.isDirectory(mvnWrapper)) {
                try (Stream<Path> s = Files.list(mvnWrapper)) {
                    s.filter(Files::isRegularFile).forEach(deletes::add);
                } catch (IOException e) {
                    warnings.add(".mvn/wrapper: could not list files — remove the Maven wrapper manually");
                }
            }

            Set<String> internalKeys = new LinkedHashSet<>();
            for (Pom p : poms) internalKeys.add(p.key());
            return new Conversion(repoRoot, dsl, writes, deletes, List.copyOf(warnings),
                    internalKeys, droppedKeys, substitutions);
        }

        // Follows <modules> declarations, mirroring the Maven parser's declared-modules scan.
        Pom load(Path pomFile, Pom aggregator, Deque<Pom> aggregatorChain) {
            Model model;
            try (Reader r = Files.newBufferedReader(pomFile, StandardCharsets.UTF_8)) {
                model = new MavenXpp3Reader().read(r);
            } catch (Exception e) {
                warnings.add(repoRoot.relativize(pomFile) + ": cannot be read (" + e.getMessage() + ") — skipped");
                return null;
            }
            Pom pom = new Pom(pomFile, model, inheritanceParent(model, aggregatorChain));
            Path rel = repoRoot.relativize(pom.dir).normalize();
            if (rel.toString().startsWith("..")) {
                warnings.add(pom.relativePath(repoRoot) + ": module outside the repository root is not supported — skipped");
                return null;
            }
            pom.gradlePath = rel.toString().isEmpty() ? ":" : ":" + rel.toString().replace('\\', '/').replace('/', ':');
            poms.add(pom);
            if (aggregator != null) aggregator.children.add(pom);

            if (pom.kind() == Kind.AGGREGATOR) {
                aggregatorChain.push(pom);
                for (String module : model.getModules()) {
                    Path sub = pom.dir.resolve(module).normalize();
                    Path subPom = module.endsWith(".xml") ? sub : sub.resolve("pom.xml");
                    if (Files.isRegularFile(subPom)) {
                        load(subPom, pom, aggregatorChain);
                    } else {
                        warnings.add(pom.relativePath(repoRoot) + ": declared module '" + module + "' has no pom.xml — skipped");
                    }
                }
                aggregatorChain.pop();
            }
            return pom;
        }

        private static Pom inheritanceParent(Model model, Deque<Pom> aggregatorChain) {
            Parent p = model.getParent();
            if (p == null) return null;
            for (Pom candidate : aggregatorChain) {
                if (Objects.equals(candidate.groupId(), p.getGroupId())
                        && Objects.equals(candidate.artifactId(), p.getArtifactId())) {
                    return candidate;
                }
            }
            return null;
        }

        void reportUnconverted() {
            for (Pom p : poms) {
                String where = p.relativePath(repoRoot);
                if (p.model.getBuild() != null) {
                    for (Plugin plugin : p.model.getBuild().getPlugins()) {
                        String id = plugin.getArtifactId();
                        if (HANDLED_PLUGINS.contains(id)) continue;
                        String hint = "spring-boot-maven-plugin".equals(id)
                                ? " (apply the 'org.springframework.boot' Gradle plugin)" : "";
                        warnings.add(where + ": Maven plugin " + plugin.getGroupId() + ":" + id
                                + " has no generated Gradle equivalent" + hint);
                    }
                }
                if (!p.model.getProfiles().isEmpty()) {
                    warnings.add(where + ": " + p.model.getProfiles().size() + " profile(s) not migrated");
                }
                String packaging = p.model.getPackaging();
                if (packaging != null && !Set.of("jar", "war", "pom").contains(packaging)) {
                    warnings.add(where + ": packaging '" + packaging + "' migrated as java-library");
                }
            }
        }

        // ── settings / properties / root ─────────────────────────────────────

        String settings(Pom root, List<Pom> modules) {
            StringBuilder sb = new StringBuilder("// " + HEADER + "\n\n");
            sb.append("rootProject.name = ").append(str(root.artifactId())).append('\n');
            if (!modules.isEmpty()) sb.append('\n');
            for (Pom m : modules) {
                String path = m.gradlePath.substring(1);
                sb.append(kts ? "include(" + str(path) + ")" : "include " + str(path)).append('\n');
            }
            return sb.toString();
        }

        String gradleProperties() {
            Path existing = repoRoot.resolve(GradleProperties.FILE_NAME);
            String content = "";
            try {
                if (Files.isRegularFile(existing)) content = Files.readString(existing, StandardCharsets.UTF_8);
            } catch (IOException e) {
                warnings.add("gradle.properties: existing file could not be read and was replaced");
            }
            if (content.isEmpty()) content = "# " + HEADER + "\n";
            for (Map.Entry<String, String> e : gradleProps.entrySet()) {
                content = GradleProperties.set(content, e.getKey(), e.getValue());
            }
            return content;
        }

        String gitignore() {
            Path file = repoRoot.resolve(".gitignore");
            String content = "";
            try {
                if (Files.isRegularFile(file)) content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                warnings.add(".gitignore: could not be read");
            }
            Set<String> lines = new HashSet<>(Arrays.asList(content.split("\\R")));
            StringBuilder sb = new StringBuilder(content);
            List<String> add = new ArrayList<>();
            for (String entry : List.of(".gradle/", "build/", "!gradle/wrapper/gradle-wrapper.jar")) {
                if (!lines.contains(entry)) add.add(entry);
            }
            if (add.isEmpty()) return content;
            if (!content.isEmpty() && !content.endsWith("\n")) sb.append('\n');
            if (!content.isEmpty()) sb.append('\n');
            sb.append("# Gradle\n");
            add.forEach(l -> sb.append(l).append('\n'));
            return sb.toString();
        }

        String rootScript() {
            Script s = new Script();
            s.line("// " + HEADER);
            s.blank();
            s.open("allprojects");
            repositories(s);
            s.close();
            return s.toString();
        }

        void repositories(Script s) {
            s.open("repositories");
            s.line("mavenLocal()");
            s.line("mavenCentral()");
            Set<String> urls = new LinkedHashSet<>();
            for (Pom p : poms) {
                for (Repository r : p.model.getRepositories()) {
                    String url = r.getUrl() == null ? null : r.getUrl().replaceAll("/+$", "");
                    if (url != null && !CENTRAL_URLS.contains(url)) urls.add(url);
                }
            }
            for (String url : urls) s.line("maven { url = uri(" + str(url) + ") }");
            s.close();
        }

        // ── module script ────────────────────────────────────────────────────

        String moduleScript(Pom pom, boolean single) {
            Kind kind = pom.kind();
            Script s = new Script();
            Set<String> usedProps = new LinkedHashSet<>();
            List<String> deps = new ArrayList<>();
            List<String> constraints = new ArrayList<>();
            boolean junitPlatform = collectDependencies(pom, kind, deps, constraints, usedProps);

            s.line("// " + HEADER);
            s.blank();
            s.open("plugins");
            s.line(plugin(switch (kind) {
                case WAR -> "war";
                case PLATFORM -> "java-platform";
                default -> "java-library";
            }));
            s.line(plugin("maven-publish"));
            s.close();

            if (kts && !usedProps.isEmpty()) {
                s.blank();
                for (String p : usedProps) s.line("val " + p + ": String by project");
            }

            boolean groupDiffers = pom.groupId() != null && !pom.groupId().equals(rootGroup);
            boolean versionDiffers = pom.version() != null && !pom.version().equals(rootVersion);
            if (groupDiffers || versionDiffers) {
                s.blank();
                if (groupDiffers) s.line("group = " + str(pom.groupId()));
                if (versionDiffers) s.line("version = " + str(pom.version()));
            }

            String javaVersion = kind != Kind.PLATFORM ? javaVersion(pom) : null;
            if (javaVersion != null) {
                s.blank();
                s.open("java");
                s.line("sourceCompatibility = JavaVersion.toVersion(" + str(javaVersion) + ")");
                s.line("targetCompatibility = JavaVersion.toVersion(" + str(javaVersion) + ")");
                s.close();
            }

            if (kind == Kind.PLATFORM && !deps.isEmpty()) {
                s.blank();
                s.open("javaPlatform");
                s.line("allowDependencies()");
                s.close();
            }

            if (single) {
                s.blank();
                repositories(s);
            }

            if (!deps.isEmpty() || !constraints.isEmpty()) {
                s.blank();
                s.open("dependencies");
                if (!constraints.isEmpty()) {
                    s.open("constraints");
                    constraints.forEach(s::raw);
                    s.close();
                }
                deps.forEach(s::raw);
                s.close();
            }

            String encoding = kind != Kind.PLATFORM ? resolve(pom, pom.properties().get("project.build.sourceEncoding")) : null;
            if (encoding != null) {
                s.blank();
                s.open(kts ? "tasks.withType<JavaCompile>().configureEach" : "tasks.withType(JavaCompile).configureEach");
                s.line("options.encoding = " + str(encoding));
                s.close();
            }
            if (junitPlatform) {
                s.blank();
                s.open(kts ? "tasks.named<Test>(\"test\")" : "tasks.named('test', Test)");
                s.line("useJUnitPlatform()");
                s.close();
            }

            s.blank();
            publishing(s, pom, kind, single);
            return s.toString();
        }

        // Fills deps/constraints with rendered statements. Returns true when JUnit 5 tests are present.
        boolean collectDependencies(Pom pom, Kind kind, List<String> deps, List<String> constraints,
                                    Set<String> usedProps) {
            String where = pom.relativePath(repoRoot);
            String apiConfig = kind == Kind.WAR ? "implementation" : "api";

            Parent parent = pom.externalParent();
            if (parent != null && parent.getGroupId() != null && parent.getArtifactId() != null) {
                String key = parent.getGroupId() + ":" + parent.getArtifactId();
                String artifact = parent.getArtifactId();
                if ("org.springframework.boot:spring-boot-starter-parent".equals(key)) {
                    artifact = "spring-boot-dependencies";
                    substitutions.put(key, parent.getGroupId() + ":" + artifact);
                    warnings.add(where + ": parent spring-boot-starter-parent replaced by platform(spring-boot-dependencies)"
                            + " — consider the 'org.springframework.boot' Gradle plugin");
                }
                String coords = parent.getGroupId() + ":" + artifact
                        + (parent.getVersion() != null ? ":" + parent.getVersion() : "");
                deps.add(call(apiConfig, "platform(" + str(coords) + ")"));
            }

            for (org.apache.maven.model.Dependency d : pom.managed()) {
                String key = resolve(pom, d.getGroupId()) + ":" + resolve(pom, d.getArtifactId());
                if (internalByKey.containsKey(key)) continue;
                String coords = coordinates(pom, d, usedProps);
                if ("import".equals(d.getScope())) {
                    deps.add(call(apiConfig, "platform(" + str(coords) + ")"));
                } else if (d.getVersion() != null) {
                    constraints.add(call(apiConfig, str(coords)));
                }
            }

            boolean junitPlatform = false;
            for (org.apache.maven.model.Dependency d : pom.dependencies()) {
                String group = resolve(pom, d.getGroupId());
                String artifact = resolve(pom, d.getArtifactId());
                String key = group + ":" + artifact;
                String scope = d.getScope() == null ? "compile" : d.getScope();
                if (artifact != null && (artifact.startsWith("junit-jupiter") || artifact.equals("spring-boot-starter-test"))) {
                    junitPlatform = true;
                }
                if ("system".equals(scope)) {
                    warnings.add(where + ": system-scoped dependency " + key + " not migrated");
                    droppedKeys.add(key);
                    continue;
                }
                String config = configuration(kind, scope, d.isOptional());
                if (config == null) {
                    warnings.add(where + ": " + scope + "-scoped dependency " + key
                            + " of a parent pom is not inherited by consumers in Gradle — add it to them");
                    droppedKeys.add(key);
                    continue;
                }
                Pom internal = internalByKey.get(key);
                String notation;
                if (internal != null) {
                    if (internal == pom) continue;
                    if (internal.kind() == Kind.AGGREGATOR || internal.gradlePath == null) {
                        warnings.add(where + ": dependency on aggregator " + key + " dropped");
                        continue;
                    }
                    String project = "project(" + str(internal.gradlePath) + ")";
                    notation = internal.kind() == Kind.PLATFORM ? "platform(" + project + ")" : project;
                    if ("test-jar".equals(d.getType())) {
                        warnings.add(where + ": test-jar dependency on " + key
                                + " migrated as a plain project dependency — consider test fixtures");
                    }
                } else {
                    notation = "pom".equals(d.getType()) && "import".equals(scope)
                            ? "platform(" + str(coordinates(pom, d, usedProps)) + ")"
                            : str(coordinates(pom, d, usedProps));
                }
                deps.add(withExclusions(config, notation, d.getExclusions()));
            }
            if (junitPlatform && kind != Kind.PLATFORM) {
                deps.add(call("testRuntimeOnly", str("org.junit.platform:junit-platform-launcher")));
            }
            return junitPlatform;
        }

        String configuration(Kind kind, String scope, boolean optional) {
            return switch (kind) {
                case PLATFORM -> switch (scope) {
                    case "compile", "import" -> "api";
                    case "runtime" -> "runtime";
                    default -> null;
                };
                case WAR -> switch (scope) {
                    case "provided" -> "providedCompile";
                    case "runtime" -> "runtimeOnly";
                    case "test" -> "testImplementation";
                    default -> "implementation";
                };
                default -> switch (scope) {
                    case "provided" -> "compileOnly";
                    case "runtime" -> "runtimeOnly";
                    case "test" -> "testImplementation";
                    default -> optional ? "implementation" : "api";
                };
            };
        }

        // "g:a[:version][:classifier]" with the version rendered as a literal or a gradle.properties reference.
        String coordinates(Pom pom, org.apache.maven.model.Dependency d, Set<String> usedProps) {
            String group = resolve(pom, d.getGroupId());
            String artifact = resolve(pom, d.getArtifactId());
            String version = versionExpression(pom, d.getVersion(), group + ":" + artifact, usedProps);
            String classifier = d.getClassifier();
            if ("test-jar".equals(d.getType()) && classifier == null) classifier = "tests";
            StringBuilder sb = new StringBuilder(group).append(':').append(artifact);
            if (version != null) {
                sb.append(':').append(version);
                if (classifier != null) sb.append(':').append(classifier);
            } else if (classifier != null) {
                warnings.add(pom.relativePath(repoRoot) + ": classifier '" + classifier + "' of versionless dependency "
                        + group + ":" + artifact + " dropped");
            }
            return sb.toString();
        }

        String versionExpression(Pom pom, String version, String key, Set<String> usedProps) {
            if (version == null || version.isBlank()) return null;
            Matcher whole = Pattern.compile("^\\$\\{([^}]+)}$").matcher(version.trim());
            if (whole.matches()) {
                String prop = whole.group(1);
                if (PROJECT_VERSION_PROPS.contains(prop)) return "${version}";
                String value = resolve(pom, version);
                if (value == null) {
                    warnings.add(pom.relativePath(repoRoot) + ": property '" + prop + "' (version of " + key
                            + ") is not defined in this repository — define it in gradle.properties");
                    String name = gradleName(prop);
                    usedProps.add(name);
                    return "${" + name + "}";
                }
                String name = gradlePropertyFor(pom, prop, value);
                usedProps.add(name);
                return "${" + name + "}";
            }
            String resolved = resolve(pom, version);
            if (resolved == null) {
                warnings.add(pom.relativePath(repoRoot) + ": version '" + version + "' of " + key + " could not be resolved");
                return version;
            }
            return resolved;
        }

        // Registers a gradle.properties entry for Maven property `prop` and returns its Gradle name.
        String gradlePropertyFor(Pom pom, String prop, String value) {
            String name = gradleName(prop);
            String existing = gradleProps.get(name);
            if (existing != null && !existing.equals(value)) {
                name = gradleName(pom.artifactId() + "." + prop);
            }
            gradleProps.put(name, value);
            return name;
        }

        // Expands ${...} placeholders using the pom's effective properties and project coordinates.
        // Returns null when a placeholder cannot be resolved within the repository.
        String resolve(Pom pom, String value) {
            if (value == null) return null;
            Map<String, String> props = pom.properties();
            String current = value;
            for (int depth = 0; depth < 10 && current.contains("${"); depth++) {
                Matcher m = PLACEHOLDER.matcher(current);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String replacement = builtin(pom, m.group(1));
                    if (replacement == null) replacement = props.get(m.group(1));
                    if (replacement == null) return null;
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
                m.appendTail(sb);
                current = sb.toString();
            }
            return current.contains("${") ? null : current;
        }

        static String builtin(Pom pom, String name) {
            Parent parent = pom.model.getParent();
            return switch (name) {
                case "project.version", "version", "pom.version" -> pom.version();
                case "project.groupId", "groupId", "pom.groupId" -> pom.groupId();
                case "project.artifactId", "artifactId", "pom.artifactId" -> pom.artifactId();
                case "project.parent.version", "parent.version" -> parent != null ? parent.getVersion() : null;
                case "project.parent.groupId", "parent.groupId" -> parent != null ? parent.getGroupId() : null;
                default -> null;
            };
        }

        String javaVersion(Pom pom) {
            Map<String, String> props = pom.properties();
            for (String key : List.of("maven.compiler.release", "maven.compiler.source", "java.version")) {
                String v = resolve(pom, props.get(key));
                if (v != null && !v.isBlank()) return v;
            }
            for (Pom p = pom; p != null; p = p.parent) {
                if (p.model.getBuild() == null) continue;
                for (Plugin plugin : p.model.getBuild().getPlugins()) {
                    if (!"maven-compiler-plugin".equals(plugin.getArtifactId())) continue;
                    if (plugin.getConfiguration() instanceof Xpp3Dom cfg) {
                        for (String child : List.of("release", "source")) {
                            Xpp3Dom node = cfg.getChild(child);
                            if (node != null && node.getValue() != null) {
                                String v = resolve(pom, node.getValue().trim());
                                if (v != null) return v;
                            }
                        }
                    }
                }
            }
            return null;
        }

        void publishing(Script s, Pom pom, Kind kind, boolean single) {
            String component = switch (kind) {
                case PLATFORM -> "javaPlatform";
                case WAR -> "web";
                default -> "java";
            };
            String projectName = single ? pom.artifactId() : pom.gradlePath.substring(pom.gradlePath.lastIndexOf(':') + 1);
            s.open("publishing");
            s.open("publications");
            s.open(kts ? "create<MavenPublication>(\"maven\")" : "maven(MavenPublication)");
            s.line(kts ? "from(components[\"" + component + "\"])" : "from components." + component);
            if (!pom.artifactId().equals(projectName)) {
                s.line("artifactId = " + str(pom.artifactId()));
            }
            s.close();
            s.close();
            DistributionManagement dm = pom.distribution();
            if (dm != null && (dm.getRepository() != null || dm.getSnapshotRepository() != null)) {
                DeploymentRepository release = dm.getRepository();
                DeploymentRepository snapshot = dm.getSnapshotRepository() != null ? dm.getSnapshotRepository() : release;
                if (release == null) release = snapshot;
                String name = gradleName(release.getId() != null ? release.getId() : "deploy");
                s.open("repositories");
                s.open("maven");
                s.line("name = " + str(name));
                if (Objects.equals(release.getUrl(), snapshot.getUrl())) {
                    s.line("url = uri(" + str(release.getUrl()) + ")");
                } else if (kts) {
                    s.line("url = uri(if (version.toString().endsWith(\"SNAPSHOT\")) " + str(snapshot.getUrl())
                            + " else " + str(release.getUrl()) + ")");
                } else {
                    s.line("url = uri(version.toString().endsWith('SNAPSHOT') ? " + str(snapshot.getUrl())
                            + " : " + str(release.getUrl()) + ")");
                }
                s.line(kts ? "credentials(PasswordCredentials::class)" : "credentials(PasswordCredentials)");
                s.close();
                s.close();
                String msg = "distributionManagement migrated to publishing.repositories — set credentials via the "
                        + name + "Username / " + name + "Password Gradle properties";
                if (!warnings.contains(msg)) warnings.add(msg);
            }
            s.close();
        }

        // ── DSL helpers ──────────────────────────────────────────────────────

        String str(String s) {
            if (kts) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            return s.contains("${") ? "\"" + s.replace("\"", "\\\"") + "\"" : "'" + s.replace("'", "\\'") + "'";
        }

        String call(String config, String argument) {
            return kts ? config + "(" + argument + ")" : config + " " + argument;
        }

        String plugin(String id) {
            return kts ? "id(\"" + id + "\")" : "id '" + id + "'";
        }

        String withExclusions(String config, String notation, List<Exclusion> exclusions) {
            if (exclusions == null || exclusions.isEmpty()) return call(config, notation);
            StringBuilder sb = new StringBuilder(config).append('(').append(notation).append(") {\n");
            for (Exclusion e : exclusions) {
                boolean anyGroup = e.getGroupId() == null || "*".equals(e.getGroupId());
                boolean anyArtifact = e.getArtifactId() == null || "*".equals(e.getArtifactId());
                List<String> parts = new ArrayList<>();
                if (!anyGroup) parts.add(kts ? "group = " + str(e.getGroupId()) : "group: " + str(e.getGroupId()));
                if (!anyArtifact) parts.add(kts ? "module = " + str(e.getArtifactId()) : "module: " + str(e.getArtifactId()));
                if (parts.isEmpty()) {
                    sb.append("    ").append(kts ? "isTransitive = false" : "transitive = false").append('\n');
                } else {
                    sb.append("    ").append(kts ? "exclude(" + String.join(", ", parts) + ")"
                            : "exclude " + String.join(", ", parts)).append('\n');
                }
            }
            return sb.append('}').toString();
        }
    }

    // Indentation-aware script builder.
    private static final class Script {
        private final StringBuilder sb = new StringBuilder();
        private int depth;

        void line(String s) {
            sb.append("    ".repeat(depth)).append(s).append('\n');
        }

        // Multi-line statement: every line is indented to the current depth.
        void raw(String statement) {
            for (String l : statement.split("\n")) line(l);
        }

        void blank() {
            if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) sb.append('\n');
        }

        void open(String header) {
            line(header + " {");
            depth++;
        }

        void close() {
            depth--;
            line("}");
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
