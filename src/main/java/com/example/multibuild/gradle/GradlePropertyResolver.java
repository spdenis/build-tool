package com.example.multibuild.gradle;

import com.example.multibuild.gradle.GradleDeclarations.Definition;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// Resolves a version reference ("$libVersion", "${property('libVersion')}", ...) used in a
// project's build script to the place the property is defined, searching in Gradle's lookup order:
// the project's script, the project's gradle.properties, the root script, the root gradle.properties.
final class GradlePropertyResolver {

    // Where a property is defined: either a literal in a script or an entry in a gradle.properties file.
    record Location(String name, String value, GradleScript script, Definition definition, Path propertiesFile) {
        String id() {
            return (script != null ? script.file : propertiesFile) + "#" + name;
        }
    }

    private final GradleBuild build;
    private final GradleBuild.Project project;
    private final GradleScript script;
    private final GradleScript rootScript;

    GradlePropertyResolver(GradleBuild build, GradleBuild.Project project, GradleScript script, GradleScript rootScript) {
        this.build = build;
        this.project = project;
        this.script = script;
        this.rootScript = rootScript;
    }

    // Properties visible to a project from gradle.properties files (project dir overrides root).
    static Map<String, String> projectProperties(GradleBuild build, GradleBuild.Project project) {
        Map<String, String> props = new LinkedHashMap<>(build.rootProperties);
        if (!project.isRoot()) {
            props.putAll(GradleProperties.read(project.dir().resolve(GradleProperties.FILE_NAME)));
        }
        return props;
    }

    Location locate(String reference) {
        String name = GradleDeclarations.referenceName(reference);
        if (name == null) return null;
        if (script != null) {
            Definition d = GradleDeclarations.definition(script, name);
            if (d != null) return new Location(name, d.value(), script, d, null);
        }
        if (!project.isRoot()) {
            Path props = project.dir().resolve(GradleProperties.FILE_NAME);
            String v = GradleProperties.read(props).get(name);
            if (v != null) return new Location(name, v, null, null, props);
        }
        if (rootScript != null && rootScript != script) {
            Definition d = GradleDeclarations.definition(rootScript, name);
            if (d != null) return new Location(name, d.value(), rootScript, d, null);
        }
        Path rootProps = build.root.resolve(GradleProperties.FILE_NAME);
        String v = build.rootProperties.get(name);
        if (v != null) return new Location(name, v, null, null, rootProps);
        return null;
    }

    String resolveOr(String reference, String fallback) {
        Location loc = locate(reference);
        return loc != null ? loc.value() : fallback;
    }
}
