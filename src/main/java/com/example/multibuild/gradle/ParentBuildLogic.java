package com.example.multibuild.gradle;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

// Convention plugins shared between the repositories of a workspace (the estate's clone directory), the Gradle
// counterpart of a Maven <parent> that lives in another repository:
//   - a pom used as <parent> by a pom of another repo gets a convention plugin in its repo's build-logic, marked
//     with "// maven-parent: g:a"; that build-logic is published with the repo's modules (plugin markers
//     <id>:<id>.gradle.plugin:<version>);
//   - the inheriting repo depends on that marker in its build-logic and applies the plugin id, so it extends the
//     parent's conventions instead of generating its own.
// The parent repo is converted in memory to find its plugin ids (or, once its poms are gone, its build-logic is
// read from disk), so the result does not depend on the order in which repos are migrated.
final class ParentBuildLogic {

    private static final Logger log = LoggerFactory.getLogger(ParentBuildLogic.class);

    static final String MARKER = "// maven-parent: ";
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target", "build", "node_modules", "src", ".gradle");

    // The published convention of a parent pom and the other conventions of its build-logic.
    record Imported(String parentKey, String pluginId, String publishingId, String lombokId, boolean appliesLombok,
                    Set<String> pluginIds, Path repo) {
        // plugin marker artifact: resolves to the parent repo's build-logic jar
        String markerGroup() { return pluginId; }
        String markerArtifact() { return pluginId + ".gradle.plugin"; }
    }

    private final MavenToGradleConverter converter;
    private final Path repoRoot;
    private final List<Path> others;
    private final Set<Path> visiting;
    private Set<String> consumed;
    private Map<String, Imported> imports;

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
        consumed = new LinkedHashSet<>();
        for (Path repo : others) {
            for (Path pom : poms(repo)) {
                Model model = read(pom);
                if (model != null && model.getParent() != null) {
                    consumed.add(model.getParent().getGroupId() + ":" + model.getParent().getArtifactId());
                }
            }
        }
        return consumed;
    }

    // Convention plugin ids already applied by other repos migrated without their poms: the plugin markers
    // (<id>:<id>.gradle.plugin) in their version catalogs.
    Set<String> consumedPluginIds() {
        if (consumedIds != null) return consumedIds;
        consumedIds = new LinkedHashSet<>();
        for (Path repo : others) {
            if (!Files.isRegularFile(repo.resolve(VersionCatalog.FILE))) continue;
            try {
                for (VersionCatalog.Library lib : VersionCatalog.read(repo).libraries.values()) {
                    if (lib.artifact().equals(lib.group() + ".gradle.plugin")) consumedIds.add(lib.group());
                }
            } catch (RuntimeException e) {
                log.debug("  cannot read the catalog of {}: {}", repo.getFileName(), e.getMessage());
            }
        }
        return consumedIds;
    }

    private Set<String> consumedIds;

    // The convention published for parent pom `parentKey` by another repo of the workspace, or null.
    Imported importedFor(String parentKey, GradleDsl dsl) {
        if (imports == null) imports = loadImports(dsl);
        return imports.get(parentKey);
    }

    private Map<String, Imported> loadImports(GradleDsl dsl) {
        Map<String, Imported> result = new LinkedHashMap<>();
        for (Path repo : others) {
            if (visiting.contains(repo)) continue;
            Map<String, String> conventions = conventions(repo, dsl);
            String publishing = idEndingWith(conventions, ".publishing-conventions");
            String lombok = idEndingWith(conventions, ".lombok-conventions");
            conventions.forEach((id, text) -> {
                for (String line : text.split("\n")) {
                    if (!line.startsWith(MARKER)) continue;
                    String key = line.substring(MARKER.length()).trim();
                    boolean appliesLombok = lombok != null
                            && (text.contains("\"" + lombok + "\"") || text.contains("'" + lombok + "'"));
                    result.putIfAbsent(key, new Imported(key, id, publishing, lombok, appliesLombok,
                            Set.copyOf(conventions.keySet()), repo));
                }
            });
        }
        return result;
    }

    // id → text of the convention plugins of a repo: generated in memory from its poms, else read from disk.
    private Map<String, String> conventions(Path repo, GradleDsl dsl) {
        Map<String, String> result = new LinkedHashMap<>();
        Path src = repo.resolve("build-logic/src/main");
        if (Files.isRegularFile(repo.resolve("pom.xml"))) {
            try {
                List<Path> workspace = new ArrayList<>(others);
                workspace.add(repoRoot);
                MavenToGradleConverter.Conversion c = converter.convert(repo, dsl, workspace, visiting);
                c.writes().forEach((path, text) -> {
                    String id = conventionId(src, path);
                    if (id != null) result.put(id, text);
                });
                return result;
            } catch (RuntimeException e) {
                log.debug("  cannot convert {} to find its convention plugins: {}", repo.getFileName(), e.getMessage());
            }
        }
        if (!Files.isDirectory(src)) return result;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String id = conventionId(src, f);
                if (id != null) result.put(id, Files.readString(f));
            }
        } catch (IOException e) {
            log.debug("  cannot read {}: {}", src, e.getMessage());
        }
        return result;
    }

    private static String conventionId(Path src, Path file) {
        if (!file.toAbsolutePath().normalize().startsWith(src.toAbsolutePath().normalize())) return null;
        String name = file.getFileName().toString();
        if (name.endsWith(".gradle.kts")) return name.substring(0, name.length() - ".gradle.kts".length());
        if (name.endsWith(".gradle")) return name.substring(0, name.length() - ".gradle".length());
        return null;
    }

    private static String idEndingWith(Map<String, String> conventions, String suffix) {
        return conventions.keySet().stream().filter(id -> id.endsWith(suffix)).findFirst().orElse(null);
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
