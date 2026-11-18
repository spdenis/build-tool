package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.ProjectParser;
import com.example.multibuild.gradle.GradleDeclarations.DependencyRef;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Dependency;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepositoryProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Parses a Gradle build (Groovy or Kotlin DSL) into the artifacts it publishes and their dependencies.
//
// Coordinates: group/version come from the project's build script, the root script's
// allprojects/subprojects blocks, or gradle.properties (project dir, then root). The artifactId is the
// publication's artifactId / archivesName when set, otherwise the project name.
//
// A single-project build is always one module. In a multi-project build a subproject is a module when it
// applies a JVM/publishing plugin (directly or via the root's subprojects/allprojects block), has
// src/main, or declares dependencies; the root is a module only if it applies such a plugin itself.
@Component
public class GradleParser implements ProjectParser {

    private static final Logger log = LoggerFactory.getLogger(GradleParser.class);

    private static final Set<String> ARTIFACT_PLUGINS = Set.of(
            "java", "java-library", "war", "java-platform", "application", "groovy", "scala",
            "maven-publish", "org.jetbrains.kotlin.jvm", "kotlin");

    @Override
    public RepositoryProject parse(Path repoDir) {
        GradleBuild build = GradleBuild.load(repoDir);
        GradleScript rootScript = GradleScript.read(build.rootProject().buildFile());

        // Configuration the root script applies to other projects.
        List<int[]> subBlocks = rootScript != null ? rootScript.blocks("subprojects") : List.of();
        List<int[]> allBlocks = rootScript != null ? rootScript.blocks("allprojects") : List.of();
        List<int[]> sharedBlocks = new ArrayList<>(subBlocks);
        sharedBlocks.addAll(allBlocks);
        List<int[]> rootOnly = rootScript != null ? rootScript.complementOf(sharedBlocks) : List.of();

        Set<String> subPlugins = new LinkedHashSet<>();
        List<DependencyRef> subDeps = new ArrayList<>();
        Set<String> allPlugins = new LinkedHashSet<>();
        List<DependencyRef> allDeps = new ArrayList<>();
        String sharedGroup = null;
        String sharedVersion = null;
        if (rootScript != null) {
            if (!subBlocks.isEmpty()) {
                subPlugins.addAll(GradleDeclarations.plugins(rootScript, subBlocks));
                subDeps.addAll(GradleDeclarations.dependencies(rootScript, subBlocks));
            }
            if (!allBlocks.isEmpty()) {
                allPlugins.addAll(GradleDeclarations.plugins(rootScript, allBlocks));
                allDeps.addAll(GradleDeclarations.dependencies(rootScript, allBlocks));
            }
            sharedGroup = GradleDeclarations.group(rootScript, sharedBlocks.isEmpty() ? List.of() : sharedBlocks);
            sharedVersion = GradleDeclarations.version(rootScript, sharedBlocks.isEmpty() ? List.of() : sharedBlocks);
        }

        List<Module> modules = new ArrayList<>();
        for (GradleBuild.Project project : build.projects) {
            boolean root = project.isRoot();
            GradleScript script = root ? rootScript : GradleScript.read(project.buildFile());
            List<int[]> own = root ? rootOnly : null;

            Set<String> plugins = new LinkedHashSet<>(allPlugins);
            if (!root) plugins.addAll(subPlugins);
            List<DependencyRef> deps = new ArrayList<>(allDeps);
            if (!root) deps.addAll(subDeps);
            if (script != null) {
                plugins.addAll(GradleDeclarations.plugins(script, own));
                deps.addAll(GradleDeclarations.dependencies(script, own));
            }

            if (!isModule(build, project, plugins, deps)) continue;

            Map<String, String> props = GradlePropertyResolver.projectProperties(build, project);
            String group = firstNonNull(script != null ? GradleDeclarations.group(script, own) : null,
                    sharedGroup, props.get("group"));
            String version = firstNonNull(script != null ? GradleDeclarations.version(script, own) : null,
                    sharedVersion, props.get("version"));
            String artifactId = script != null ? GradleDeclarations.artifactId(script) : null;
            if (artifactId == null) artifactId = project.name();
            if (group == null) {
                log.warn("Skipping Gradle project {} in {}: no group declared", project.path(), repoDir.getFileName());
                continue;
            }

            GradlePropertyResolver resolver = new GradlePropertyResolver(build, project, script, rootScript);
            List<Dependency> dependencies = new ArrayList<>();
            for (DependencyRef ref : deps) {
                String v = !ref.hasVersion() ? "unknown"
                        : ref.isReference() ? resolver.resolveOr(ref.version(), ref.version())
                        : ref.version();
                dependencies.add(new Dependency(new Artifact(ref.group(), ref.artifact(), v)));
            }
            modules.add(new Module(new Artifact(group, artifactId, version != null ? version : "unknown"),
                    dependencies, project.dir(), repoDir));
        }
        return new RepositoryProject(repoDir.getFileName().toString(), repoDir, modules, List.of());
    }

    private static boolean isModule(GradleBuild build, GradleBuild.Project project,
                                    Set<String> plugins, List<DependencyRef> deps) {
        if (!build.isMultiProject()) return true;
        boolean artifactPlugin = plugins.stream().anyMatch(GradleParser::isArtifactPlugin);
        if (project.isRoot()) return artifactPlugin;
        return artifactPlugin || !deps.isEmpty() || Files.isDirectory(project.dir().resolve("src/main"));
    }

    // Core JVM/publishing plugins, plus convention plugins that conventionally wrap them
    // (e.g. "acme.java-library-conventions").
    private static boolean isArtifactPlugin(String id) {
        if (ARTIFACT_PLUGINS.contains(id)) return true;
        String lower = id.toLowerCase();
        return lower.contains("java") || lower.contains("kotlin") || lower.contains("library")
                || lower.contains("conventions") || lower.contains("publish");
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
