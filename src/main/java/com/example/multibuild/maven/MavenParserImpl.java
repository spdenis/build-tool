package com.example.multibuild.maven;

import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Dependency;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepositoryProject;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class MavenParserImpl implements MavenParser {

    private static final Logger log = LoggerFactory.getLogger(MavenParserImpl.class);

    // When true, only pom.xml files reachable via <modules> declarations are scanned.
    // When false (default), the entire directory tree is walked.
    @Value("${maven.scan.declared-modules-only:true}")
    private boolean declaredModulesOnly;

    private final MavenXpp3Reader reader = new MavenXpp3Reader();

    @Override
    public RepositoryProject parse(Path repoDir) {
        List<Module> modules = new ArrayList<>();
        List<Artifact> aggregatorParents = new ArrayList<>();

        Path rootPom = repoDir.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            return new RepositoryProject(repoDir.getFileName().toString(), repoDir, modules, aggregatorParents);
        }

        if (declaredModulesOnly) {
            scanDeclared(rootPom, repoDir, modules, aggregatorParents);
        } else {
            try (Stream<Path> paths = Files.walk(repoDir)) {
                paths.filter(p -> p.getFileName().toString().equals("pom.xml"))
                     .sorted()
                     .forEach(pomPath -> {
                         try {
                             parsePom(pomPath, repoDir, modules, aggregatorParents);
                         } catch (Exception e) {
                             log.warn("Skipping {}: {}", pomPath, e.getMessage());
                         }
                     });
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan " + repoDir, e);
            }
        }
        return new RepositoryProject(repoDir.getFileName().toString(), repoDir, modules, aggregatorParents);
    }

    // Recursively follows <modules> declarations instead of walking the filesystem.
    private void scanDeclared(Path pomPath, Path repoRoot,
                              List<Module> modules, List<Artifact> aggregatorParents) {
        Model model;
        try (FileReader fr = new FileReader(pomPath.toFile())) {
            model = reader.read(fr);
        } catch (Exception e) {
            log.warn("Skipping {}: {}", pomPath, e.getMessage());
            return;
        }

        if ("pom".equals(model.getPackaging()) && !model.getModules().isEmpty()) {
            if (model.getParent() != null) {
                org.apache.maven.model.Parent p = model.getParent();
                if (p.getGroupId() != null && p.getArtifactId() != null && p.getVersion() != null) {
                    aggregatorParents.add(new Artifact(p.getGroupId(), p.getArtifactId(), p.getVersion()));
                }
            }
            Path pomDir = pomPath.getParent();
            for (String moduleName : model.getModules()) {
                Path subPom = pomDir.resolve(moduleName).resolve("pom.xml");
                if (Files.exists(subPom)) {
                    scanDeclared(subPom, repoRoot, modules, aggregatorParents);
                } else {
                    log.warn("Declared module '{}' in {} has no pom.xml — skipping", moduleName, pomPath);
                }
            }
        } else {
            try {
                parsePom(pomPath, repoRoot, modules, aggregatorParents);
            } catch (Exception e) {
                log.warn("Skipping {}: {}", pomPath, e.getMessage());
            }
        }
    }

    private void parsePom(Path pomPath, Path repoRoot,
                          List<Module> modules, List<Artifact> aggregatorParents)
            throws IOException, XmlPullParserException {
        Model model;
        try (FileReader fr = new FileReader(pomPath.toFile())) {
            model = reader.read(fr);
        }

        if ("pom".equals(model.getPackaging()) && !model.getModules().isEmpty()) {
            // Aggregator POM — not a buildable module, but its <parent> establishes a
            // cross-repo dependency that must be reflected in the build order.
            if (model.getParent() != null) {
                org.apache.maven.model.Parent p = model.getParent();
                if (p.getGroupId() != null && p.getArtifactId() != null && p.getVersion() != null) {
                    aggregatorParents.add(new Artifact(p.getGroupId(), p.getArtifactId(), p.getVersion()));
                }
            }
            return;
        }

        String groupId = resolve(model.getGroupId(), model.getParent() != null ? model.getParent().getGroupId() : null);
        String artifactId = model.getArtifactId();
        String version = resolve(model.getVersion(), model.getParent() != null ? model.getParent().getVersion() : null);

        if (groupId == null || artifactId == null) return;

        Artifact artifact = new Artifact(groupId, artifactId, version != null ? version : "unknown");

        List<Dependency> dependencies = new ArrayList<>();

        // Include <parent> as a dependency so that an external parent from another repo
        // is reflected as a graph edge. Internal parents (aggregator roots skipped above)
        // won't match any internal artifact and are harmlessly ignored in buildGraph.
        if (model.getParent() != null) {
            org.apache.maven.model.Parent p = model.getParent();
            if (p.getGroupId() != null && p.getArtifactId() != null && p.getVersion() != null) {
                dependencies.add(new Dependency(
                    new Artifact(p.getGroupId(), p.getArtifactId(), p.getVersion())));
            }
        }

        for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
            dependencies.add(new Dependency(
                new Artifact(dep.getGroupId(), dep.getArtifactId(),
                    dep.getVersion() != null ? dep.getVersion() : "unknown")
            ));
        }

        modules.add(new Module(artifact, dependencies, pomPath.getParent(), repoRoot));
    }

    private String resolve(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
