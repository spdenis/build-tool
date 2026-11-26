package com.example.multibuild.gradle;

import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
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
// Default layout (migrate.gradle.layout=build-logic; Kotlin shown, Groovy is equivalent):
//   settings.gradle.kts         pluginManagement { includeBuild("build-logic") }, repositories, includes
//   gradle/libs.versions.toml   every external dependency; Maven version properties → [versions]
//   gradle.properties           group, version
//   build-logic/                included build of precompiled convention plugins:
//     <p>.java-conventions             java-library
//     <p>.lombok-conventions           Lombok (compileOnly / annotationProcessor, main and test), applied by the
//                                      modules whose pom uses Lombok (migrate.gradle.lombok.scope=all: in the base)
//     <p>.publishing-conventions       maven-publish + the single distributionManagement of the repo
//     <p>.<parent>-conventions         per in-repo parent pom: everything the shared root block below holds,
//                                      plus the parent's build plugins (MavenPluginTranslator)
//     <p>.<feature>-conventions        per third-party Gradle plugin a module uses (spring-boot, shadow, ...)
//   <module>/build.gradle.kts   plugins { id("<p>.<parent>-conventions") } + its own dependencies (libs.x)
// <parent> in another repository of the workspace (sibling in the clone directory), see ParentBuildLogic:
//   parent repo  the inherited pom gets a convention ("// maven-parent: g:a") and build-logic is published
//                with the modules (`publish` / `publishToMavenLocal` of the root include it), same group and
//                version; its conventions use literal coordinates since other repos' catalogs lack its aliases
//   child repo   build-logic depends on the convention's plugin marker at the parent's version (one [versions]
//                entry shared with platform(parent)) and its conventions build on the parent's convention;
//                the base / publishing conventions come from the parent repo too
// Dependency semantics (scopes, managed scopes, exclusions, processors) are the same in both layouts.
//
// Root-script layout (migrate.gradle.layout=root-script; Groovy shown; Kotlin DSL is equivalent):
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
//   Annotation processing: Gradle only runs processors on the annotationProcessor path, Maven also discovers
//   them on the compile classpath. Processors from maven-compiler-plugin <annotationProcessorPaths> become
//   annotationProcessor / testAnnotationProcessor dependencies. Without processor paths, a module whose
//   build (or parent) declares maven-compiler-plugin gets implicit processing like Maven: the processor paths
//   extend the compile configurations, so every processor on the classpath runs. Otherwise known processors
//   found on the classpath (Lombok, MapStruct, Hibernate JPA model gen, Spring Boot configuration processor,
//   ...) are added explicitly.
//   <compilerArgs>, <parameters>true</parameters> and <proc> become options.compilerArgs. The compiler
//   configuration is looked up like Maven merges it: module, in-repo parents, then external parents from
//   the local Maven repository (spring-boot-starter-parent implies -parameters).
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
    private static final String LOMBOK = "org.projectlombok:lombok";

    // Result of a conversion. Nothing is written until apply() is called.
    //   internalKeys:  groupId:artifactId of every pom in the repo (modules, aggregators, parents)
    //   droppedKeys:   external dependencies intentionally not carried over (reported in warnings)
    //   substitutions: external keys replaced by an equivalent (e.g. spring-boot-starter-parent → spring-boot-dependencies)
    //   inheritedKeys: external dependencies the conventions of a parent repo provide (e.g. Lombok through the
    //                  parent's lombok convention): they are not in this repo's build files
    public record Conversion(Path repoRoot, GradleDsl dsl, Map<Path, String> writes, List<Path> deletes,
                             List<String> warnings, Set<String> internalKeys, Set<String> droppedKeys,
                             Map<String, String> substitutions, List<ParentBuildLogic.Imported> imports,
                             Set<String> inheritedKeys) {
        // Same conversion that leaves the Maven build in place (migrate.keep.maven=true).
        public Conversion keepingMaven() {
            return new Conversion(repoRoot, dsl, writes, List.of(), warnings, internalKeys, droppedKeys, substitutions, imports, inheritedKeys);
        }
    }

    // The Maven build files of a repo: every pom reachable through <modules>, plus the Maven wrapper.
    public List<Path> mavenBuildFiles(Path repoRoot) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) return List.of();
        Run run = new Run(repoRoot, GradleDsl.GROOVY, Path.of(""), options());
        run.load(rootPom, null, new ArrayDeque<>());
        return run.mavenBuildFiles();
    }

    // Local Maven repository holding external parents / imported BOMs (blank = Maven's default location).
    @org.springframework.beans.factory.annotation.Value("${maven.repo.local:}")
    private String localRepository = "";

    // build-logic: version catalog + convention plugins in an included build-logic build (default);
    // root-script: versions in gradle.properties, parent configuration in blocks of the root build script.
    @org.springframework.beans.factory.annotation.Value("${migrate.gradle.layout:build-logic}")
    private String layout = "build-logic";

    // Prefix of the convention plugin ids (<prefix>.java-conventions); blank = last segment of the root groupId.
    @org.springframework.beans.factory.annotation.Value("${migrate.gradle.conventions.prefix:}")
    private String conventionPrefix = "";

    // Lombok version for the base convention when no pom of the repo (or its BOMs) sets one.
    @org.springframework.beans.factory.annotation.Value("${migrate.gradle.lombok.version:1.18.48}")
    private String lombokVersion = "1.18.48";

    // used (default): Lombok only in modules whose pom uses it (own or inherited dependency, processor path),
    // through <prefix>.lombok-conventions; all: in the base convention of every Java module.
    @org.springframework.beans.factory.annotation.Value("${migrate.gradle.lombok.scope:used}")
    private String lombokScope = "used";

    record Options(boolean conventions, String prefix, String lombokVersion, boolean lombokEverywhere) {}

    private Options options() {
        boolean conventions = !"root-script".equalsIgnoreCase(layout == null ? "" : layout.trim());
        return new Options(conventions, conventionPrefix == null ? "" : conventionPrefix.trim(),
                lombokVersion == null || lombokVersion.isBlank() ? "1.18.48" : lombokVersion.trim(),
                "all".equalsIgnoreCase(lombokScope == null ? "" : lombokScope.trim()));
    }

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
        return convert(repoRoot, dsl, List.of());
    }

    // `workspace`: the other repositories of the estate (siblings in the clone directory). A repo whose poms
    // are the <parent> of poms in another workspace repo publishes its build-logic; a repo whose <parent> is
    // such a pom applies that published convention plugin instead of generating its own conventions.
    public Conversion convert(Path repoRoot, GradleDsl dsl, List<Path> workspace) {
        return convert(repoRoot, dsl, workspace, new HashSet<>(), new ParentBuildLogic.Cache());
    }

    // `visiting`: the repos whose in-memory conversion led here (no cycles); `cache`: shared with them.
    Conversion convert(Path repoRoot, GradleDsl dsl, List<Path> workspace, Set<Path> visiting, ParentBuildLogic.Cache cache) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException("No pom.xml in " + repoRoot);
        }
        Path local = localRepository == null || localRepository.isBlank()
                ? defaultLocalRepository() : Path.of(localRepository);
        Run run = new Run(repoRoot, dsl, local, options());
        run.parentLogic = new ParentBuildLogic(this, repoRoot, workspace, visiting, cache);
        return run.convert(rootPom);
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
        Pom parent;               // in-repo inheritance parent (null when the <parent> is external or absent)
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
        boolean processing;                // annotation processors / compiler args identical in all targets
        boolean implicitProcessing;        // every target discovers processors on its compile classpath

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
        final Set<String> inheritedKeys = new LinkedHashSet<>();
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

        // build-logic layout
        final Options opts;
        final VersionCatalog.Builder catalog = new VersionCatalog.Builder();
        String prefix;
        final Map<Shared, String> conventionIds = new IdentityHashMap<>();
        final Set<String> usedConventionIds = new HashSet<>();
        final Map<String, String> features = new LinkedHashMap<>();                 // id → script
        final Map<String, MavenPluginTranslator.ThirdParty> buildLogicPlugins = new LinkedHashMap<>();
        final Set<String> lombokAliases = new LinkedHashSet<>();
        final Set<String> lombokScopeWarnings = new HashSet<>();
        // cross-repo parents: conventions published for other repos / applied from another repo's build-logic
        ParentBuildLogic parentLogic;
        final Set<Shared> exportedShared = Collections.newSetFromMap(new IdentityHashMap<>());
        // published conventions use literal coordinates: the consuming repo's catalog does not have our aliases
        boolean literalConventions;
        final Map<String, ParentBuildLogic.Imported> appliedImports = new LinkedHashMap<>();
        // versionless aliases whose version comes from a parent repo's catalog
        final Set<String> inheritedAliases = new HashSet<>();

        Run(Path repoRoot, GradleDsl dsl, Path localRepo, Options opts) {
            this.opts = opts;
            this.localRepo = localRepo;
            this.repoRoot = repoRoot;
            this.dsl = dsl;
            this.kts = dsl == GradleDsl.KOTLIN;
        }

        Conversion convert(Path rootPomFile) {
            Pom root = load(rootPomFile, null, new ArrayDeque<>());
            if (root == null) throw new IllegalArgumentException("Cannot read " + rootPomFile);
            for (Pom p : poms) internalByKey.putIfAbsent(p.key(), p);
            linkParents();

            rootGroup = root.groupId();
            rootVersion = root.version();
            if (rootGroup != null) gradleProps.put("group", rootGroup);
            else warnings.add("pom.xml: no groupId — set 'group' in gradle.properties");
            if (rootVersion != null) gradleProps.put("version", rootVersion);
            else warnings.add("pom.xml: no version — set 'version' in gradle.properties");

            reportUnconverted();
            if (!pomRepositoryUrls().isEmpty()) {
                warnings.add("pom <repositories> not migrated (the build declares no repositories; they come from the "
                        + "environment — init script / settings plugin): " + String.join(", ", pomRepositoryUrls()));
            }

            Map<Path, String> writes = new LinkedHashMap<>();
            boolean single = root.kind() != Kind.AGGREGATOR;
            List<Pom> modules = poms.stream()
                    .filter(p -> p.kind() != Kind.AGGREGATOR && p.gradlePath != null)
                    .toList();
            if (opts.conventions()) {
                writeBuildLogicLayout(root, single, modules, writes);
            } else if (single) {
                writes.put(repoRoot.resolve(dsl.buildFile()), moduleScript(root, true));
            } else {
                planSharing(modules);
                writes.put(repoRoot.resolve(dsl.buildFile()), rootScript(modules));
                for (Pom m : modules) {
                    writes.put(m.dir.resolve(dsl.buildFile()), moduleScript(m, false));
                }
            }
            if (!opts.conventions()) {
                writes.put(repoRoot.resolve(dsl.settingsFile()), settings(root, single ? List.of() : modules));
            }
            writes.put(repoRoot.resolve(GradleProperties.FILE_NAME), gradleProperties());
            writes.put(repoRoot.resolve(".gitignore"), gitignore());

            List<Path> deletes = mavenBuildFiles();

            Set<String> internalKeys = new LinkedHashSet<>();
            for (Pom p : poms) internalKeys.add(p.key());
            if (parentLogic != null) parentLogic.warnings.stream().distinct().forEach(warnings::add);
            return new Conversion(repoRoot, dsl, writes, deletes, List.copyOf(warnings),
                    internalKeys, droppedKeys, substitutions, List.copyOf(appliedImports.values()), Set.copyOf(inheritedKeys));
        }

        // ── build-logic layout ───────────────────────────────────────────────

        // settings (+ includeBuild), gradle/libs.versions.toml, build-logic with the convention plugins
        // (base, publishing, one per in-repo parent, one per third-party Gradle plugin used by a module) and
        // module scripts that apply a convention and declare their own dependencies through the catalog.
        void writeBuildLogicLayout(Pom root, boolean single, List<Pom> modules, Map<Path, String> writes) {
            prefix = !opts.prefix().isBlank() ? opts.prefix() : defaultPrefix(root);
            usedConventionIds.add(baseId());
            usedConventionIds.add(publishingId());
            usedConventionIds.add(lombokId());
            for (Pom p : poms) {
                ParentBuildLogic.Imported imp = importedFor(p);
                if (imp == null) continue;
                usedConventionIds.addAll(imp.pluginIds());
                applyImport(p, imp);
            }
            List<Pom> built = single ? List.of(root) : modules;
            // a single-module repo whose parent pom is in another repo: like a module, it applies a convention
            // of this build-logic, which builds on the parent's (its platform and catalog versions)
            if (single && root.kind() != Kind.PLATFORM && importedFor(root) != null) singleConvention = uniqueId(root.artifactId());
            if (!single) {
                planSharing(modules);
                for (Shared sh : sharing) conventionIds.put(sh, uniqueId(sh.parent.artifactId()
                        + (sh.perKind && sh.kind == Kind.WAR ? "-war" : "")));
                // a parent declaring Lombok as a dependency gives it to all its children (as in Maven)
                for (Shared sh : sharing) {
                    if (!opts.lombokEverywhere() && declaresLombok(sh.parent) && sh.targets.stream().allMatch(this::usesLombok)) {
                        lombokShared.add(sh);
                    }
                }
            }
            planExports();
            Map<Path, String> scripts = new LinkedHashMap<>();
            if (singleConvention != null) scripts.put(conventionFile(singleConvention), singleConventionScript(root));
            for (Pom m : built) scripts.put(m.dir.resolve(dsl.buildFile()), moduleScript(m, single));
            if (!single) scripts.put(repoRoot.resolve(dsl.buildFile()), "// " + HEADER + "\n\n"
                    + "// The build is configured by the convention plugins in build-logic/ and the versions in\n"
                    + "// gradle/libs.versions.toml; each module applies its convention in its own build script.\n");
            for (Shared sh : sharing) scripts.put(conventionFile(conventionIds.get(sh)), conventionScript(sh));
            // with a parent in another repo the modules build on that repo's conventions: the base and
            // publishing conventions are only generated when something here still applies them
            boolean baseUsed = appliedImports.isEmpty() || scripts.values().stream().anyMatch(t -> t.contains(plugin(baseId())));
            boolean publishingUsed = baseUsed || scripts.values().stream().anyMatch(t -> t.contains(plugin(publishingId())));
            if (publishingUsed) scripts.put(conventionFile(publishingId()), publishingConvention(root, built));
            else warnImportedPublishing(built);
            if (baseUsed) scripts.put(conventionFile(baseId()), baseConvention(built));
            if (lombokApplied) scripts.put(conventionFile(lombokId()), lombokConvention(built));
            features.forEach((id, script) -> scripts.put(conventionFile(id), script));
            wrappers.forEach((id, imported) -> scripts.put(conventionFile(id), wrapperConvention(imported)));
            for (String id : List.of(baseId(), publishingId(), lombokId())) {
                if (scripts.containsKey(conventionFile(id)) && appliedImports.values().stream().anyMatch(i -> i.pluginIds().contains(id))) {
                    warnings.add("convention plugin " + id + " is also published by " + appliedImports.values().stream()
                            .filter(i -> i.pluginIds().contains(id)).findFirst().orElseThrow().repo().getFileName()
                            + " — set migrate.gradle.conventions.prefix so the ids of the two builds differ");
                }
            }
            scripts.put(repoRoot.resolve("build-logic").resolve(dsl.buildFile()), buildLogicScript(root));
            scripts.put(repoRoot.resolve("build-logic").resolve(dsl.settingsFile()), buildLogicSettings(root));
            if (!exportedShared.isEmpty()) {
                Path rootBuild = repoRoot.resolve(dsl.buildFile());
                scripts.put(rootBuild, scripts.get(rootBuild) + publishBuildLogicWiring(root));
            }
            scripts.put(repoRoot.resolve(dsl.settingsFile()), conventionSettings(root, single ? List.of() : modules));
            scripts.forEach((path, text) -> writes.put(path, resolveLibraryTokens(text)));
            writes.put(repoRoot.resolve(VersionCatalog.FILE), catalog.render(HEADER));
        }

        // Convention of a single-module repo with its parent pom in another repo (null otherwise).
        String singleConvention;

        String singleConventionScript(Pom root) {
            String parentId = baseOf(root);         // registers the import (catalog ref of the parent version)
            sharedContext = true;
            Set<String> usedProps = new LinkedHashSet<>();
            List<String> deps = new ArrayList<>();
            List<String> constraints = new ArrayList<>();
            render(root, root.kind(), root.externalParent(), List.of(), List.of(), null, deps, constraints, usedProps);
            Script s = new Script();
            Parent ext = root.externalParent();
            conventionHeader(s, "Configuration inherited from the parent pom " + ext.getGroupId() + ":" + ext.getArtifactId()
                    + " (another repository): its convention, managed versions and catalog", List.of(parentId));
            dependenciesBlock(s, deps, constraints);
            ParentBuildLogic.Imported parentCatalog = importApplied(List.of(parentId));
            if (parentCatalog != null) parentCatalogConstraints(s, parentCatalog);
            sharedContext = false;
            return s.toString();
        }

        String defaultPrefix(Pom root) {
            String group = root.groupId();
            String last = group != null ? group.substring(group.lastIndexOf('.') + 1) : root.artifactId();
            return VersionCatalog.Builder.sanitize(last).replace("-", "");
        }

        String baseId() { return prefix + ".java-conventions"; }

        String publishingId() { return prefix + ".publishing-conventions"; }

        String lombokId() { return prefix + ".lombok-conventions"; }

        final Set<Shared> lombokShared = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean lombokApplied;

        static boolean isLombok(org.apache.maven.model.Dependency d) {
            return "org.projectlombok".equals(d.getGroupId()) && "lombok".equals(d.getArtifactId());
        }

        boolean lombokProcessorPath(Pom pom) {
            CompilerSetting paths = compilerSetting(pom, "annotationProcessorPaths");
            if (paths == null) return false;
            for (Xpp3Dom path : paths.node().getChildren()) {
                if ("org.projectlombok".equals(text(path, "groupId")) && "lombok".equals(text(path, "artifactId"))) return true;
            }
            return false;
        }

        // The module compiles with Lombok: own or inherited dependency, or a compiler processor path.
        boolean usesLombok(Pom pom) {
            return pom.dependencies().stream().anyMatch(Run::isLombok) || lombokProcessorPath(pom);
        }

        // The parent itself (or its in-repo parents) declares Lombok as a dependency.
        boolean declaresLombok(Pom parent) {
            return parent.dependencies().stream().anyMatch(Run::isLombok);
        }

        // Does a module need to apply the Lombok convention itself (not through a parent's convention)?
        boolean appliesLombok(Pom pom) {
            if (opts.lombokEverywhere() || !usesLombok(pom)) return false;
            if (sharedFor(pom).stream().anyMatch(lombokShared::contains)) return false;
            ParentBuildLogic.Imported imp = sharedFor(pom).isEmpty() ? importedFor(pom) : null;
            if (imp == null || !imp.appliesLombok()) return true;
            inheritedKeys.add(LOMBOK);                 // the parent repo's convention applies it
            return false;
        }

        String uniqueId(String name) {
            String base = prefix + "." + VersionCatalog.Builder.sanitize(name).replaceFirst("-conventions$", "");
            String id = base + "-conventions";
            for (int i = 2; !usedConventionIds.add(id); i++) id = base + "-" + i + "-conventions";
            return id;
        }

        Path conventionFile(String id) {
            return repoRoot.resolve("build-logic/src/main/" + (kts ? "kotlin/" + id + ".gradle.kts" : "groovy/" + id + ".gradle"));
        }

        // Convention plugin applied by a module: the one of its nearest in-repo parent, else the base.
        String conventionOf(Pom pom) {
            Shared nearest = null;
            for (Shared sh : sharedFor(pom)) {
                if (nearest == null || inheritsFrom(sh.parent, nearest.parent)) nearest = sh;
            }
            return nearest != null ? conventionIds.get(nearest) : baseOf(pom);
        }

        // ── parents in other repositories ────────────────────────────────

        // The convention published by another workspace repo for the pom's (external) <parent>, or null.
        ParentBuildLogic.Imported importedFor(Pom pom) {
            if (parentLogic == null || !opts.conventions()) return null;
            Parent ext = pom.externalParent();
            if (ext == null || ext.getGroupId() == null || ext.getArtifactId() == null) return null;
            return parentLogic.importedFor(ext.getGroupId() + ":" + ext.getArtifactId(), dsl);
        }

        // The convention a module (or parent convention) without an in-repo parent convention builds on.
        String baseOf(Pom pom) {
            ParentBuildLogic.Imported imp = importedFor(pom);
            if (imp == null) return baseId();
            applyImport(pom, imp);
            return imp.pluginId();
        }

        String publishingOf(Pom pom) {
            ParentBuildLogic.Imported imp = importedFor(pom);
            if (imp == null || imp.publishingId() == null) return publishingId();
            applyImport(pom, imp);
            return imp.publishingId();
        }

        // Lombok convention for a module / parent convention: the parent repo's when it has one.
        String lombokOf(Pom pom) {
            ParentBuildLogic.Imported imp = importedFor(pom);
            if (imp != null && imp.lombokId() != null) {
                applyImport(pom, imp);
                inheritedKeys.add(LOMBOK);             // in the parent repo's build-logic
                return imp.lombokId();
            }
            lombokApplied = true;
            return lombokId();
        }

        final Map<String, String> importRefs = new LinkedHashMap<>();       // parent key → [versions] name

        // The parent repo's build-logic goes on this build-logic's classpath (through its plugin marker),
        // at the version of the <parent>, which the platform(parent) dependency shares in the catalog.
        void applyImport(Pom pom, ParentBuildLogic.Imported imp) {
            boolean first = appliedImports.putIfAbsent(imp.parentKey(), imp) == null;
            importRef(pom, pom.externalParent());
            if (!first || imp.catalog() == null) return;
            // the parent's conventions resolve `libs` in this build: its aliases they look up are declared
            // here too, without a version (the parent catalog's constraints give it)
            for (String a : imp.aliases()) {
                VersionCatalog.Library lib = imp.catalog().byAccessor(a);
                if (lib == null) {
                    warnings.add("convention " + imp.pluginId() + " looks up library '" + a + "', which the catalog of "
                            + imp.repo().getFileName() + " does not declare — add it to " + VersionCatalog.FILE);
                    continue;
                }
                String alias = catalog.libraryAs(lib.alias(), lib.group(), lib.artifact());
                inheritedAliases.add(alias);
                if (LOMBOK.equals(lib.key())) lombokAliases.add(alias);
            }
            for (String v : imp.versionNames()) {
                VersionCatalog.Version version = imp.catalog().versions.get(v);
                if (version == null) continue;
                catalog.version(v, version.value());
                warnings.add("convention " + imp.pluginId() + " looks up version '" + v + "': copied from the catalog of "
                        + imp.repo().getFileName() + " to " + VersionCatalog.FILE + " — keep both in sync");
            }
        }

        // Local convention of this build-logic applying a parent repo's plugin, by that plugin's id.
        final Map<String, String> wrappers = new LinkedHashMap<>();       // local id → imported id

        // The plugin a module script applies: a parent repo's plugin only through a convention of this
        // build-logic, since the projects only see the plugins the included build-logic defines (the parent's
        // jar is a dependency of build-logic: its plugins are found from build-logic's own conventions).
        String local(String id) {
            if (!isImportedPlugin(id)) return id;
            for (Map.Entry<String, String> e : wrappers.entrySet()) if (e.getValue().equals(id)) return e.getKey();
            String local = uniqueId(id.replace('.', '-'));
            wrappers.put(local, id);
            return local;
        }

        String wrapperConvention(String imported) {
            Script s = new Script();
            ParentBuildLogic.Imported imp = appliedImports.values().stream()
                    .filter(i -> i.pluginIds().contains(imported)).findFirst().orElseThrow();
            conventionHeader(s, "The convention " + imported + " of " + imp.repo().getFileName()
                    + " (a parent repo: its build-logic is a dependency of this build-logic)", List.of(imported), false);
            return s.toString();
        }

        boolean isImportedPlugin(String id) {
            return appliedImports.values().stream().anyMatch(i -> i.pluginIds().contains(id));
        }

        // Name of the imported catalog of a parent repo in this build (settings versionCatalogs).
        String parentCatalogName(ParentBuildLogic.Imported imp) {
            int i = 1;
            for (ParentBuildLogic.Imported other : appliedImports.values()) {
                if (other.catalogCoordinates() == null) continue;
                if (other == imp) return i == 1 ? "parentLibs" : "parentLibs" + i;
                i++;
            }
            return "parentLibs";
        }

        // The imported parent convention among plugin ids applied by a script, or null.
        ParentBuildLogic.Imported importApplied(Collection<String> ids) {
            for (ParentBuildLogic.Imported imp : appliedImports.values()) {
                if (ids.contains(imp.pluginId()) && imp.catalogCoordinates() != null) return imp;
            }
            return null;
        }

        // Every library of the parent repo's catalog constrains the module's versions: what the catalog
        // declares without a version (third-party libraries) gets the parent's, like the dependencyManagement a
        // Maven child inherits. The constraints are published with the module (dependencyManagement in its pom).
        void parentCatalogConstraints(Script s, ParentBuildLogic.Imported imp) {
            s.blank();
            s.line("// Versions of the parent repo's catalog (" + imp.catalogCoordinates() + "): the libraries of");
            s.line("// gradle/libs.versions.toml declared without a version get them, like Maven's inherited dependencyManagement.");
            String name = parentCatalogName(imp);
            if (kts) {
                s.line("val parentCatalog = the<VersionCatalogsExtension>().named(" + str(name) + ")");
                s.open("configurations.matching { it.name in setOf(\"implementation\", \"annotationProcessor\", \"testAnnotationProcessor\") }.configureEach");
                s.line("val configuration = name");
                s.openLine("parentCatalog.libraryAliases.forEach { alias ->");
                s.line("val library = parentCatalog.findLibrary(alias).get().get()");
                s.line("val version = library.versionConstraint.requiredVersion");
                s.line("if (version.isNotEmpty()) project.dependencies.constraints.add(configuration, \"${library.module}:$version\")");
            } else {
                s.line("def parentCatalog = extensions.getByType(VersionCatalogsExtension).named(" + str(name) + ")");
                s.openLine("configurations.matching { it.name in ['implementation', 'annotationProcessor', 'testAnnotationProcessor'] }.configureEach { configuration ->");
                s.openLine("parentCatalog.libraryAliases.each { alias ->");
                s.line("def library = parentCatalog.findLibrary(alias).get().get()");
                s.line("def version = library.versionConstraint.requiredVersion");
                s.line("if (version) project.dependencies.constraints.add(configuration.name, \"${library.module}:${version}\")");
            }
            s.close();
            s.close();
        }

        String importRef(Pom ctx, Parent ext) {
            String key = ext.getGroupId() + ":" + ext.getArtifactId();
            String ref = importRefs.get(key);
            if (ref != null) return ref;
            String version = ext.getVersion() == null ? null : resolve(ctx, ext.getVersion());
            if (version == null) {
                warnings.add(ctx.relativePath(repoRoot) + ": version of parent " + key + " could not be resolved — set it in "
                        + VersionCatalog.FILE);
                version = "TODO";
            }
            ref = catalog.version(VersionCatalog.Builder.sanitize(ext.getArtifactId()), version);
            importRefs.put(key, ref);
            return ref;
        }

        // The poms of this repo that are the <parent> of poms in other workspace repos get a convention
        // plugin (an existing parent convention, or one holding what the pom passes on to its children) and
        // build-logic is published, so those repos can apply it.
        void planExports() {
            if (parentLogic == null) return;
            Set<String> consumed = parentLogic.consumedParents();
            Set<String> consumedIds = parentLogic.consumedPluginIds();
            for (Pom p : poms) {
                if (p.kind() != Kind.PLATFORM && p.kind() != Kind.AGGREGATOR) continue;
                String expectedId = prefix + "." + VersionCatalog.Builder.sanitize(p.artifactId()) + "-conventions";
                if (!consumed.contains(p.key()) && !consumedIds.contains(expectedId)) continue;
                Shared sh = sharing.stream().filter(x -> x.parent == p && x.kind == Kind.LIBRARY).findFirst().orElse(null);
                if (sh == null) {
                    sh = exportShared(p);
                    sharing.add(sh);
                    conventionIds.put(sh, uniqueId(p.artifactId()));
                }
                exportedShared.add(sh);
            }
            literalConventions = !exportedShared.isEmpty();
        }

        // What a parent pom passes on to children in other repos: dependencies, dependencyManagement (a BOM
        // reaches them as platform(parent) instead), Java version, encoding, JUnit platform, build plugins.
        Shared exportShared(Pom p) {
            Shared sh = new Shared(p, List.of(), Kind.LIBRARY, true, false);
            boolean outer = sharing.stream().anyMatch(o -> o.kind == Kind.LIBRARY && inheritsFrom(p, o.parent));
            String where = p.relativePath(repoRoot);
            for (org.apache.maven.model.Dependency d : outer ? p.model.getDependencies() : p.dependencies()) {
                if (exportable(p, d, where)) sh.deps.add(d);
            }
            if (p.kind() != Kind.PLATFORM) {
                List<org.apache.maven.model.Dependency> managed = outer
                        ? (p.model.getDependencyManagement() != null ? p.model.getDependencyManagement().getDependencies() : List.of())
                        : p.managed();
                for (org.apache.maven.model.Dependency d : managed) if (exportable(p, d, where)) sh.managed.add(d);
            }
            sh.shared.addAll(sh.deps);
            sh.shared.addAll(sh.managed);
            sh.externalPlatform = !outer && p.parent == null && p.model.getParent() != null;
            sh.java = javaVersion(p);
            sh.encoding = resolve(p, p.properties().get("project.build.sourceEncoding"));
            sh.junit = usesJUnitPlatform(p, p.dependencies());
            if (!opts.lombokEverywhere() && declaresLombok(p)) lombokShared.add(sh);
            return sh;
        }

        boolean exportable(Pom p, org.apache.maven.model.Dependency d, String where) {
            String key = resolve(p, d.getGroupId()) + ":" + resolve(p, d.getArtifactId());
            if (!internalByKey.containsKey(key)) return true;
            warnings.add(where + ": " + key + " (a module of this repo) is not passed on to the children of "
                    + p.key() + " in other repos — they declare it themselves");
            return false;
        }

        void warnImportedPublishing(List<Pom> built) {
            for (Pom m : built) {
                if (m.distribution() == null) continue;
                ParentBuildLogic.Imported imp = importedFor(m);
                warnings.add(m.relativePath(repoRoot) + ": distributionManagement " + urls(m.distribution())
                        + " is not used — the modules publish through " + (imp != null ? imp.publishingId() + " of "
                        + imp.repo().getFileName() : "the parent repo's publishing convention"));
                return;
            }
        }

        // `publish` / `publishToMavenLocal` of the root also publish build-logic (the convention plugins
        // other repos apply), like `mvn deploy` / `mvn install` publish a parent pom.
        String publishBuildLogicWiring(Pom root) {
            Script s = new Script();
            s.blank();
            s.line("// the convention plugins are published with the modules: other repositories apply them");
            if (kts) {
                s.openLine("listOf(\"publish\", \"publishToMavenLocal\").forEach { name ->");
                s.line("tasks.maybeCreate(name).dependsOn(gradle.includedBuild(" + str("build-logic") + ").task(\":$name\"))");
            } else {
                s.openLine("['publish', 'publishToMavenLocal'].each { name ->");
                s.line("tasks.maybeCreate(name).dependsOn(gradle.includedBuild(" + str("build-logic") + ").task(\":$name\"))");
            }
            s.close();
            return "\n" + s;
        }

        // The convention a parent's convention builds on: the enclosing parent's, else the base.
        String outerConvention(Shared sh) {
            Shared outer = null;
            for (Shared o : sharing) {
                if (o == sh || o.kind != sh.kind || !inheritsFrom(sh.parent, o.parent)) continue;
                if (outer == null || inheritsFrom(o.parent, outer.parent)) outer = o;
            }
            return outer != null ? conventionIds.get(outer) : baseOf(sh.parent);
        }

        static final String LIB_TOKEN = "\u0001lib:";
        static final String FIND_TOKEN = "\u0001find:";

        String resolveLibraryTokens(String text) {
            Matcher m = Pattern.compile("\u0001(lib|find):([a-z0-9-]+)\u0001").matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String alias = m.group(2);
                String replacement = m.group(1).equals("lib") ? catalog.accessor(alias)
                        : (kts ? "libs.findLibrary(\"" + alias + "\").get()" : "libs.findLibrary('" + alias + "').get()");
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        // Script reference to a catalog library: type-safe accessor in build scripts, findLibrary in
        // precompiled convention plugins (which have no generated catalog accessors).
        String libraryRef(String alias) {
            if (sharedContext && literalConventions) return str(catalog.coordinates(alias));
            return (sharedContext ? FIND_TOKEN : LIB_TOKEN) + alias + "\u0001";
        }

        // Catalog version name for a Maven property: spring.version → spring, version.lombok → lombok.
        static String versionName(String property) {
            String name = property.replaceFirst("(?i)[._-]?version$", "").replaceFirst("(?i)^version[._-]", "");
            return VersionCatalog.Builder.sanitize(name.isEmpty() ? property : name);
        }

        // Catalog library for g:a with the Maven version expression `version` (literal, ${prop} or null).
        String catalogLibrary(Pom ctx, String group, String artifact, String version) {
            return catalogLibrary(ctx, group, artifact, version, true);
        }

        // inherit: a third-party library of the parent repo's catalog is declared without a version (the parent
        // catalog's constraints give it) unless the pom sets a different one. Not for build-logic's plugins.
        String catalogLibrary(Pom ctx, String group, String artifact, String version, boolean inherit) {
            ParentBuildLogic.Imported imp = inherit ? importedFor(ctx) : null;
            VersionCatalog.Library inherited = imp != null && imp.catalog() != null && imp.catalogCoordinates() != null
                    ? imp.catalog().libraries.values().stream().filter(l -> l.key().equals(group + ":" + artifact)).findFirst().orElse(null)
                    : null;
            if (inherited != null) {
                String own = version == null || version.isBlank() ? null : resolve(ctx, version);
                String parentVersion = imp.catalog().versionOf(inherited);
                if (own == null || own.equals(parentVersion)) {
                    applyImport(ctx, imp);
                    String alias = catalog.libraryAs(inherited.alias(), group, artifact);
                    inheritedAliases.add(alias);
                    if (LOMBOK.equals(group + ":" + artifact)) lombokAliases.add(alias);
                    return alias;
                }
                warnings.add(ctx.relativePath(repoRoot) + ": " + group + ":" + artifact + " " + own + " differs from "
                        + parentVersion + " in the catalog of " + imp.repo().getFileName() + " — kept; Gradle resolves the "
                        + "higher of the two (use a strict version to force " + own + ")");
            }
            String literal = null;
            String ref = null;
            if (version != null && !version.isBlank()) {
                Matcher whole = Pattern.compile("^\\$\\{([^}]+)}$").matcher(version.trim());
                if (whole.matches() && !PROJECT_VERSION_PROPS.contains(whole.group(1))) {
                    String value = resolve(ctx, version);
                    if (value == null) {
                        warnings.add(ctx.relativePath(repoRoot) + ": property '" + whole.group(1) + "' (version of " + group + ":"
                                + artifact + ") is not defined in this repository — set it in " + VersionCatalog.FILE);
                        value = "TODO";
                    }
                    ref = catalog.version(versionName(whole.group(1)), value);
                } else {
                    literal = resolve(ctx, version);
                    if (literal == null) {
                        warnings.add(ctx.relativePath(repoRoot) + ": version '" + version + "' of " + group + ":" + artifact
                                + " could not be resolved — set it in " + VersionCatalog.FILE);
                        literal = "TODO";
                    }
                }
            }
            String alias = catalog.library(group, artifact, literal, ref);
            if (LOMBOK.equals(group + ":" + artifact)) lombokAliases.add(alias);
            return alias;
        }

        String conventionSettings(Pom root, List<Pom> modules) {
            Script s = new Script();
            s.line("// " + HEADER);
            s.blank();
            s.open("pluginManagement");
            s.line("// convention plugins of this build: build-logic/src/main/" + (kts ? "kotlin" : "groovy"));
            s.line(kts ? "includeBuild(\"build-logic\")" : "includeBuild 'build-logic'");
            s.close();
            // no repositories: they come from the environment (init script / settings plugin of the estate)
            List<ParentBuildLogic.Imported> catalogs = appliedImports.values().stream()
                    .filter(i -> i.catalogCoordinates() != null).toList();
            if (!catalogs.isEmpty()) {
                s.blank();
                s.open("dependencyResolutionManagement");
                s.line("// the version catalog of the parent repo, published with its build-logic: versions of the third-party");
                s.line("// libraries (gradle/libs.versions.toml declares them without one); bumped with the parent version");
                s.open("versionCatalogs");
                for (ParentBuildLogic.Imported imp : catalogs) {
                    String version = catalog.versionValue(importRefs.get(imp.parentKey()));
                    s.open(kts ? "create(" + str(parentCatalogName(imp)) + ")" : parentCatalogName(imp));
                    s.line("from(" + str(imp.catalogCoordinates() + ":" + version) + ")");
                    s.close();
                }
                s.close();
                s.close();
            }
            s.blank();
            s.line("rootProject.name = " + str(root.artifactId()));
            if (!modules.isEmpty()) s.blank();
            for (Pom m : modules) {
                String path = m.gradlePath.substring(1);
                s.line(kts ? "include(" + str(path) + ")" : "include " + str(path));
            }
            return s.toString();
        }

        // Root project name of build-logic: the artifactId of its jar when it is published (the included build
        // itself is still addressed by its directory name, gradle.includedBuild("build-logic")).
        String buildLogicName(Pom root) {
            return exportedShared.isEmpty() ? "build-logic" : root.artifactId() + "-build-logic";
        }

        String buildLogicSettings(Pom root) {
            Script s = new Script();
            s.line("// " + HEADER);
            s.blank();
            s.open("dependencyResolutionManagement");
            s.line("// the main build's catalog, so plugin versions are kept in one place");
            s.open("versionCatalogs");
            s.open(kts ? "create(\"libs\")" : "libs");
            s.line(kts ? "from(files(\"../gradle/libs.versions.toml\"))" : "from(files('../gradle/libs.versions.toml'))");
            s.close();
            s.close();
            s.close();
            s.blank();
            s.line("rootProject.name = " + str(buildLogicName(root)));
            return s.toString();
        }

        String buildLogicScript(Pom root) {
            boolean published = !exportedShared.isEmpty();
            Script s = new Script();
            s.line("// " + HEADER);
            s.blank();
            if (published && kts) {
                // `java` is the java extension inside the script, so java.util.Properties needs an import
                s.line("import java.util.Properties");
                s.blank();
            }
            s.open("plugins");
            s.line(kts ? "`kotlin-dsl`" : "id 'groovy-gradle-plugin'");
            if (published) {
                s.line(kts ? "`maven-publish`" : "id 'maven-publish'");
                s.line(kts ? "`version-catalog`" : "id 'version-catalog'");
            }
            s.close();
            if (published) {
                s.blank();
                s.line("// Published with the modules (one plugin marker per convention, and gradle/libs.versions.toml as a");
                s.line("// version catalog), so the children of this repo's parent poms in other repositories apply these");
                s.line("// conventions and take their versions from this catalog. Same group and version as the modules.");
                if (kts) {
                    s.line("val repoProperties = Properties()");
                    s.line("file(\"../gradle.properties\").inputStream().use { repoProperties.load(it) }");
                    s.line("group = repoProperties.getProperty(\"group\")");
                    s.line("version = repoProperties.getProperty(\"version\")");
                } else {
                    s.line("def repoProperties = new Properties()");
                    s.line("file('../gradle.properties').withInputStream { repoProperties.load(it) }");
                    s.line("group = repoProperties.getProperty('group')");
                    s.line("version = repoProperties.getProperty('version')");
                }
                s.blank();
                s.open("catalog");
                s.open("versionCatalog");
                s.line(kts ? "from(files(\"../gradle/libs.versions.toml\"))" : "from(files('../gradle/libs.versions.toml'))");
                s.close();
                s.close();
                DistributionManagement dm = root.model.getDistributionManagement();
                if (dm == null) dm = poms.stream().map(Pom::distribution).filter(Objects::nonNull).findFirst().orElse(null);
                s.blank();
                s.open("publishing");
                s.open("publications");
                s.open(kts ? "create<MavenPublication>(\"catalog\")" : "catalog(MavenPublication)");
                s.line("artifactId = " + str(root.artifactId() + ParentBuildLogic.CATALOG_SUFFIX));
                s.line(kts ? "from(components[\"versionCatalog\"])" : "from components.versionCatalog");
                s.close();
                s.close();
                if (dm != null && (dm.getRepository() != null || dm.getSnapshotRepository() != null)) publishRepositories(s, dm);
                s.close();
            }
            if (!buildLogicPlugins.isEmpty() || !appliedImports.isEmpty()) {
                s.blank();
                s.open("dependencies");
                boolean saved = sharedContext;
                sharedContext = false;
                if (!buildLogicPlugins.isEmpty()) s.line("// Gradle plugins applied by the convention plugins");
                for (MavenPluginTranslator.ThirdParty tp : buildLogicPlugins.values()) {
                    String alias = pluginLibrary(tp);
                    s.line(call("implementation", LIB_TOKEN + alias + "\u0001"));
                }
                if (!appliedImports.isEmpty()) {
                    s.line("// convention plugins of the parent poms in other repositories (their build-logic, by plugin marker)");
                }
                for (ParentBuildLogic.Imported imp : appliedImports.values()) {
                    String alias = catalog.library(imp.markerGroup(), imp.markerArtifact(), null, importRefs.get(imp.parentKey()));
                    s.line(call("implementation", LIB_TOKEN + alias + "\u0001"));
                }
                sharedContext = saved;
                s.close();
            }
            return s.toString();
        }

        Set<String> pomRepositoryUrls() {
            Set<String> urls = new LinkedHashSet<>();
            for (Pom p : poms) {
                for (Repository r : p.model.getRepositories()) {
                    String url = r.getUrl() == null ? null : r.getUrl().replaceAll("/+$", "");
                    if (url != null && !CENTRAL_URLS.contains(url)) urls.add(url);
                }
            }
            return urls;
        }

        String pluginLibrary(MavenPluginTranslator.ThirdParty tp) {
            Pom ctx = buildLogicPluginContext.getOrDefault(tp.pluginId(), poms.get(0));
            return catalogLibrary(ctx, tp.group(), tp.artifact(), tp.version(), false);
        }

        // Script header of a convention plugin: plugins block and the catalog handle.
        void conventionHeader(Script s, String comment, List<String> pluginIds) {
            conventionHeader(s, comment, pluginIds, true);
        }

        void conventionHeader(Script s, String comment, List<String> pluginIds, boolean catalogHandle) {
            s.line("// " + HEADER);
            s.line("// " + comment);
            s.blank();
            // Kotlin: a parent repo's convention is applied in the body, not in plugins { }: kotlin-dsl applies the
            // plugins { } of a precompiled script to a bare project to generate accessors, and a convention
            // looking up the catalog of the build applying it fails there
            List<String> imported = kts ? pluginIds.stream().filter(this::isImportedPlugin).toList() : List.of();
            s.open("plugins");
            for (String id : pluginIds) if (!imported.contains(id)) s.line(plugin(id));
            s.close();
            if (!imported.isEmpty()) {
                s.blank();
                s.line("// the parent repo's convention (its build-logic is a dependency of this build-logic)");
                for (String id : imported) s.line("apply(plugin = " + str(id) + ")");
            }
            if (!catalogHandle || literalConventions) return;
            s.blank();
            s.line(kts ? "val libs = the<VersionCatalogsExtension>().named(\"libs\")"
                    : "def libs = extensions.getByType(VersionCatalogsExtension).named('libs')");
        }

        String baseConvention(List<Pom> built) {
            Script s = new Script();
            List<String> ids = List.of("java-library", publishingId());
            sharedContext = true;
            if (!opts.lombokEverywhere()) {
                conventionHeader(s, "Base convention of every Java module of this build.", ids, false);
                sharedContext = false;
                return s.toString();
            }
            conventionHeader(s, "Base convention of every Java module of this build.", ids, true);
            lombokDependencies(s, built);
            sharedContext = false;
            return s.toString();
        }

        // Applied by the modules (or parent conventions) that use Lombok.
        String lombokConvention(List<Pom> built) {
            Script s = new Script();
            sharedContext = true;
            conventionHeader(s, "Lombok for the modules whose pom uses it (provided dependency + annotation processor in Maven).",
                    List.of("java-library"), true);
            lombokDependencies(s, built);
            sharedContext = false;
            return s.toString();
        }

        void lombokDependencies(Script s, List<Pom> built) {
            String alias = lombokAliases.stream().filter(a -> catalog.hasVersion(a) || inheritedAliases.contains(a))
                    .findFirst().orElse(null);
            if (alias == null) {
                org.apache.maven.model.Dependency probe = new org.apache.maven.model.Dependency();
                probe.setGroupId("org.projectlombok");
                probe.setArtifactId("lombok");
                String version = null;
                for (Pom m : built) {
                    org.apache.maven.model.Dependency managed = managedFor(m, probe);
                    if (managed != null && managed.getVersion() != null && resolve(m, managed.getVersion()) != null) {
                        version = resolve(m, managed.getVersion());
                        break;
                    }
                }
                alias = catalogLibrary(built.get(0), "org.projectlombok", "lombok", version != null ? version : opts.lombokVersion());
            }
            Set<String> versions = new TreeSet<>();
            for (String a : lombokAliases) if (catalog.hasVersion(a)) versions.add(catalog.versionOf(a));
            if (versions.size() > 1) {
                warnings.add("Lombok versions " + versions + " are used in the repo — the Lombok convention uses "
                        + catalog.versionOf(alias) + " for every module");
            }
            s.blank();
            s.open("dependencies");
            s.line("// Lombok for main and test code (provided dependency + annotation processor in Maven)");
            String lombok = libraryRef(alias);
            for (String config : List.of("compileOnly", "annotationProcessor", "testCompileOnly", "testAnnotationProcessor")) {
                s.line(call(config, lombok));
            }
            s.close();
        }

        // maven-publish, the publication of the module's component and the repositories of the single
        // distributionManagement of the repo.
        String publishingConvention(Pom root, List<Pom> built) {
            DistributionManagement dm = root.model.getDistributionManagement();
            if (dm == null) {
                dm = built.stream().map(Pom::distribution).filter(Objects::nonNull).findFirst().orElse(null);
            }
            for (Pom m : built) {
                DistributionManagement own = m.distribution();
                if (own != null && dm != null && !Objects.equals(urls(own), urls(dm))) {
                    warnings.add(m.relativePath(repoRoot) + ": distributionManagement " + urls(own)
                            + " differs from the one of the repo — the module is now published to " + urls(dm));
                }
            }
            sharedContext = true;
            Script s = new Script();
            s.line("// " + HEADER);
            s.line("// Publishing of every module: `publish` replaces `mvn deploy`, `publishToMavenLocal` `mvn install`.");
            s.line("// The repositories (Maven <distributionManagement>) are defined here only.");
            s.blank();
            s.open("plugins");
            s.line(plugin("maven-publish"));
            s.close();
            s.blank();
            s.open(kts ? "configure<PublishingExtension>" : "publishing");
            s.open("publications");
            if (kts) {
                s.open("create<MavenPublication>(\"maven\")");
                s.line("val publication = this");
                s.line("// the component of the module's packaging, known once all of its plugins are applied");
                s.open("afterEvaluate");
                s.line("publication.from(components[when {");
                s.line("    plugins.hasPlugin(\"java-platform\") -> \"javaPlatform\"");
                s.line("    plugins.hasPlugin(\"war\") -> \"web\"");
                s.line("    else -> \"java\"");
                s.line("}])");
                s.close();
                s.close();
            } else {
                s.openLine("maven(MavenPublication) { publication ->");
                s.line("// the component of the module's packaging, known once all of its plugins are applied");
                s.open("afterEvaluate");
                s.line("publication.from(components[plugins.hasPlugin('java-platform') ? 'javaPlatform'");
                s.line("        : plugins.hasPlugin('war') ? 'web' : 'java'])");
                s.close();
                s.close();
            }
            s.close();
            publishRepositories(s, dm);
            s.close();
            sharedContext = false;
            return s.toString();
        }

        static List<String> urls(DistributionManagement dm) {
            List<String> result = new ArrayList<>();
            if (dm.getRepository() != null) result.add(dm.getRepository().getUrl());
            if (dm.getSnapshotRepository() != null) result.add(dm.getSnapshotRepository().getUrl());
            return result;
        }

        // The convention plugin of an in-repo parent pom: what the shared block held, plus its build plugins.
        String conventionScript(Shared sh) {
            sharedContext = true;
            Set<String> usedProps = new LinkedHashSet<>();
            List<String> deps = new ArrayList<>();
            List<String> constraints = new ArrayList<>();
            render(sh.parent, sh.kind, sh.externalPlatform ? sh.parent.model.getParent() : null,
                    sh.managed, sh.deps, null, deps, constraints, usedProps);
            if (sh.junit) deps.add(call("testRuntimeOnly", str("org.junit.platform:junit-platform-launcher")));
            Processing processing = sh.processing ? processing(sh.targets.get(0), usedProps) : null;
            if (processing != null) deps.addAll(processing.dependencies());
            MavenPluginTranslator.Result plugins = translatePlugins(sh.parent, sh.kind,
                    sh.junit || sh.targets.stream().anyMatch(t -> usesJUnitPlatform(t, t.dependencies())), true);

            List<String> ids = new ArrayList<>();
            ids.add(outerConvention(sh));
            if (sh.kind == Kind.WAR) ids.add("war");
            if (lombokShared.contains(sh)) ids.add(lombokOf(sh.parent));
            ids.addAll(plugins.corePlugins);
            ids.addAll(plugins.thirdParty.keySet());
            Script s = new Script();
            String comment = "Shared configuration of the modules inheriting " + sh.parent.relativePath(repoRoot)
                    + " (" + sh.parent.key() + (sh.perKind ? ", " + (sh.kind == Kind.WAR ? "war" : "jar") + " modules" : "") + ")";
            if (exportedShared.contains(sh)) {
                comment += "\n// Published with build-logic and applied by the children of this pom in other repositories."
                        + "\n" + ParentBuildLogic.MARKER + sh.parent.key();
            }
            conventionHeader(s, comment, ids);
            if (sh.java != null) {
                s.blank();
                javaBlock(s, sh.java);
            }
            dependenciesBlock(s, deps, constraints);
            ParentBuildLogic.Imported parentCatalog = importApplied(List.of(ids.get(0)));
            if (parentCatalog != null) parentCatalogConstraints(s, parentCatalog);
            if (sh.implicitProcessing) {
                s.blank();
                s.line("// annotation processors are discovered on the compile classpath, like maven-compiler-plugin does");
                implicitProcessingBlock(s);
            }
            if (sh.encoding != null) {
                s.blank();
                encodingBlock(s, sh.encoding);
            }
            if (processing != null && !processing.compilerArgs().isEmpty()) {
                s.blank();
                compilerArgsBlock(s, processing.compilerArgs());
            }
            if (sh.junit) {
                s.blank();
                s.open(kts ? "tasks.withType<Test>().configureEach" : "tasks.withType(Test).configureEach");
                s.line("useJUnitPlatform()");
                s.close();
            }
            for (String block : plugins.blocks) {
                s.blank();
                s.raw(block);
            }
            sharedContext = false;
            return s.toString();
        }

        // Build plugins a pom declares, with their configuration merged with pluginManagement of the pom
        // and its in-repo parents (as Maven does).
        MavenPluginTranslator.Result translatePlugins(Pom pom, Kind kind, boolean junit, boolean convention) {
            List<MavenPluginTranslator.MavenPlugin> plugins = new ArrayList<>();
            if (pom.model.getBuild() != null) {
                for (Plugin plugin : pom.model.getBuild().getPlugins()) plugins.add(effectivePlugin(pom, plugin));
            }
            String bootVersion = springBootVersion(pom);
            MavenPluginTranslator.Result result = MavenPluginTranslator.translate(plugins, new MavenPluginTranslator.Context() {
                public boolean kts() { return kts; }
                public String where() { return pom.relativePath(repoRoot); }
                public String resolve(String raw) { return Run.this.resolve(pom, raw); }
                public boolean junitPlatform() { return junit; }
                public String springBootVersion() { return bootVersion; }
                public boolean war() { return kind == Kind.WAR; }
            });
            warnings.addAll(result.warnings);
            for (MavenPluginTranslator.ThirdParty tp : result.thirdParty.values()) {
                // the version is resolved in the context of the (first) declaring pom
                if (buildLogicPlugins.putIfAbsent(tp.pluginId(), tp) == null) buildLogicPluginContext.put(tp.pluginId(), pom);
            }
            return result;
        }

        final Map<String, Pom> buildLogicPluginContext = new LinkedHashMap<>();

        MavenPluginTranslator.MavenPlugin effectivePlugin(Pom pom, Plugin plugin) {
            String key = plugin.getKey();
            Xpp3Dom config = plugin.getConfiguration() instanceof Xpp3Dom c ? new Xpp3Dom(c) : null;
            String version = plugin.getVersion();
            Map<String, String> dependencies = new LinkedHashMap<>();
            plugin.getDependencies().forEach(d -> dependencies.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion()));
            List<PluginExecution> executions = new ArrayList<>(plugin.getExecutions());
            for (Pom p = pom; p != null; p = p.parent) {
                if (p.model.getBuild() == null || p.model.getBuild().getPluginManagement() == null) continue;
                for (Plugin managed : p.model.getBuild().getPluginManagement().getPlugins()) {
                    if (!managed.getKey().equals(key)) continue;
                    if (managed.getConfiguration() instanceof Xpp3Dom mc) {
                        config = config == null ? new Xpp3Dom(mc) : Xpp3Dom.mergeXpp3Dom(config, new Xpp3Dom(mc));
                    }
                    if (version == null) version = managed.getVersion();
                    managed.getDependencies().forEach(d -> dependencies.putIfAbsent(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion()));
                    for (PluginExecution e : managed.getExecutions()) {
                        if (executions.stream().noneMatch(x -> x.getId().equals(e.getId()))) executions.add(e);
                    }
                }
            }
            List<MavenPluginTranslator.Execution> execs = new ArrayList<>();
            for (PluginExecution e : executions) {
                execs.add(new MavenPluginTranslator.Execution(e.getId(), e.getPhase(), e.getGoals(),
                        e.getConfiguration() instanceof Xpp3Dom c ? c : null));
            }
            return new MavenPluginTranslator.MavenPlugin(plugin.getGroupId(), plugin.getArtifactId(), version, config,
                    execs, dependencies);
        }

        // Maven expression of the Spring Boot version: spring-boot-starter-parent (in the parent chain) or an
        // imported spring-boot-dependencies BOM.
        String springBootVersion(Pom pom) {
            Parent ext = pom.externalParent();
            if (ext != null && "org.springframework.boot".equals(ext.getGroupId())) return ext.getVersion();
            for (org.apache.maven.model.Dependency d : pom.managed()) {
                if ("import".equals(d.getScope()) && "spring-boot-dependencies".equals(d.getArtifactId())) return d.getVersion();
            }
            return null;
        }

        // A third-party plugin used by a module gets its own small convention that applies it.
        String featureConvention(String pluginId) {
            String name = switch (pluginId) {
                case MavenPluginTranslator.SPRING_BOOT -> "spring-boot";
                case MavenPluginTranslator.SHADOW -> "shadow";
                case MavenPluginTranslator.SPOTBUGS -> "spotbugs";
                default -> pluginId.substring(pluginId.lastIndexOf('.') + 1);
            };
            for (Map.Entry<String, String> e : features.entrySet()) {
                if (e.getValue().contains(plugin(pluginId))) return e.getKey();
            }
            String id = uniqueId(name);
            Script s = new Script();
            s.line("// " + HEADER);
            s.line("// Applies the " + pluginId + " Gradle plugin (on the build-logic classpath, version in gradle/libs.versions.toml).");
            s.blank();
            s.open("plugins");
            s.line(plugin(pluginId));
            s.close();
            features.put(id, s.toString());
            return id;
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

        // A <parent> that is a pom of this repository but not an aggregator above the module (the common
        // "parent/" sibling module reached via relativePath) is still the module's inheritance parent.
        void linkParents() {
            for (Pom p : poms) {
                Parent declared = p.model.getParent();
                if (p.parent != null || declared == null) continue;
                Pom candidate = internalByKey.get(declared.getGroupId() + ":" + declared.getArtifactId());
                if (candidate == null || candidate == p || inheritsFrom(candidate, p)) continue;
                p.parent = candidate;
            }
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
                if (p.model.getBuild() != null && !opts.conventions()) {   // build-logic: the plugin translator reports
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
            boolean first = true;
            for (Shared shared : sharing) {
                if (!first) s.blank();
                first = false;
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
                List<Pom> inheritors = poms.stream().filter(q -> inheritsFrom(q, parent)).toList();
                if (inheritors.isEmpty()) continue;
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
                    // An intermediate parent adding nothing: the outer block already applies the plugins.
                    // (build-logic layout: a parent declaring build plugins still gets its own convention)
                    boolean buildPlugins = opts.conventions() && parent.model.getBuild() != null
                            && !parent.model.getBuild().getPlugins().isEmpty();
                    if (!top && sh.deps.isEmpty() && sh.managed.isEmpty() && !buildPlugins) continue;
                    if (top) {
                        sh.externalPlatform = parent.parent == null && parent.model.getParent() != null;
                        sh.java = common(targets, this::javaVersion);
                        sh.encoding = common(targets, t -> resolve(t, t.properties().get("project.build.sourceEncoding")));
                        sh.junit = targets.stream().allMatch(t -> usesJUnitPlatform(t, t.dependencies()));
                        Processing first = processing(targets.get(0), new LinkedHashSet<>());
                        sh.processing = !first.isEmpty()
                                && targets.stream().allMatch(t -> processing(t, new LinkedHashSet<>()).equals(first));
                        sh.implicitProcessing = targets.stream().allMatch(t -> processing(t, new LinkedHashSet<>()).implicit());
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
            Processing processing = sh.processing ? processing(sh.targets.get(0), usedProps) : null;
            if (processing != null) deps.addAll(processing.dependencies());
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
            if (sh.implicitProcessing) {
                s.blank();
                s.line("// annotation processors are discovered on the compile classpath, like maven-compiler-plugin does");
                implicitProcessingBlock(s);
            }

            if (sh.encoding != null) {
                s.blank();
                encodingBlock(s, sh.encoding);
            }
            if (processing != null && !processing.compilerArgs().isEmpty()) {
                s.blank();
                compilerArgsBlock(s, processing.compilerArgs());
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
            Processing processing = top != null && top.processing ? null : processing(pom, usedProps);
            if (processing != null) deps.addAll(processing.dependencies());

            s.line("// " + HEADER);
            boolean parentPom = poms.stream().anyMatch(q -> q.parent == pom);
            MavenPluginTranslator.Result ownPlugins = opts.conventions() && kind != Kind.PLATFORM && !parentPom
                    ? translatePlugins(pom, kind, junitPlatform, false) : null;
            if (opts.conventions()) {
                s.blank();
                s.open("plugins");
                if (kind == Kind.PLATFORM) {
                    s.line(plugin("java-platform"));
                    s.line(plugin(local(publishingOf(pom))));
                } else {
                    s.line(plugin(local(single ? (singleConvention != null ? singleConvention : baseOf(pom)) : conventionOf(pom))));
                    if (kind == Kind.WAR) s.line(plugin("war"));
                    if (appliesLombok(pom)) s.line(plugin(local(lombokOf(pom))));
                    for (String id : ownPlugins.corePlugins) s.line(plugin(id));
                    for (String id : ownPlugins.thirdParty.keySet()) s.line(plugin(featureConvention(id)));
                }
                s.close();
            } else if (!pluginsShared || kts) {
                // Groovy scripts see plugins applied by the root's shared block; Kotlin scripts still declare
                // them so the type-safe accessors (api, java, publishing ...) are generated.
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

            dependenciesBlock(s, deps, constraints);
            if (opts.conventions() && kind != Kind.PLATFORM && !(single && singleConvention != null)) {
                ParentBuildLogic.Imported parentCatalog = importApplied(List.of(single ? baseOf(pom) : conventionOf(pom)));
                if (parentCatalog != null) parentCatalogConstraints(s, parentCatalog);
            }
            if (processing != null && processing.implicit() && (top == null || !top.implicitProcessing)) {
                s.blank();
                s.line("// annotation processors are discovered on the compile classpath, like maven-compiler-plugin does");
                implicitProcessingBlock(s);
            }

            String encoding = kind != Kind.PLATFORM && (top == null || top.encoding == null)
                    ? resolve(pom, pom.properties().get("project.build.sourceEncoding")) : null;
            if (encoding != null) {
                s.blank();
                encodingBlock(s, encoding);
            }
            if (processing != null && !processing.compilerArgs().isEmpty()) {
                s.blank();
                compilerArgsBlock(s, processing.compilerArgs());
            }
            if (junitPlatform && kind != Kind.PLATFORM && (top == null || !top.junit)) {
                s.blank();
                s.open(kts ? "tasks.named<Test>(\"test\")" : "tasks.named('test', Test)");
                s.line("useJUnitPlatform()");
                s.close();
            }

            String projectName = single ? pom.artifactId() : pom.gradlePath.substring(pom.gradlePath.lastIndexOf(':') + 1);
            String artifactOverride = pom.artifactId().equals(projectName) ? null : pom.artifactId();
            if (opts.conventions()) {
                // publication and repositories come from the publishing convention
                if (artifactOverride != null) {
                    s.blank();
                    s.line(kts ? "publishing.publications.named<MavenPublication>(\"maven\") { artifactId = " + str(artifactOverride) + " }"
                            : "publishing.publications.maven.artifactId = " + str(artifactOverride));
                }
                if (ownPlugins != null) {
                    for (String block : ownPlugins.blocks) {
                        s.blank();
                        s.raw(block);
                    }
                }
            } else if (top != null && top.publishing) {
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
            if (singleConvention != null) parent = null;      // the parent's platform is in the single module's convention
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
                String notation = importRefs.containsKey(key)
                        ? libraryRef(catalog.library(parent.getGroupId(), artifact, null, importRefs.get(key)))
                        : externalNotation(ctx, parent.getGroupId(), artifact, parent.getVersion());
                deps.add(call(apiConfig, "platform(" + notation + ")"));
            }

            for (org.apache.maven.model.Dependency d : managed) {
                String key = resolve(ctx, d.getGroupId()) + ":" + resolve(ctx, d.getArtifactId());
                Pom internal = internalByKey.get(key);
                if (internal != null) {
                    // An imported BOM of this repo: keep its managed versions via the project platform.
                    if ("import".equals(d.getScope()) && internal.kind() == Kind.PLATFORM && internal != self
                            && internal.gradlePath != null) {
                        deps.add(call(apiConfig, "platform(project(" + str(internal.gradlePath) + "))"));
                    } else if (kind == Kind.PLATFORM && self == ctx && !"import".equals(d.getScope())
                            && internal.kind() != Kind.AGGREGATOR && internal.gradlePath != null) {
                        // a BOM listing modules of this repo: the platform constrains them to this build's version
                        String project = "project(" + str(internal.gradlePath) + ")";
                        constraints.add(kts && sharedContext ? "add(\"api\", " + project + ")" : call("api", project));
                    }
                    continue;
                }
                if (opts.conventions() && LOMBOK.equals(key)) {
                    notation(ctx, d, usedProps);      // its version feeds the base convention's Lombok
                    continue;
                }
                if (!"import".equals(d.getScope()) && d.getVersion() == null) continue;
                String coords = notation(ctx, d, usedProps);
                if ("import".equals(d.getScope())) {
                    deps.add(call(apiConfig, "platform(" + coords + ")"));
                } else {
                    String managedScope = d.getScope() == null ? "compile" : resolve(ctx, d.getScope());
                    String constraintConfig = kind == Kind.PLATFORM || managedScope == null ? apiConfig
                            : Objects.requireNonNullElse(configuration(kind, managedScope, false), apiConfig);
                    constraints.add(kts && sharedContext ? "add(" + str(constraintConfig) + ", " + coords + ")"
                            : call(constraintConfig, coords));
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
                if (opts.conventions() && LOMBOK.equals(key) && !internalByKey.containsKey(key)) {
                    notation(ctx, d, usedProps);      // its version feeds the base convention's Lombok
                    if (!"provided".equals(scope) && !optionalOf(ctx, d) && lombokScopeWarnings.add(where)) {
                        warnings.add(where + ": Lombok (" + scope + " scope) is compileOnly in the base convention now"
                                + " — it is no longer passed on to consumers");
                    }
                    continue;
                }
                String config = configuration(kind, scope, optionalOf(ctx, d));
                if (config == null) {
                    // A parent pom's own dependencies reach its in-repo children through the shared block.
                    boolean inheritedInRepo = self != null && poms.stream().anyMatch(q -> q.parent == self);
                    if (!inheritedInRepo) warnings.add(where + ": " + scope + "-scoped dependency " + key
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
                            ? "platform(" + notation(ctx, d, usedProps) + ")"
                            : notation(ctx, d, usedProps);
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

        // Script notation of an external dependency: a catalog reference in the build-logic layout,
        // otherwise a "g:a:v" string.
        String notation(Pom ctx, org.apache.maven.model.Dependency d, Set<String> usedProps) {
            if (!opts.conventions()) return str(coordinates(ctx, d, usedProps));
            String group = resolve(ctx, d.getGroupId());
            String artifact = resolve(ctx, d.getArtifactId());
            if (group == null || artifact == null) return str(coordinates(ctx, d, usedProps));
            String classifier = d.getClassifier();
            if ("test-jar".equals(d.getType()) && classifier == null) classifier = "tests";
            String version = d.getVersion();
            // managed in this repo: the same catalog entry as the dependencyManagement declaration
            if (version == null) version = declaredManagedVersion(ctx, group + ":" + artifact);
            String alias = catalogLibrary(ctx, group, artifact, version);
            if (classifier != null && sharedContext && literalConventions) {
                return str(catalog.coordinates(alias) + (catalog.hasVersion(alias) ? ":" + classifier : ""));
            }
            String ref = libraryRef(alias);
            return classifier == null ? ref : "variantOf(" + ref + ") { classifier(" + str(classifier) + ") }";
        }

        String externalNotation(Pom ctx, String group, String artifact, String version) {
            if (!opts.conventions()) return str(group + ":" + artifact + (version != null ? ":" + version : ""));
            return libraryRef(catalogLibrary(ctx, group, artifact, version));
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

        // ── annotation processing / compiler arguments ───────────────────────

        // Processors Maven discovers on the classpath (META-INF/services) and Gradle does not.
        static final Set<String> KNOWN_PROCESSORS = Set.of(
                "org.projectlombok:lombok", "org.projectlombok:lombok-mapstruct-binding",
                "org.mapstruct:mapstruct-processor",
                "org.hibernate:hibernate-jpamodelgen", "org.hibernate.orm:hibernate-jpamodelgen",
                "org.springframework.boot:spring-boot-configuration-processor",
                "org.springframework.boot:spring-boot-autoconfigure-processor",
                "com.google.auto.value:auto-value", "com.google.auto.service:auto-service",
                "org.immutables:value", "com.google.dagger:dagger-compiler", "com.querydsl:querydsl-apt",
                "io.micronaut:micronaut-inject-java", "info.picocli:picocli-codegen");

        // A compiler plugin setting and the properties of the (external) pom it was found in.
        record CompilerSetting(Xpp3Dom node, Map<String, String> externalProps) {}

        // implicit: the processor paths extend the compile configurations (Maven's classpath discovery).
        record Processing(List<String> dependencies, List<String> compilerArgs, boolean implicit) {
            boolean isEmpty() { return dependencies.isEmpty() && compilerArgs.isEmpty() && !implicit; }
        }

        // True when maven-compiler-plugin is declared by the module, an in-repo parent or an external parent.
        boolean compilerPluginDeclared(Pom pom) {
            for (Pom p = pom; p != null; p = p.parent) {
                if (!compilerPlugins(p.model).isEmpty()) return true;
            }
            Parent ext = pom.externalParent();
            for (int depth = 0; ext != null && depth < 15; depth++) {
                if (ext.getGroupId() == null || ext.getVersion() == null || ext.getVersion().contains("${")) break;
                if ("spring-boot-starter-parent".equals(ext.getArtifactId())) return true;   // configures it
                Model model = externalModel(ext.getGroupId(), ext.getArtifactId(), ext.getVersion());
                if (model == null) break;
                if (!compilerPlugins(model).isEmpty()) return true;
                ext = model.getParent();
            }
            return false;
        }

        static List<Plugin> compilerPlugins(Model model) {
            List<Plugin> result = new ArrayList<>();
            if (model.getBuild() == null) return result;
            for (Plugin p : model.getBuild().getPlugins()) {
                if ("maven-compiler-plugin".equals(p.getArtifactId())) result.add(p);
            }
            if (model.getBuild().getPluginManagement() != null) {
                for (Plugin p : model.getBuild().getPluginManagement().getPlugins()) {
                    if ("maven-compiler-plugin".equals(p.getArtifactId())) result.add(p);
                }
            }
            return result;
        }

        static Xpp3Dom child(Plugin plugin, String name) {
            if (plugin.getConfiguration() instanceof Xpp3Dom cfg && cfg.getChild(name) != null) return cfg.getChild(name);
            for (PluginExecution e : plugin.getExecutions()) {
                if (!"default-compile".equals(e.getId())) continue;
                if (e.getConfiguration() instanceof Xpp3Dom cfg && cfg.getChild(name) != null) return cfg.getChild(name);
            }
            return null;
        }

        // Nearest maven-compiler-plugin setting `name`: the module and its in-repo parents, then external parents.
        CompilerSetting compilerSetting(Pom pom, String name) {
            for (Pom p = pom; p != null; p = p.parent) {
                for (Plugin plugin : compilerPlugins(p.model)) {
                    Xpp3Dom node = child(plugin, name);
                    if (node != null) return new CompilerSetting(node, Map.of());
                }
            }
            Parent ext = pom.externalParent();
            for (int depth = 0; ext != null && depth < 15; depth++) {
                if (ext.getGroupId() == null || ext.getVersion() == null || ext.getVersion().contains("${")) break;
                Model model = externalModel(ext.getGroupId(), ext.getArtifactId(), ext.getVersion());
                if (model == null) break;
                for (Plugin plugin : compilerPlugins(model)) {
                    Xpp3Dom node = child(plugin, name);
                    if (node != null) return new CompilerSetting(node, externalProperties(model, 0));
                }
                ext = model.getParent();
            }
            return null;
        }

        String settingValue(Pom pom, CompilerSetting setting, String raw) {
            if (raw == null) return null;
            String v = resolve(pom, raw.trim());
            if (v == null && !setting.externalProps().isEmpty()) v = expand(raw.trim(), setting.externalProps());
            return v;
        }

        Processing processing(Pom pom, Set<String> usedProps) {
            List<String> deps = new ArrayList<>();
            List<String> args = new ArrayList<>();
            if (pom.kind() == Kind.PLATFORM) return new Processing(deps, args, false);
            boolean versionless = false;
            CompilerSetting proc = compilerSetting(pom, "proc");
            String procValue = proc != null ? settingValue(pom, proc, proc.node().getValue()) : null;
            boolean procNone = procValue != null && "none".equals(procValue.trim());
            boolean implicit = false;

            CompilerSetting paths = compilerSetting(pom, "annotationProcessorPaths");
            List<String> processors = new ArrayList<>();
            if (paths != null) {
                for (Xpp3Dom path : paths.node().getChildren()) {
                    org.apache.maven.model.Dependency d = new org.apache.maven.model.Dependency();
                    d.setGroupId(text(path, "groupId"));
                    d.setArtifactId(text(path, "artifactId"));
                    d.setVersion(text(path, "version"));
                    d.setClassifier(text(path, "classifier"));
                    if (d.getGroupId() == null || d.getArtifactId() == null) continue;
                    if (!paths.externalProps().isEmpty()) {
                        d = expanded(d, v -> settingValue(pom, paths, v));
                    }
                    if (d.getVersion() == null) {
                        org.apache.maven.model.Dependency m = managedFor(pom, d);
                        if (m != null && m.getVersion() != null) d.setVersion(m.getVersion());
                    }
                    versionless |= d.getVersion() == null;
                    if (opts.conventions() && LOMBOK.equals(d.getGroupId() + ":" + d.getArtifactId())) continue;
                    String coords = notation(pom, d, usedProps);
                    processors.add(coords);
                    deps.add(call("annotationProcessor", coords));
                }
                for (String c : processors) deps.add(call("testAnnotationProcessor", c));
            } else if (!procNone && compilerPluginDeclared(pom)) {
                // Maven runs every processor on the compile classpath: so does Gradle with extended paths.
                implicit = true;
                for (org.apache.maven.model.Dependency d : pom.dependencies()) {
                    String key = resolve(pom, d.getGroupId()) + ":" + resolve(pom, d.getArtifactId());
                    // provided → compileOnly is not on the test compile classpath in Gradle (it is in Maven)
                    if (opts.conventions() && LOMBOK.equals(key)) continue;   // the base convention has it
                    if (KNOWN_PROCESSORS.contains(key) && "provided".equals(scopeOf(pom, d))) {
                        deps.add(call("testCompileOnly", notation(pom, d, usedProps)));
                    }
                }
            } else if (!procNone) {
                // No compiler configuration at all: add the processors Maven would discover explicitly.
                for (org.apache.maven.model.Dependency d : pom.dependencies()) {
                    String key = resolve(pom, d.getGroupId()) + ":" + resolve(pom, d.getArtifactId());
                    if (!KNOWN_PROCESSORS.contains(key)) continue;
                    if (opts.conventions() && LOMBOK.equals(key)) continue;   // the base convention has it
                    String scope = scopeOf(pom, d);
                    if (d.getVersion() == null) {
                        // the processor path sees no constraints of api/implementation: pin the managed version
                        org.apache.maven.model.Dependency m = managedFor(pom, d);
                        String raw = declaredManagedVersion(pom, key);
                        String version = raw != null ? raw : m != null ? m.getVersion() : null;
                        if (version != null && resolve(pom, version) != null) {
                            d = d.clone();
                            d.setVersion(version);
                        }
                    }
                    String coords = notation(pom, d, usedProps);
                    versionless |= d.getVersion() == null;
                    if ("test".equals(scope)) {
                        deps.add(call("testAnnotationProcessor", coords));
                        continue;
                    }
                    deps.add(call("annotationProcessor", coords));
                    deps.add(call("testAnnotationProcessor", coords));
                    // provided → compileOnly is not on the test compile classpath in Gradle (it is in Maven)
                    if ("provided".equals(scope)) deps.add(call("testCompileOnly", coords));
                }
            }
            if (versionless && !deps.isEmpty()) {
                // processor paths do not see the platforms of api/implementation: add them explicitly
                List<String> platforms = new ArrayList<>();
                for (String platform : platformNotations(pom, usedProps)) {
                    platforms.add(call("annotationProcessor", platform));
                    platforms.add(call("testAnnotationProcessor", platform));
                }
                deps.addAll(0, platforms);
            }

            CompilerSetting compilerArgs = compilerSetting(pom, "compilerArgs");
            if (compilerArgs != null) {
                for (Xpp3Dom arg : compilerArgs.node().getChildren()) {
                    String v = settingValue(pom, compilerArgs, arg.getValue());
                    if (v != null && !v.isBlank()) args.add(v);
                }
            }
            CompilerSetting single = compilerSetting(pom, "compilerArgument");
            if (single != null) {
                String v = settingValue(pom, single, single.node().getValue());
                if (v != null && !v.isBlank()) args.addAll(Arrays.asList(v.trim().split("\\s+")));
            }
            CompilerSetting parameters = compilerSetting(pom, "parameters");
            boolean bootParent = pom.externalParent() != null
                    && "spring-boot-starter-parent".equals(pom.externalParent().getArtifactId());
            if (parameters != null ? "true".equals(settingValue(pom, parameters, parameters.node().getValue())) : bootParent) {
                if (!args.contains("-parameters")) args.add(0, "-parameters");
            }
            if (procValue != null && !procValue.isBlank()) args.add("-proc:" + procValue.trim());
            return new Processing(deps, args, implicit);
        }

        // Version as written in the dependencyManagement of the module or an in-repo parent (e.g.
        // "${lombok.version}"), so the rendered coordinates keep referring to the gradle.properties entry.
        String declaredManagedVersion(Pom pom, String key) {
            for (Pom p = pom; p != null; p = p.parent) {
                if (p.model.getDependencyManagement() == null) continue;
                for (org.apache.maven.model.Dependency m : p.model.getDependencyManagement().getDependencies()) {
                    if ("import".equals(m.getScope()) || m.getVersion() == null) continue;
                    if (key.equals(resolve(p, m.getGroupId()) + ":" + resolve(p, m.getArtifactId()))) return m.getVersion();
                }
            }
            return null;
        }

        static String text(Xpp3Dom node, String child) {
            Xpp3Dom c = node.getChild(child);
            return c == null || c.getValue() == null || c.getValue().isBlank() ? null : c.getValue().trim();
        }

        // platform(...) notations the module gets: external parent (as platform) and imported BOMs.
        List<String> platformNotations(Pom pom, Set<String> usedProps) {
            List<String> result = new ArrayList<>();
            Parent parent = pom.externalParent();
            if (parent != null && parent.getGroupId() != null && parent.getArtifactId() != null) {
                String artifact = "org.springframework.boot:spring-boot-starter-parent"
                        .equals(parent.getGroupId() + ":" + parent.getArtifactId()) ? "spring-boot-dependencies" : parent.getArtifactId();
                result.add("platform(" + externalNotation(pom, parent.getGroupId(), artifact, parent.getVersion()) + ")");
            }
            for (org.apache.maven.model.Dependency d : pom.managed()) {
                if (!"import".equals(d.getScope())) continue;
                Pom internal = internalByKey.get(resolve(pom, d.getGroupId()) + ":" + resolve(pom, d.getArtifactId()));
                if (internal != null) {
                    if (internal.kind() == Kind.PLATFORM && internal.gradlePath != null) {
                        result.add("platform(project(" + str(internal.gradlePath) + "))");
                    }
                } else {
                    result.add("platform(" + notation(pom, d, usedProps) + ")");
                }
            }
            return result;
        }

        void implicitProcessingBlock(Script s) {
            if (kts) {
                s.line("configurations.named(\"annotationProcessor\") { extendsFrom(configurations[\"compileOnly\"], configurations[\"implementation\"]) }");
                s.line("configurations.named(\"testAnnotationProcessor\") { extendsFrom(configurations[\"testCompileOnly\"], configurations[\"testImplementation\"]) }");
            } else {
                s.line("configurations.named('annotationProcessor') { extendsFrom(configurations.compileOnly, configurations.implementation) }");
                s.line("configurations.named('testAnnotationProcessor') { extendsFrom(configurations.testCompileOnly, configurations.testImplementation) }");
            }
        }

        void compilerArgsBlock(Script s, List<String> args) {
            s.open(kts ? "tasks.withType<JavaCompile>().configureEach" : "tasks.withType(JavaCompile).configureEach");
            String list = String.join(", ", args.stream().map(this::str).toList());
            s.line(kts ? "options.compilerArgs.addAll(listOf(" + list + "))" : "options.compilerArgs += [" + list + "]");
            s.close();
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

        // Opens a block whose header already ends with its brace / closure parameters ("x { p ->").
        void openLine(String header) {
            line(header);
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
