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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
//   build.gradle         root: shared repositories + one shared block per in-repo parent pom;
//                        single-module repo: the module itself
//   <module>/build.gradle  only what the module declares itself
//
// Mapping:
//   packaging jar → java-library, war → war, pom without modules (parent/BOM) → java-platform,
//   pom with modules → plain aggregator project.
//   scope compile → api (implementation for optional / war), provided → compileOnly (providedCompile for war),
//   runtime → runtimeOnly, test → testImplementation. A dependency without <scope> / <optional> /
//   <exclusions> takes them from dependencyManagement exactly like Maven does: from the pom and its in-repo
//   parents, from external parents and from imported BOMs (scope import). Poms outside the repository are
//   read from the local Maven repository (maven.repo.local, else <localRepository> in ~/.m2/settings.xml,
//   else ~/.m2/repository); when one is missing the affected dependencies are reported as warnings.
//   Managed versions become constraints in the configuration matching the managed scope.
//   <parent> outside the repo → platform(parent) so managed versions and the cross-repo edge are kept;
//   dependencyManagement → constraints { } (scope import → platform()).
//   An in-repo parent pom becomes a shared block in the root script — `subprojects { }` when every
//   subproject inherits it, `configure([project(':a'), ...]) { }` otherwise — holding what the parent
//   contributes: plugins, dependencies, dependencyManagement, Java version, encoding, JUnit platform and
//   publishing. Contributions a child overrides (same dependency redeclared, a version property redefined)
//   and parents whose inheritors mix packagings (jar + war) are inlined into each module instead, which
//   keeps Maven's "child wins" semantics. BOM-like children (packaging pom) always get them inlined.
//   Dependencies on modules of the same repo → project(':path').
//   Every module applies maven-publish so `publishToMavenLocal` / `publish` replace `mvn install` / `mvn deploy`.
// Anything without a generated equivalent (build plugins, profiles, system-scoped dependencies) is reported
// as a warning.
@Component
public class MavenToGradleConverter {

    private static final Logger log = LoggerFactory.getLogger(MavenToGradleConverter.class);

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
                             Map<String, String> substitutions) {
        // Same conversion that leaves the Maven build in place (migrate.keep.maven=true).
        public Conversion keepingMaven() {
            return new Conversion(repoRoot, dsl, writes, List.of(), warnings, internalKeys, droppedKeys, substitutions);
        }
    }

    // The Maven build files of a repo: every pom reachable through <modules>, plus the Maven wrapper.
    public List<Path> mavenBuildFiles(Path repoRoot) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) return List.of();
        Run run = new Run(repoRoot, GradleDsl.GROOVY, Path.of(""));
        run.load(rootPom, null, new ArrayDeque<>());
        return run.mavenBuildFiles();
    }

    // Local Maven repository holding external parents / imported BOMs (blank = Maven's default location).
    @org.springframework.beans.factory.annotation.Value("${maven.repo.local:}")
    private String localRepository = "";

    static Path defaultLocalRepository() {
        String prop = System.getProperty("maven.repo.local");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        Path m2 = Path.of(System.getProperty("user.home"), ".m2");
        Path settings = m2.resolve("settings.xml");
        try {
            if (Files.isRegularFile(settings)) {
                Matcher m = Pattern.compile("<localRepository>\\s*([^<]+?)\\s*</localRepository>")
                        .matcher(Files.readString(settings, StandardCharsets.UTF_8));
                if (m.find()) return Path.of(m.group(1).replace("${user.home}", System.getProperty("user.home")));
            }
        } catch (IOException e) {
            // fall through to the default
        }
        return m2.resolve("repository");
    }

    public Conversion convert(Path repoRoot, GradleDsl dsl) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException("No pom.xml in " + repoRoot);
        }
        Path local = localRepository == null || localRepository.isBlank()
                ? defaultLocalRepository() : Path.of(localRepository);
        return new Run(repoRoot, dsl, local).convert(rootPom);
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

    // What an in-repo parent pom contributes to its inheriting modules, rendered once in the root script.
    private static final class Shared {
        final Pom parent;
        final List<Pom> targets;
        final Kind kind;
        final boolean top;                 // outermost sharing parent of its targets
        final boolean perKind;             // the parent's inheritors mix packagings: one block per kind
        final List<org.apache.maven.model.Dependency> deps = new ArrayList<>();
        final List<org.apache.maven.model.Dependency> managed = new ArrayList<>();
        final Set<org.apache.maven.model.Dependency> shared = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean externalPlatform;
        String java;
        String encoding;
        boolean junit;
        boolean publishing;

        Shared(Pom parent, List<Pom> targets, Kind kind, boolean top, boolean perKind) {
            this.parent = parent;
            this.perKind = perKind;
            this.targets = targets;
            this.kind = kind;
            this.top = top;
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
        final List<Shared> sharing = new ArrayList<>();
        // Inside a shared block of a Kotlin script configurations have no accessors: "api"(...).
        boolean sharedContext;
        String rootGroup;
        String rootVersion;

        final Path localRepo;
        final Map<String, Model> externalModels = new HashMap<>();
        final Map<Pom, Map<String, org.apache.maven.model.Dependency>> managedIndexes = new IdentityHashMap<>();
        final Map<Pom, Set<String>> unresolvedByPom = new IdentityHashMap<>();
        final Set<String> scopeWarnings = new HashSet<>();

        Run(Path repoRoot, GradleDsl dsl, Path localRepo) {
            this.localRepo = localRepo;
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
                planSharing(modules);
                writes.put(repoRoot.resolve(dsl.buildFile()), rootScript(modules));
                for (Pom m : modules) {
                    writes.put(m.dir.resolve(dsl.buildFile()), moduleScript(m, false));
                }
            }
            writes.put(repoRoot.resolve(dsl.settingsFile()), settings(root, single ? List.of() : modules));
            writes.put(repoRoot.resolve(GradleProperties.FILE_NAME), gradleProperties());
            writes.put(repoRoot.resolve(".gitignore"), gitignore());

            List<Path> deletes = mavenBuildFiles();

            Set<String> internalKeys = new LinkedHashSet<>();
            for (Pom p : poms) internalKeys.add(p.key());
            return new Conversion(repoRoot, dsl, writes, deletes, List.copyOf(warnings),
                    internalKeys, droppedKeys, substitutions);
        }

        List<Path> mavenBuildFiles() {
            List<Path> files = new ArrayList<>();
            for (Pom p : poms) files.add(p.file);
            for (String f : List.of("mvnw", "mvnw.cmd")) {
                if (Files.isRegularFile(repoRoot.resolve(f))) files.add(repoRoot.resolve(f));
            }
            Path mvnWrapper = repoRoot.resolve(".mvn/wrapper");
            if (Files.isDirectory(mvnWrapper)) {
                try (Stream<Path> s = Files.list(mvnWrapper)) {
                    s.filter(Files::isRegularFile).forEach(files::add);
                } catch (IOException e) {
                    warnings.add(".mvn/wrapper: could not list files — remove the Maven wrapper manually");
                }
            }
            return files;
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

        // ── effective dependencyManagement (scope / optional / exclusions) ───

        static String managementKey(String group, String artifact, String type, String classifier) {
            return group + ":" + artifact + ":" + (type == null || type.isBlank() ? "jar" : type)
                    + (classifier == null || classifier.isBlank() ? "" : ":" + classifier);
        }

        // Copy of `d` with ${...} in coordinates and scope expanded (left as-is when unresolvable).
        static org.apache.maven.model.Dependency expanded(org.apache.maven.model.Dependency d,
                                                          java.util.function.UnaryOperator<String> resolver) {
            org.apache.maven.model.Dependency c = d.clone();
            java.util.function.UnaryOperator<String> r = v -> {
                if (v == null) return null;
                String x = resolver.apply(v);
                return x != null ? x : v;
            };
            c.setGroupId(r.apply(d.getGroupId()));
            c.setArtifactId(r.apply(d.getArtifactId()));
            c.setVersion(r.apply(d.getVersion()));
            c.setScope(r.apply(d.getScope()));
            c.setType(r.apply(d.getType()));
            c.setClassifier(r.apply(d.getClassifier()));
            return c;
        }

        static String managementKey(org.apache.maven.model.Dependency d) {
            return managementKey(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier());
        }

        // Maven precedence: the pom's own and inherited (in-repo, then external parents) entries first,
        // then entries of imported BOMs in declaration order; the first entry for a key wins.
        Map<String, org.apache.maven.model.Dependency> managedIndex(Pom pom) {
            Map<String, org.apache.maven.model.Dependency> cached = managedIndexes.get(pom);
            if (cached != null) return cached;
            Map<String, org.apache.maven.model.Dependency> index = new LinkedHashMap<>();
            managedIndexes.put(pom, index);   // guards against import cycles
            Set<String> unresolved = new LinkedHashSet<>();
            List<org.apache.maven.model.Dependency> imports = new ArrayList<>();
            for (org.apache.maven.model.Dependency d : pom.managed()) {
                org.apache.maven.model.Dependency e = expanded(d, v -> resolve(pom, v));
                index.putIfAbsent(managementKey(e), e);
                if ("import".equals(e.getScope())) imports.add(e);
            }
            Parent ext = pom.externalParent();
            if (ext != null) {
                externalManaged(ext.getGroupId(), ext.getArtifactId(), ext.getVersion(), 0, unresolved)
                        .forEach(index::putIfAbsent);
            }
            for (org.apache.maven.model.Dependency imp : imports) {
                Pom internal = internalByKey.get(imp.getGroupId() + ":" + imp.getArtifactId());
                Map<String, org.apache.maven.model.Dependency> imported = internal != null ? managedIndex(internal)
                        : externalManaged(imp.getGroupId(), imp.getArtifactId(), imp.getVersion(), 0, unresolved);
                if (internal != null) unresolved.addAll(unresolvedByPom.getOrDefault(internal, Set.of()));
                imported.forEach(index::putIfAbsent);
            }
            unresolvedByPom.put(pom, unresolved);
            return index;
        }

        // Effective dependencyManagement of a pom from the local Maven repository.
        Map<String, org.apache.maven.model.Dependency> externalManaged(String g, String a, String v, int depth,
                                                                      Set<String> unresolved) {
            Map<String, org.apache.maven.model.Dependency> index = new LinkedHashMap<>();
            if (depth > 15 || g == null || a == null || v == null || g.contains("${") || v.contains("${")) {
                if (g != null && a != null) unresolved.add(g + ":" + a + ":" + v);
                return index;
            }
            Model model = externalModel(g, a, v);
            if (model == null) {
                unresolved.add(g + ":" + a + ":" + v);
                return index;
            }
            Map<String, String> props = externalProperties(model, depth);
            List<org.apache.maven.model.Dependency> imports = new ArrayList<>();
            if (model.getDependencyManagement() != null) {
                for (org.apache.maven.model.Dependency d : model.getDependencyManagement().getDependencies()) {
                    org.apache.maven.model.Dependency e = expanded(d, x -> expand(x, props));
                    index.putIfAbsent(managementKey(e), e);
                    if ("import".equals(e.getScope())) imports.add(e);
                }
            }
            Parent parent = model.getParent();
            if (parent != null) {
                externalManaged(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), depth + 1, unresolved)
                        .forEach(index::putIfAbsent);
            }
            for (org.apache.maven.model.Dependency imp : imports) {
                externalManaged(imp.getGroupId(), imp.getArtifactId(), imp.getVersion(), depth + 1, unresolved)
                        .forEach(index::putIfAbsent);
            }
            return index;
        }

        Model externalModel(String g, String a, String v) {
            String key = g + ":" + a + ":" + v;
            if (externalModels.containsKey(key)) return externalModels.get(key);
            Path file = localRepo.resolve(g.replace('.', '/')).resolve(a).resolve(v).resolve(a + "-" + v + ".pom");
            Model model = null;
            if (Files.isRegularFile(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    model = new MavenXpp3Reader().read(r, false);
                } catch (Exception e) {
                    warnings.add(file + ": cannot be read (" + e.getMessage() + ")");
                }
            }
            externalModels.put(key, model);
            return model;
        }

        // Properties of an external pom: parent chain first, own properties win, plus project.* builtins.
        Map<String, String> externalProperties(Model model, int depth) {
            Map<String, String> props = new HashMap<>();
            Parent parent = model.getParent();
            if (parent != null && depth < 15) {
                Model pm = externalModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
                if (pm != null) props.putAll(externalProperties(pm, depth + 1));
            }
            model.getProperties().forEach((k, v) -> props.put(k.toString(), v.toString().trim()));
            String group = model.getGroupId() != null ? model.getGroupId() : parent != null ? parent.getGroupId() : null;
            String version = model.getVersion() != null ? model.getVersion() : parent != null ? parent.getVersion() : null;
            for (String prefix : List.of("project.", "pom.", "")) {
                if (group != null) props.put(prefix + "groupId", group);
                if (version != null) props.put(prefix + "version", version);
                props.put(prefix + "artifactId", model.getArtifactId());
            }
            if (parent != null) {
                props.put("project.parent.version", parent.getVersion());
                props.put("project.parent.groupId", parent.getGroupId());
            }
            return props;
        }

        static String expand(String value, Map<String, String> props) {
            String current = value;
            for (int depth = 0; depth < 10 && current.contains("${"); depth++) {
                Matcher m = PLACEHOLDER.matcher(current);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String replacement = props.get(m.group(1));
                    if (replacement == null) return null;
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
                m.appendTail(sb);
                current = sb.toString();
            }
            return current.contains("${") ? null : current;
        }

        org.apache.maven.model.Dependency managedFor(Pom ctx, org.apache.maven.model.Dependency d) {
            org.apache.maven.model.Dependency e = expanded(d, v -> resolve(ctx, v));
            return managedIndex(ctx).get(managementKey(e));
        }

        // Effective scope: declared, else managed, else compile.
        String scopeOf(Pom ctx, org.apache.maven.model.Dependency d) {
            String declared = d.getScope() != null ? resolve(ctx, d.getScope()) : null;
            if (declared != null && !declared.isBlank()) return declared;
            org.apache.maven.model.Dependency m = managedFor(ctx, d);
            if (m != null && m.getScope() != null && !m.getScope().isBlank() && !"import".equals(m.getScope())) {
                return m.getScope();
            }
            if (d.getVersion() == null && m == null && !unresolvedByPom.getOrDefault(ctx, Set.of()).isEmpty()) {
                String key = resolve(ctx, d.getGroupId()) + ":" + resolve(ctx, d.getArtifactId());
                if (scopeWarnings.add(ctx.relativePath(repoRoot) + key)) {
                    warnings.add(ctx.relativePath(repoRoot) + ": " + key + " is managed by "
                            + String.join(", ", unresolvedByPom.get(ctx)) + ", not found in the local Maven repository "
                            + localRepo + " — scope assumed compile; run 'mvn dependency:resolve' once and migrate "
                            + "again, or check the generated configuration");
                }
            }
            return "compile";
        }

        boolean optionalOf(Pom ctx, org.apache.maven.model.Dependency d) {
            if (d.getOptional() != null) return d.isOptional();
            org.apache.maven.model.Dependency m = managedFor(ctx, d);
            return m != null && m.isOptional();
        }

        List<Exclusion> exclusionsOf(Pom ctx, org.apache.maven.model.Dependency d) {
            org.apache.maven.model.Dependency m = managedFor(ctx, d);
            if (m == null || m.getExclusions().isEmpty()) return d.getExclusions();
            List<Exclusion> all = new ArrayList<>(d.getExclusions());
            for (Exclusion e : m.getExclusions()) {
                boolean present = all.stream().anyMatch(x -> Objects.equals(x.getGroupId(), e.getGroupId())
                        && Objects.equals(x.getArtifactId(), e.getArtifactId()));
                if (!present) all.add(e);
            }
            return all;
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

        String rootScript(List<Pom> modules) {
            Script s = new Script();
            s.line("// " + HEADER);
            s.blank();
            s.open("allprojects");
            repositories(s);
            s.close();
            for (Shared shared : sharing) {
                s.blank();
                sharedBlock(s, shared, modules);
            }
            return s.toString();
        }

        // ── shared parent configuration ──────────────────────────────────────

        static boolean inheritsFrom(Pom pom, Pom parent) {
            for (Pom p = pom.parent; p != null; p = p.parent) if (p == parent) return true;
            return false;
        }

        // One shared block per in-repo parent and packaging of its inheriting modules (jar modules get
        // java-library + api, war modules war + implementation), so mixed parents are still shared.
        void planSharing(List<Pom> modules) {
            for (Pom parent : poms) {
                if (parent.kind() != Kind.AGGREGATOR) continue;
                List<Pom> inheritors = poms.stream().filter(q -> inheritsFrom(q, parent)).toList();
                Map<Kind, List<Pom>> byKind = new EnumMap<>(Kind.class);
                for (Pom q : inheritors) {
                    if (modules.contains(q) && q.kind() != Kind.PLATFORM) {
                        byKind.computeIfAbsent(q.kind(), k -> new ArrayList<>()).add(q);
                    }
                }
                for (Map.Entry<Kind, List<Pom>> group : byKind.entrySet()) {
                    Kind kind = group.getKey();
                    List<Pom> targets = group.getValue();
                    boolean top = sharing.stream().noneMatch(b -> b.kind == kind && inheritsFrom(parent, b.parent));
                    Shared sh = new Shared(parent, targets, kind, top, byKind.size() > 1);
                    for (org.apache.maven.model.Dependency d : parent.model.getDependencies()) {
                        if (shareable(parent, d, inheritors, targets, false)) sh.deps.add(d);
                        else log.info("  {}: {} stays in each module (overridden by a child)",
                                parent.relativePath(repoRoot), d.getManagementKey());
                    }
                    if (parent.model.getDependencyManagement() != null) {
                        for (org.apache.maven.model.Dependency d : parent.model.getDependencyManagement().getDependencies()) {
                            if (shareable(parent, d, inheritors, targets, true)) sh.managed.add(d);
                            else log.info("  {}: managed {} stays in each module (overridden by a child)",
                                    parent.relativePath(repoRoot), d.getManagementKey());
                        }
                    }
                    sh.shared.addAll(sh.deps);
                    sh.shared.addAll(sh.managed);
                    if (top) {
                        sh.externalPlatform = parent.parent == null && parent.model.getParent() != null;
                        sh.java = common(targets, this::javaVersion);
                        sh.encoding = common(targets, t -> resolve(t, t.properties().get("project.build.sourceEncoding")));
                        sh.junit = targets.stream().allMatch(t -> usesJUnitPlatform(t, t.dependencies()));
                        DistributionManagement dm = targets.get(0).distribution();
                        sh.publishing = targets.stream().allMatch(t -> t.distribution() == dm);
                    }
                    sharing.add(sh);
                }
            }
        }

        // A parent's dependency can live in the shared block when no inheritor redeclares it, it is not a
        // module of this repo, and it resolves the same in every target (no redefined version property).
        boolean shareable(Pom parent, org.apache.maven.model.Dependency d, List<Pom> inheritors,
                          List<Pom> targets, boolean managed) {
            String mk = d.getManagementKey();
            for (Pom q : inheritors) {
                List<org.apache.maven.model.Dependency> own = managed
                        ? (q.model.getDependencyManagement() != null ? q.model.getDependencyManagement().getDependencies() : List.of())
                        : q.model.getDependencies();
                if (own.stream().anyMatch(o -> o.getManagementKey().equals(mk))) return false;
            }
            String key = resolve(parent, d.getGroupId()) + ":" + resolve(parent, d.getArtifactId());
            if (internalByKey.containsKey(key)) return false;
            String scope = managed ? d.getScope() : scopeOf(parent, d);
            for (Pom t : targets) {
                String ts = managed ? d.getScope() : scopeOf(t, d);
                if (!Objects.equals(scope, ts)) return false;
            }
            String coords = key + ":" + resolve(parent, d.getVersion());
            for (Pom t : targets) {
                String c = resolve(t, d.getGroupId()) + ":" + resolve(t, d.getArtifactId()) + ":" + resolve(t, d.getVersion());
                if (!c.equals(coords)) return false;
            }
            return true;
        }

        static String common(List<Pom> targets, java.util.function.Function<Pom, String> value) {
            String first = value.apply(targets.get(0));
            if (first == null) return null;
            for (Pom t : targets) if (!first.equals(value.apply(t))) return null;
            return first;
        }

        List<Shared> sharedFor(Pom pom) {
            return sharing.stream().filter(sh -> sh.targets.contains(pom)).toList();
        }

        Shared topSharedFor(Pom pom) {
            return sharedFor(pom).stream().filter(sh -> sh.top).findFirst().orElse(null);
        }

        boolean isShared(Pom pom, org.apache.maven.model.Dependency d) {
            return sharedFor(pom).stream().anyMatch(sh -> sh.shared.contains(d));
        }

        void sharedBlock(Script s, Shared sh, List<Pom> modules) {
            boolean everySubproject = sh.parent.gradlePath.equals(":")
                    && sh.targets.size() == modules.size()
                    && modules.stream().noneMatch(m -> m.gradlePath.lastIndexOf(':') > 0);
            if (everySubproject) {
                s.open("subprojects");
            } else {
                List<String> projects = sh.targets.stream().map(t -> "project(" + str(t.gradlePath) + ")").toList();
                s.open(kts ? "configure(listOf(" + String.join(", ", projects) + "))"
                        : "configure([" + String.join(", ", projects) + "])");
            }
            s.line("// shared configuration from " + sh.parent.relativePath(repoRoot)
                    + (sh.perKind ? " (" + (sh.kind == Kind.WAR ? "war" : "jar") + " modules)" : ""));
            String kindPlugin = sh.kind == Kind.WAR ? "war" : "java-library";
            s.line(kts ? "apply(plugin = \"" + kindPlugin + "\")" : "apply plugin: '" + kindPlugin + "'");
            s.line(kts ? "apply(plugin = \"maven-publish\")" : "apply plugin: 'maven-publish'");

            sharedContext = true;
            Set<String> usedProps = new LinkedHashSet<>();
            List<String> deps = new ArrayList<>();
            List<String> constraints = new ArrayList<>();
            render(sh.parent, sh.kind, sh.externalPlatform ? sh.parent.model.getParent() : null,
                    sh.managed, sh.deps, null, deps, constraints, usedProps);
            if (sh.junit) deps.add(call("testRuntimeOnly", str("org.junit.platform:junit-platform-launcher")));
            if (kts && !usedProps.isEmpty()) {
                s.blank();
                for (String p : usedProps) s.line("val " + p + ": String by project");
            }
            if (sh.java != null) {
                s.blank();
                javaBlock(s, sh.java);
            }
            dependenciesBlock(s, deps, constraints);
            sharedContext = false;

            if (sh.encoding != null) {
                s.blank();
                encodingBlock(s, sh.encoding);
            }
            if (sh.junit) {
                s.blank();
                s.open(kts ? "tasks.withType<Test>().configureEach" : "tasks.withType(Test).configureEach");
                s.line("useJUnitPlatform()");
                s.close();
            }
            if (sh.publishing) {
                s.blank();
                s.open(kts ? "configure<PublishingExtension>" : "publishing");
                publications(s, sh.kind, null);
                publishRepositories(s, sh.targets.get(0).distribution());
                s.close();
            }
            s.close();
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
            Shared top = single ? null : topSharedFor(pom);
            boolean pluginsShared = !single && !sharedFor(pom).isEmpty();

            s.line("// " + HEADER);
            // Groovy scripts see plugins applied by the root's shared block; Kotlin scripts still declare
            // them so the type-safe accessors (api, java, publishing ...) are generated.
            if (!pluginsShared || kts) {
                s.blank();
                s.open("plugins");
                s.line(plugin(switch (kind) {
                    case WAR -> "war";
                    case PLATFORM -> "java-platform";
                    default -> "java-library";
                }));
                s.line(plugin("maven-publish"));
                s.close();
            }

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

            String javaVersion = kind != Kind.PLATFORM && (top == null || top.java == null) ? javaVersion(pom) : null;
            if (javaVersion != null) {
                s.blank();
                javaBlock(s, javaVersion);
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

            dependenciesBlock(s, deps, constraints);

            String encoding = kind != Kind.PLATFORM && (top == null || top.encoding == null)
                    ? resolve(pom, pom.properties().get("project.build.sourceEncoding")) : null;
            if (encoding != null) {
                s.blank();
                encodingBlock(s, encoding);
            }
            if (junitPlatform && kind != Kind.PLATFORM && (top == null || !top.junit)) {
                s.blank();
                s.open(kts ? "tasks.named<Test>(\"test\")" : "tasks.named('test', Test)");
                s.line("useJUnitPlatform()");
                s.close();
            }

            String projectName = single ? pom.artifactId() : pom.gradlePath.substring(pom.gradlePath.lastIndexOf(':') + 1);
            String artifactOverride = pom.artifactId().equals(projectName) ? null : pom.artifactId();
            if (top != null && top.publishing) {
                if (artifactOverride != null) {
                    s.blank();
                    s.line(kts ? "publishing.publications.named<MavenPublication>(\"maven\") { artifactId = " + str(artifactOverride) + " }"
                            : "publishing.publications.maven.artifactId = " + str(artifactOverride));
                }
            } else {
                s.blank();
                s.open("publishing");
                publications(s, kind, artifactOverride);
                publishRepositories(s, pom.distribution());
                s.close();
            }
            return s.toString();
        }

        void dependenciesBlock(Script s, List<String> deps, List<String> constraints) {
            if (deps.isEmpty() && constraints.isEmpty()) return;
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

        void javaBlock(Script s, String javaVersion) {
            s.open(kts && sharedContext ? "configure<JavaPluginExtension>" : "java");
            s.line("sourceCompatibility = JavaVersion.toVersion(" + str(javaVersion) + ")");
            s.line("targetCompatibility = JavaVersion.toVersion(" + str(javaVersion) + ")");
            s.close();
        }

        void encodingBlock(Script s, String encoding) {
            s.open(kts ? "tasks.withType<JavaCompile>().configureEach" : "tasks.withType(JavaCompile).configureEach");
            s.line("options.encoding = " + str(encoding));
            s.close();
        }

        static boolean usesJUnitPlatform(Pom pom, List<org.apache.maven.model.Dependency> deps) {
            for (org.apache.maven.model.Dependency d : deps) {
                String artifact = d.getArtifactId();
                if (artifact != null && (artifact.startsWith("junit-jupiter") || artifact.equals("spring-boot-starter-test"))) {
                    return true;
                }
            }
            return false;
        }

        // Fills deps/constraints with the module's own statements (contributions rendered by a shared
        // parent block are left out). Returns true when JUnit 5 tests are present.
        boolean collectDependencies(Pom pom, Kind kind, List<String> deps, List<String> constraints,
                                    Set<String> usedProps) {
            Shared top = topSharedFor(pom);
            Parent parent = top != null && top.externalPlatform ? null : pom.externalParent();
            List<org.apache.maven.model.Dependency> managed = pom.managed().stream().filter(d -> !isShared(pom, d)).toList();
            List<org.apache.maven.model.Dependency> own = pom.dependencies().stream().filter(d -> !isShared(pom, d)).toList();
            render(pom, kind, parent, managed, own, pom, deps, constraints, usedProps);
            boolean junitPlatform = usesJUnitPlatform(pom, pom.dependencies());
            if (junitPlatform && kind != Kind.PLATFORM && (top == null || !top.junit)) {
                deps.add(call("testRuntimeOnly", str("org.junit.platform:junit-platform-launcher")));
            }
            return junitPlatform;
        }

        // Renders an external parent (as platform), managed dependencies and dependencies. `ctx` resolves
        // placeholders; `self` is the module being written (null for a shared block).
        void render(Pom ctx, Kind kind, Parent parent, List<org.apache.maven.model.Dependency> managed,
                    List<org.apache.maven.model.Dependency> dependencies, Pom self,
                    List<String> deps, List<String> constraints, Set<String> usedProps) {
            String where = ctx.relativePath(repoRoot);
            String apiConfig = kind == Kind.WAR ? "implementation" : "api";

            if (parent != null && parent.getGroupId() != null && parent.getArtifactId() != null) {
                String key = parent.getGroupId() + ":" + parent.getArtifactId();
                String artifact = parent.getArtifactId();
                if ("org.springframework.boot:spring-boot-starter-parent".equals(key)) {
                    artifact = "spring-boot-dependencies";
                    substitutions.put(key, parent.getGroupId() + ":" + artifact);
                    String msg = "parent spring-boot-starter-parent replaced by platform(spring-boot-dependencies)"
                            + " — consider the 'org.springframework.boot' Gradle plugin";
                    if (warnings.stream().noneMatch(w -> w.endsWith(msg))) warnings.add(where + ": " + msg);
                }
                String coords = parent.getGroupId() + ":" + artifact
                        + (parent.getVersion() != null ? ":" + parent.getVersion() : "");
                deps.add(call(apiConfig, "platform(" + str(coords) + ")"));
            }

            for (org.apache.maven.model.Dependency d : managed) {
                String key = resolve(ctx, d.getGroupId()) + ":" + resolve(ctx, d.getArtifactId());
                Pom internal = internalByKey.get(key);
                if (internal != null) {
                    // An imported BOM of this repo: keep its managed versions via the project platform.
                    if ("import".equals(d.getScope()) && internal.kind() == Kind.PLATFORM && internal != self
                            && internal.gradlePath != null) {
                        deps.add(call(apiConfig, "platform(project(" + str(internal.gradlePath) + "))"));
                    }
                    continue;
                }
                String coords = coordinates(ctx, d, usedProps);
                if ("import".equals(d.getScope())) {
                    deps.add(call(apiConfig, "platform(" + str(coords) + ")"));
                } else if (d.getVersion() != null) {
                    String managedScope = d.getScope() == null ? "compile" : resolve(ctx, d.getScope());
                    String constraintConfig = kind == Kind.PLATFORM || managedScope == null ? apiConfig
                            : Objects.requireNonNullElse(configuration(kind, managedScope, false), apiConfig);
                    constraints.add(kts && sharedContext ? "add(" + str(constraintConfig) + ", " + str(coords) + ")"
                            : call(constraintConfig, str(coords)));
                }
            }

            for (org.apache.maven.model.Dependency d : dependencies) {
                String group = resolve(ctx, d.getGroupId());
                String artifact = resolve(ctx, d.getArtifactId());
                String key = group + ":" + artifact;
                String scope = scopeOf(ctx, d);
                if ("system".equals(scope)) {
                    warnings.add(where + ": system-scoped dependency " + key + " not migrated");
                    droppedKeys.add(key);
                    continue;
                }
                String config = configuration(kind, scope, optionalOf(ctx, d));
                if (config == null) {
                    warnings.add(where + ": " + scope + "-scoped dependency " + key
                            + " of a parent pom is not inherited by consumers in Gradle — add it to them");
                    droppedKeys.add(key);
                    continue;
                }
                Pom internal = internalByKey.get(key);
                String notation;
                if (internal != null) {
                    if (internal == self) continue;
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
                            ? "platform(" + str(coordinates(ctx, d, usedProps)) + ")"
                            : str(coordinates(ctx, d, usedProps));
                }
                deps.add(withExclusions(config, notation, exclusionsOf(ctx, d)));
            }
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

        void publications(Script s, Kind kind, String artifactId) {
            String component = switch (kind) {
                case PLATFORM -> "javaPlatform";
                case WAR -> "web";
                default -> "java";
            };
            s.open("publications");
            s.open(kts ? "create<MavenPublication>(\"maven\")" : "maven(MavenPublication)");
            s.line(kts ? "from(components[\"" + component + "\"])" : "from components." + component);
            if (artifactId != null) s.line("artifactId = " + str(artifactId));
            s.close();
            s.close();
        }

        void publishRepositories(Script s, DistributionManagement dm) {
            if (dm == null || (dm.getRepository() == null && dm.getSnapshotRepository() == null)) return;
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

        // ── DSL helpers ──────────────────────────────────────────────────────

        String str(String s) {
            if (kts) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            return s.contains("${") ? "\"" + s.replace("\"", "\\\"") + "\"" : "'" + s.replace("'", "\\'") + "'";
        }

        String call(String config, String argument) {
            return kts ? cfg(config) + "(" + argument + ")" : config + " " + argument;
        }

        String cfg(String config) {
            return kts && sharedContext ? "\"" + config + "\"" : config;
        }

        String plugin(String id) {
            return kts ? "id(\"" + id + "\")" : "id '" + id + "'";
        }

        String withExclusions(String config, String notation, List<Exclusion> exclusions) {
            if (exclusions == null || exclusions.isEmpty()) return call(config, notation);
            StringBuilder sb = new StringBuilder(cfg(config)).append('(').append(notation).append(") {\n");
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
