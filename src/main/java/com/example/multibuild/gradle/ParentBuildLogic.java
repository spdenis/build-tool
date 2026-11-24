package com.example.multibuild.gradle;

import com.example.multibuild.model.Module;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Convention plugins and the version catalog shared between the repositories of a workspace (the estate's
// clone directory), the Gradle counterpart of a Maven <parent> that lives in another repository:
//   - a pom used as <parent> by a pom of another repo gets a convention plugin in its repo's build-logic, marked
//     with "// maven-parent: g:a"; that build-logic is published with the repo's modules (plugin markers
//     <id>:<id>.gradle.plugin:<version>), together with the repo's gradle/libs.versions.toml as a version
//     catalog (<group>:<rootProject.name>-catalog:<version>);
//   - the inheriting repo depends on that marker in its build-logic and applies the plugin id, so it extends the
//     parent's conventions instead of generating its own, and imports the parent's catalog for the versions of
//     its third-party libraries.
// The parent repo is converted in memory to find its plugin ids. A repo already on Gradle (migrated earlier and
// maybe edited by hand) is read from disk: the convention of a parent pom is the one marked for it, else the one
// named after it (<prefix>.<artifactId>-conventions), else a Java convention its project applies, else the
// repo's base Java convention. Adding the marker line to a convention overrides that choice.
final class ParentBuildLogic {

    private static final Logger log = LoggerFactory.getLogger(ParentBuildLogic.class);

    static final String MARKER = "// maven-parent: ";
    static final String CATALOG_SUFFIX = "-catalog";
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "build", "node_modules", "src", ".gradle");
    private static final Pattern ROOT_NAME = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern FIND_LIBRARY = Pattern.compile("\\bfindLibrary\\(\\s*[\"']([^\"'\\n]+)[\"']\\s*\\)");
    private static final Pattern FIND_VERSION = Pattern.compile("\\bfindVersion\\(\\s*[\"']([^\"'\\n]+)[\"']\\s*\\)");
    private static final Pattern FIND_OTHER = Pattern.compile("\\b(findBundle|findPlugin)\\(\\s*[\"']([^\"'\\n]+)[\"']\\s*\\)");
    private static final Pattern JAVA_PLUGIN = Pattern.compile(
            "`java-library`|`java`|\\bid\\s*\\(?\\s*[\"']java(-library)?[\"']|^\\s*(java|`java-library`)\\s*$", Pattern.MULTILINE);

    // The published convention of a parent pom, the other conventions of its build-logic and its catalog.
    //   catalogCoordinates  group:artifact of the parent repo's published version catalog
    //   catalog             that catalog (gradle/libs.versions.toml of the parent repo), null when it has none
    //   aliases             catalog aliases the parent's conventions look up (findLibrary): the importing repo's
    //                       catalog must have them too, since a convention resolves `libs` in the build applying it
    //   versionNames        [versions] names the parent's conventions look up (findVersion)
    //   onDisk              read from an existing Gradle build, which may still lack the publication of its
    //                       build-logic / catalog (BuildLogicExport adds it)
    record Imported(String parentKey, String pluginId, String publishingId, String lombokId, boolean appliesLombok,
                    Set<String> pluginIds, Path repo, String catalogCoordinates, VersionCatalog catalog,
                    Set<String> aliases, Set<String> versionNames, boolean onDisk) {
        // plugin marker artifact: resolves to the parent repo's build-logic jar
        String markerGroup() { return pluginId; }
        String markerArtifact() { return pluginId + ".gradle.plugin"; }
    }

    // What a workspace repo offers: convention scripts by id, its catalog, root name, group and modules.
    private record RepoInfo(Path repo, Map<String, String> conventions, String catalogText, String rootName,
                            String group, Map<String, Path> moduleDirs, boolean onDisk) {}

    private final MavenToGradleConverter converter;
    private final Path repoRoot;
    private final List<Path> others;
    private final Set<Path> visiting;
    private Set<String> consumed;
    private Set<String> consumedIds;
    private Map<Path, RepoInfo> infos;
    private final Map<String, Optional<Imported>> imports = new HashMap<>();
    final List<String> warnings = new ArrayList<>();

    ParentBuildLogic(MavenToGradleConverter converter, Path repoRoot, List<Path> workspace, Set<Path> visiting) {
        this.converter = converter;
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.others = workspace.stream().map(p -> p.toAbsolutePath().normalize())
                .filter(p -> !p.equals(this.repoRoot) && Files.isDirectory(p)).distinct().toList();
        this.visiting = new HashSet<>(visiting);
        this.visiting.add(this.repoRoot);
    }

    // groupId:artifactId of every pom the poms of the other repos declare as <parent>.
    Set<String> consumedParents() {
        if (consumed != null) return consumed;
        consumed = consumedParents(others);
        return consumed;
    }

    static Set<String> consumedParents(List<Path> repos) {
        Set<String> result = new LinkedHashSet<>();
        for (Path repo : repos) {
            for (Path pom : poms(repo)) {
                Model model = read(pom);
                if (model != null && model.getParent() != null) {
                    result.add(model.getParent().getGroupId() + ":" + model.getParent().getArtifactId());
                }
            }
        }
        return result;
    }

    // Convention plugin ids already applied by other repos migrated without their poms: the plugin markers
    // (<id>:<id>.gradle.plugin) in their version catalogs.
    Set<String> consumedPluginIds() {
        if (consumedIds != null) return consumedIds;
        consumedIds = consumedPluginIds(others);
        return consumedIds;
    }

    static Set<String> consumedPluginIds(List<Path> repos) {
        Set<String> result = new LinkedHashSet<>();
        for (Path repo : repos) {
            if (!Files.isRegularFile(repo.resolve(VersionCatalog.FILE))) continue;
            try {
                for (VersionCatalog.Library lib : VersionCatalog.read(repo).libraries.values()) {
                    if (lib.artifact().equals(lib.group() + ".gradle.plugin")) result.add(lib.group());
                }
            } catch (RuntimeException e) {
                log.debug("  cannot read the catalog of {}: {}", repo.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    // The convention published for parent pom `parentKey` by another repo of the workspace, or null.
    Imported importedFor(String parentKey, GradleDsl dsl) {
        return imports.computeIfAbsent(parentKey, k -> Optional.ofNullable(find(k, dsl))).orElse(null);
    }

    private Imported find(String key, GradleDsl dsl) {
        if (infos == null) {
            infos = new LinkedHashMap<>();
            for (Path repo : others) {
                if (!visiting.contains(repo)) infos.put(repo, info(repo, dsl));
            }
        }
        // a convention marked for the pom wins, in any repo
        for (RepoInfo info : infos.values()) {
            for (Map.Entry<String, String> e : info.conventions.entrySet()) {
                for (String line : e.getValue().split("\n")) {
                    if (line.startsWith(MARKER) && line.substring(MARKER.length()).trim().equals(key)) {
                        return imported(info, key, e.getKey());
                    }
                }
            }
        }
        // an existing Gradle build producing the pom's coordinates
        for (RepoInfo info : infos.values()) {
            if (!info.onDisk || info.conventions.isEmpty()) continue;
            String id = chooseConvention(info, key);
            if (id != null) {
                log.info("  parent {} is built by {}: applying its convention plugin {}", key, info.repo.getFileName(), id);
                return imported(info, key, id);
            }
        }
        return null;
    }

    // Convention of existing Gradle build `repo` standing for each of the parent poms `keys` it produces
    // (marked, else chosen like for an importing repo); keys it does not produce are left out.
    static Map<String, String> conventionsFor(Path repo, Set<String> keys) {
        RepoInfo info = diskInfo(repo);
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            String marked = null;
            for (Map.Entry<String, String> e : info.conventions.entrySet()) {
                if (e.getValue().lines().anyMatch(l -> l.startsWith(MARKER) && l.substring(MARKER.length()).trim().equals(key))) {
                    marked = e.getKey();
                }
            }
            String id = marked != null ? marked : chooseConvention(info, key);
            if (id != null) result.put(key, id);
        }
        return result;
    }

    private Imported imported(RepoInfo info, String key, String id) {
        Map<String, String> conventions = info.conventions;
        String publishing = idMatching(conventions, ".publishing-conventions", "maven-publish");
        String lombok = idMatching(conventions, ".lombok-conventions", "org.projectlombok");
        Set<String> reached = reachable(conventions, id);
        boolean appliesLombok = lombok != null && reached.contains(lombok);
        VersionCatalog catalog = info.catalogText != null ? VersionCatalog.parse(info.catalogText) : null;
        Set<String> aliases = new LinkedHashSet<>();
        Set<String> versionNames = new LinkedHashSet<>();
        for (String c : reached) {
            String text = conventions.get(c);
            Matcher m = FIND_LIBRARY.matcher(text);
            while (m.find()) aliases.add(m.group(1));
            m = FIND_VERSION.matcher(text);
            while (m.find()) versionNames.add(m.group(1));
            m = FIND_OTHER.matcher(text);
            while (m.find()) {
                warnings.add("convention " + c + " of " + info.repo.getFileName() + " looks up " + m.group(1) + "(\""
                        + m.group(2) + "\") in the catalog of the build applying it — declare it in " + VersionCatalog.FILE);
            }
        }
        String coordinates = catalog != null && info.group != null && info.rootName != null
                ? info.group + ":" + info.rootName + CATALOG_SUFFIX : null;
        return new Imported(key, id, publishing, lombok, appliesLombok, Set.copyOf(conventions.keySet()), info.repo,
                coordinates, catalog, aliases, versionNames, info.onDisk);
    }

    // The convention of an existing Gradle build that stands for parent pom `key`, or null when the build does
    // not produce it.
    private static String chooseConvention(RepoInfo info, String key) {
        String artifactId = key.substring(key.indexOf(':') + 1);
        String group = key.substring(0, key.indexOf(':'));
        Path dir = info.moduleDirs.get(key);
        boolean root = artifactId.equals(info.rootName) && group.equals(info.group);
        if (dir == null && !root) return null;
        String suffix = "." + VersionCatalog.Builder.sanitize(artifactId) + "-conventions";
        for (String id : info.conventions.keySet()) if (id.endsWith(suffix)) return id;
        if (dir != null) {
            GradleScript script = GradleScript.read(GradleBuild.buildFileIn(dir));
            if (script != null) {
                for (String id : GradleDeclarations.plugins(script, null)) {
                    if (info.conventions.containsKey(id) && appliesJava(info.conventions, id)) return id;
                }
            }
        }
        for (String id : info.conventions.keySet()) if (id.endsWith(".java-conventions")) return id;
        for (Map.Entry<String, String> e : info.conventions.entrySet()) {
            if (JAVA_PLUGIN.matcher(e.getValue()).find()) return e.getKey();
        }
        return null;
    }

    private static boolean appliesJava(Map<String, String> conventions, String id) {
        for (String c : reachable(conventions, id)) {
            if (JAVA_PLUGIN.matcher(conventions.get(c)).find()) return true;
        }
        return false;
    }

    // The convention `id` and the conventions of the same build it applies, transitively.
    private static Set<String> reachable(Map<String, String> conventions, String id) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (!conventions.containsKey(c) || !seen.add(c)) continue;
            queue.addAll(GradleDeclarations.plugins(new GradleScript(null, conventions.get(c)), null));
        }
        return seen;
    }

    // The repo as converted in memory from its poms, else its Gradle build on disk.
    private RepoInfo info(Path repo, GradleDsl dsl) {
        Path src = repo.resolve("build-logic/src/main");
        if (Files.isRegularFile(repo.resolve("pom.xml")) && !Files.isDirectory(src)) {
            try {
                List<Path> workspace = new ArrayList<>(others);
                workspace.add(repoRoot);
                MavenToGradleConverter.Conversion c = converter.convert(repo, dsl, workspace, visiting);
                Map<String, String> conventions = new LinkedHashMap<>();
                c.writes().forEach((path, text) -> {
                    String id = conventionId(src, path);
                    if (id != null) conventions.put(id, text);
                });
                String settings = c.writes().get(repo.resolve(dsl.settingsFile()));
                String props = c.writes().get(repo.resolve(GradleProperties.FILE_NAME));
                return new RepoInfo(repo, conventions, c.writes().get(repo.resolve(VersionCatalog.FILE)),
                        rootName(settings), property(props, "group"), Map.of(), false);
            } catch (RuntimeException e) {
                log.debug("  cannot convert {} to find its convention plugins: {}", repo.getFileName(), e.getMessage());
            }
        }
        return diskInfo(repo);
    }

    // An existing Gradle build: its build-logic conventions, catalog, root name, group and modules.
    private static RepoInfo diskInfo(Path repo) {
        Path src = repo.resolve("build-logic/src/main");
        Map<String, String> conventions = new LinkedHashMap<>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                    String id = conventionId(src, f);
                    if (id != null) conventions.put(id, Files.readString(f));
                }
            } catch (IOException e) {
                log.debug("  cannot read {}: {}", src, e.getMessage());
            }
        }
        Map<String, Path> moduleDirs = new LinkedHashMap<>();
        String group = null;
        if (!conventions.isEmpty()) {
            try {
                for (Module m : new GradleParser().parse(repo).getModules()) {
                    moduleDirs.putIfAbsent(m.getArtifact().key(), m.getDirectory());
                }
            } catch (RuntimeException e) {
                log.debug("  cannot parse the Gradle build of {}: {}", repo.getFileName(), e.getMessage());
            }
            group = GradleProperties.read(repo.resolve(GradleProperties.FILE_NAME)).get("group");
        }
        return new RepoInfo(repo, conventions, readOrNull(repo.resolve(VersionCatalog.FILE)),
                rootName(readOrNull(settingsFile(repo))), group, moduleDirs, true);
    }

    static Path settingsFile(Path repo) {
        Path kts = repo.resolve("settings.gradle.kts");
        return Files.isRegularFile(kts) ? kts : repo.resolve("settings.gradle");
    }

    static String rootName(String settings) {
        if (settings == null) return null;
        Matcher m = ROOT_NAME.matcher(settings);
        return m.find() ? m.group(1) : null;
    }

    private static String property(String properties, String name) {
        if (properties == null) return null;
        for (String line : properties.split("\n")) {
            String l = line.trim();
            if (l.startsWith(name + "=")) return l.substring(name.length() + 1).trim();
        }
        return null;
    }

    static String readOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String conventionId(Path src, Path file) {
        if (!file.toAbsolutePath().normalize().startsWith(src.toAbsolutePath().normalize())) return null;
        String name = file.getFileName().toString();
        if (name.endsWith(".gradle.kts")) return name.substring(0, name.length() - ".gradle.kts".length());
        if (name.endsWith(".gradle")) return name.substring(0, name.length() - ".gradle".length());
        return null;
    }

    // A convention by its usual id suffix, else the first one whose text contains `content`.
    private static String idMatching(Map<String, String> conventions, String suffix, String content) {
        for (String id : conventions.keySet()) if (id.endsWith(suffix)) return id;
        for (Map.Entry<String, String> e : conventions.entrySet()) {
            if (e.getValue().contains(content)) return e.getKey();
        }
        return null;
    }

    private static List<Path> poms(Path repo) {
        List<Path> result = new ArrayList<>();
        collect(repo, 0, result);
        return result;
    }

    private static void collect(Path dir, int depth, List<Path> result) {
        Path pom = dir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) result.add(pom);
        if (depth >= 6) return;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path c : children.filter(Files::isDirectory).sorted().toList()) {
                if (!SKIPPED_DIRS.contains(c.getFileName().toString())) collect(c, depth + 1, result);
            }
        } catch (IOException e) {
            log.debug("  cannot list {}: {}", dir, e.getMessage());
        }
    }

    private static Model read(Path pom) {
        try (Reader r = Files.newBufferedReader(pom)) {
            return new MavenXpp3Reader().read(r);
        } catch (Exception e) {
            return null;
        }
    }
}
