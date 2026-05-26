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
import java.util.Collections;
import java.util.List;
import java.util.Properties;
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
            scanDeclared(rootPom, repoDir, modules, aggregatorParents,
                    new Properties(), Collections.emptyList());
        } else {
            try (Stream<Path> paths = Files.walk(repoDir)) {
                paths.filter(p -> p.getFileName().toString().equals("pom.xml"))
                     .sorted()
                     .forEach(pomPath -> {
                         try {
                             parsePom(pomPath, repoDir, modules, aggregatorParents,
                                     new Properties(), Collections.emptyList());
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

    // Recursively follows <modules> declarations. Carries inherited properties and
    // managed deps down from aggregator poms so child modules can resolve ${...}
    // versions and omitted-version dependencies.
    private void scanDeclared(Path pomPath, Path repoRoot,
                              List<Module> modules, List<Artifact> aggregatorParents,
                              Properties inheritedProps,
                              List<org.apache.maven.model.Dependency> inheritedManaged) {
        Model model;
        try (FileReader fr = new FileReader(pomPath.toFile())) {
            model = reader.read(fr);
        } catch (Exception e) {
            log.warn("Skipping {}: {}", pomPath, e.getMessage());
            return;
        }

        // Child properties override parent (Maven semantics).
        Properties mergedProps = new Properties(inheritedProps);
        mergedProps.putAll(model.getProperties());

        // Parent-declared managed deps are visible to children; child entries win on conflict.
        List<org.apache.maven.model.Dependency> childManaged = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies()
                : Collections.emptyList();
        List<org.apache.maven.model.Dependency> mergedManaged = mergedManaged(inheritedManaged, childManaged);

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
                    scanDeclared(subPom, repoRoot, modules, aggregatorParents, mergedProps, mergedManaged);
                } else {
                    log.warn("Declared module '{}' in {} has no pom.xml — skipping", moduleName, pomPath);
                }
            }
        } else {
            try {
                parsePom(pomPath, repoRoot, modules, aggregatorParents, mergedProps, mergedManaged);
            } catch (Exception e) {
                log.warn("Skipping {}: {}", pomPath, e.getMessage());
            }
        }
    }

    private void parsePom(Path pomPath, Path repoRoot,
                          List<Module> modules, List<Artifact> aggregatorParents,
                          Properties inheritedProps,
                          List<org.apache.maven.model.Dependency> inheritedManaged)
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

        // Effective properties and managed deps = inherited from parent + own overrides.
        Properties effectiveProps = new Properties(inheritedProps);
        effectiveProps.putAll(model.getProperties());
        List<org.apache.maven.model.Dependency> ownManaged = model.getDependencyManagement() != null
                ? model.getDependencyManagement().getDependencies()
                : Collections.emptyList();
        List<org.apache.maven.model.Dependency> effectiveManaged = mergedManaged(inheritedManaged, ownManaged);

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
            String depVersion = resolveDepVersion(dep.getVersion(), dep.getGroupId(), dep.getArtifactId(),
                    effectiveProps, effectiveManaged);
            dependencies.add(new Dependency(
                new Artifact(dep.getGroupId(), dep.getArtifactId(), depVersion)));
        }

        modules.add(new Module(artifact, dependencies, pomPath.getParent(), repoRoot));
    }

    // Child entries win: if the same groupId:artifactId appears in both lists, child version is used.
    private static List<org.apache.maven.model.Dependency> mergedManaged(
            List<org.apache.maven.model.Dependency> parent,
            List<org.apache.maven.model.Dependency> child) {
        if (parent.isEmpty()) return child;
        if (child.isEmpty()) return parent;
        List<org.apache.maven.model.Dependency> merged = new ArrayList<>(child);
        for (org.apache.maven.model.Dependency pd : parent) {
            boolean overridden = child.stream().anyMatch(
                    cd -> pd.getGroupId().equals(cd.getGroupId()) && pd.getArtifactId().equals(cd.getArtifactId()));
            if (!overridden) merged.add(pd);
        }
        return merged;
    }

    // Resolves a dependency version using the effective (merged) properties and managed deps.
    private String resolveDepVersion(String version, String groupId, String artifactId,
                                     Properties effectiveProps,
                                     List<org.apache.maven.model.Dependency> effectiveManaged) {
        if (version == null || version.isEmpty()) {
            version = findInManaged(groupId, artifactId, effectiveManaged);
        }
        if (version != null && version.startsWith("${") && version.endsWith("}")) {
            String key = version.substring(2, version.length() - 1);
            String resolved = effectiveProps.getProperty(key);
            if (resolved != null) return resolved;
        }
        return version != null ? version : "unknown";
    }

    private static String findInManaged(String groupId, String artifactId,
                                        List<org.apache.maven.model.Dependency> managed) {
        for (org.apache.maven.model.Dependency dep : managed) {
            if (groupId.equals(dep.getGroupId()) && artifactId.equals(dep.getArtifactId())) {
                return dep.getVersion();
            }
        }
        return null;
    }

    private String resolve(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
