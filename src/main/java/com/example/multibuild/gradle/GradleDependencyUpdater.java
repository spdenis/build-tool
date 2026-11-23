package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.DependencyUpdater;
import com.example.multibuild.gradle.GradleDeclarations.DependencyRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Updates versions of in-scope dependencies in Gradle builds — the Gradle counterpart of the Maven
// DependencyVersionUpdater. Covers dependencies, constraints and platform() declarations in every
// project script. A literal version is rewritten in place; a version reference ($libVersion,
// ${property('libVersion')}, ...) is followed to its definition (script ext/def/val or gradle.properties)
// and the definition is rewritten, once per property even when several modules use it.
@Component
public class GradleDependencyUpdater implements DependencyUpdater {

    private static final Logger log = LoggerFactory.getLogger(GradleDependencyUpdater.class);

    @Override
    public Set<Path> update(List<Path> repoRoots, Map<String, String> versionByKey) {
        Set<Path> modified = new LinkedHashSet<>();
        for (Path repoRoot : repoRoots) {
            try {
                if (updateRepo(repoRoot, versionByKey)) modified.add(repoRoot);
            } catch (RuntimeException e) {
                log.error("Failed to update dependency versions in {}: {}", repoRoot, e.getMessage());
            }
        }
        return modified;
    }

    private boolean updateRepo(Path repoRoot, Map<String, String> versionByKey) {
        GradleBuild build = GradleBuild.load(repoRoot);
        GradleScript rootScript = GradleScript.read(build.rootProject().buildFile());
        ScriptEdits edits = new ScriptEdits();
        Map<Path, Map<String, String>> propertyEdits = new LinkedHashMap<>();
        Set<String> handledProperties = new LinkedHashSet<>();

        for (GradleBuild.Project project : build.projects) {
            GradleScript script = project.isRoot() ? rootScript : GradleScript.read(project.buildFile());
            if (script == null) continue;
            GradlePropertyResolver resolver = new GradlePropertyResolver(build, project, script, rootScript);

            for (DependencyRef ref : GradleDeclarations.dependencies(script, null)) {
                String newVersion = versionByKey.get(ref.key());
                if (newVersion == null || !ref.hasVersion()) continue;

                if (!ref.isReference()) {
                    if (!newVersion.equals(ref.version())) {
                        log.info("  {} {} -> {}", ref.key(), ref.version(), newVersion);
                        edits.replace(script, ref.versionStart(), ref.versionEnd(), newVersion);
                    }
                    continue;
                }
                GradlePropertyResolver.Location loc = resolver.locate(ref.version());
                if (loc == null || "version".equals(loc.name())) {
                    log.warn("Property for version '{}' of {} not found in {}; skipping",
                            ref.version(), ref.key(), project.buildFile());
                    continue;
                }
                if (!handledProperties.add(loc.id())) continue;
                if (newVersion.equals(loc.value())) continue;
                log.info("  {} property {} {} -> {}", ref.key(), loc.name(), loc.value(), newVersion);
                if (loc.script() != null) {
                    edits.replace(loc.script(), loc.definition().valueStart(), loc.definition().valueEnd(), newVersion);
                } else {
                    propertyEdits.computeIfAbsent(loc.propertiesFile(), k -> new LinkedHashMap<>())
                            .put(loc.name(), newVersion);
                }
            }
        }

        // literal versions in convention plugins (build-logic / buildSrc)
        for (GradleScript convention : ConventionPlugins.load(repoRoot).byId.values()) {
            for (DependencyRef ref : GradleDeclarations.dependencies(convention, null)) {
                String newVersion = versionByKey.get(ref.key());
                if (newVersion == null || !ref.hasVersion() || ref.isReference() || newVersion.equals(ref.version())) continue;
                log.info("  {} {} -> {} ({})", ref.key(), ref.version(), newVersion, convention.file.getFileName());
                edits.replace(convention, ref.versionStart(), ref.versionEnd(), newVersion);
            }
        }
        boolean catalogChanged = updateCatalog(repoRoot, versionByKey);

        boolean changed = !edits.isEmpty() || !propertyEdits.isEmpty() || catalogChanged;
        edits.write();
        propertyEdits.forEach((file, props) -> props.forEach((k, v) -> GradleProperties.set(file, k, v)));
        return changed;
    }

    // gradle/libs.versions.toml: a library's literal version, or the [versions] entry it references
    // (rewritten once even when several libraries share it).
    private boolean updateCatalog(Path repoRoot, Map<String, String> versionByKey) {
        VersionCatalog catalog = VersionCatalog.read(repoRoot);
        if (catalog == null) return false;
        String text = catalog.text;
        for (Map.Entry<String, String> e : versionByKey.entrySet()) {
            VersionCatalog current = VersionCatalog.parse(text);
            for (VersionCatalog.Library lib : current.libraries.values()) {
                if (!lib.key().equals(e.getKey())) continue;
                String old = current.versionOf(lib);
                if (old != null && !old.equals(e.getValue())) {
                    log.info("  {} {} -> {} ({}{})", lib.key(), old, e.getValue(), VersionCatalog.FILE,
                            lib.versionRef() != null ? " [versions] " + lib.versionRef() : "");
                }
            }
            text = current.withVersion(e.getKey(), e.getValue());
        }
        if (text.equals(catalog.text)) return false;
        try {
            java.nio.file.Files.writeString(catalog.file, text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot write " + catalog.file, ex);
        }
        return true;
    }
}
