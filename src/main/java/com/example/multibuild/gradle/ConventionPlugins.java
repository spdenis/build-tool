package com.example.multibuild.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

// Precompiled script plugins of a build (build-logic/ included build or buildSrc/):
// src/main/kotlin/<id>.gradle.kts and src/main/groovy/<id>.gradle, keyed by plugin id.
final class ConventionPlugins {

    private static final List<String> BUILDS = List.of("build-logic", "buildSrc");

    final Map<String, GradleScript> byId = new LinkedHashMap<>();

    static ConventionPlugins load(Path repoRoot) {
        ConventionPlugins result = new ConventionPlugins();
        for (String build : BUILDS) {
            for (String dir : List.of("src/main/kotlin", "src/main/groovy")) {
                Path root = repoRoot.resolve(build).resolve(dir);
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                        String name = f.getFileName().toString();
                        String id = name.endsWith(".gradle.kts") ? name.substring(0, name.length() - 11)
                                : name.endsWith(".gradle") ? name.substring(0, name.length() - 7) : null;
                        if (id == null) continue;
                        GradleScript script = GradleScript.read(f);
                        if (script != null) result.byId.putIfAbsent(id, script);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot list " + root, e);
                }
            }
        }
        return result;
    }

    // The convention scripts reached from the given plugin ids, following the plugins they apply.
    List<GradleScript> reachable(Set<String> pluginIds) {
        List<GradleScript> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(pluginIds);
        while (!queue.isEmpty()) {
            String id = queue.remove(0);
            GradleScript script = byId.get(id);
            if (script == null || !seen.add(id)) continue;
            result.add(script);
            queue.addAll(GradleDeclarations.plugins(script, null));
        }
        return result;
    }

    Set<String> pluginsOf(Set<String> pluginIds) {
        Set<String> result = new LinkedHashSet<>(pluginIds);
        for (GradleScript s : reachable(pluginIds)) result.addAll(GradleDeclarations.plugins(s, null));
        return result;
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }
}
