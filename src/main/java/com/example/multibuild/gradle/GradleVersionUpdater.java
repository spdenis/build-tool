package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.VersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepositoryProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Version handling for Gradle builds. The root version is `version` in the root gradle.properties
// (the layout written by migrate-gradle), falling back to a literal `version = '...'` in the root
// build script. setVersions rewrites every declaration that carries the current root version:
// gradle.properties entries and literal `version = ...` lines in any project script.
@Component
public class GradleVersionUpdater implements VersionUpdater {

    private static final Logger log = LoggerFactory.getLogger(GradleVersionUpdater.class);

    private final GradleParser parser;

    public GradleVersionUpdater(GradleParser parser) {
        this.parser = parser;
    }

    @Override
    public String getRootVersion(Path repoDir) {
        GradleBuild build = GradleBuild.load(repoDir);
        String v = build.rootProperties.get("version");
        if (v != null && !v.isBlank()) return v;
        GradleScript root = GradleScript.read(build.rootProject().buildFile());
        return root != null ? GradleDeclarations.version(root, null) : null;
    }

    // The root project of a multi-project build usually publishes nothing, so the "root artifact" is the
    // first published module carrying the root version.
    @Override
    public Artifact getRootArtifact(Path repoDir) {
        String version = getRootVersion(repoDir);
        if (version == null) return null;
        RepositoryProject project = parser.parse(repoDir);
        List<Module> modules = project.getModules();
        if (modules.isEmpty()) return null;
        Artifact a = modules.stream().map(Module::getArtifact)
                .filter(m -> version.equals(m.getVersion()))
                .findFirst()
                .orElse(modules.get(0).getArtifact());
        return new Artifact(a.getGroupId(), a.getArtifactId(), version);
    }

    @Override
    public void setVersions(Path repoDir, String newVersion) {
        String old = getRootVersion(repoDir);
        if (old == null) {
            log.warn("Could not determine root version in {}, skipping", repoDir);
            return;
        }
        log.info("Setting versions in {} to {}", repoDir.getFileName(), newVersion);
        GradleBuild build = GradleBuild.load(repoDir);

        Path rootProps = repoDir.resolve(GradleProperties.FILE_NAME);
        if (build.rootProperties.containsKey("version")) {
            GradleProperties.set(rootProps, "version", newVersion);
        }
        ScriptEdits edits = new ScriptEdits();
        for (GradleBuild.Project p : build.projects) {
            if (!p.isRoot()) {
                Path props = p.dir().resolve(GradleProperties.FILE_NAME);
                if (Files.isRegularFile(props) && old.equals(GradleProperties.read(props).get("version"))) {
                    GradleProperties.set(props, "version", newVersion);
                }
            }
            GradleScript script = GradleScript.read(p.buildFile());
            if (script == null) continue;
            for (int[] decl : GradleDeclarations.versionDeclarations(script)) {
                if (old.equals(script.text.substring(decl[0], decl[1]))) {
                    edits.replace(script, decl[0], decl[1], newVersion);
                }
            }
        }
        edits.write().forEach(f -> log.debug("Updated {}", f));
    }
}
