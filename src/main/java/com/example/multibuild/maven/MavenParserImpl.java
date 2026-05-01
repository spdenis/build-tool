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

    private final MavenXpp3Reader reader = new MavenXpp3Reader();

    @Override
    public RepositoryProject parse(Path repoDir) {
        List<Module> modules = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(repoDir)) {
            paths.filter(p -> p.getFileName().toString().equals("pom.xml"))
                 .sorted()
                 .forEach(pomPath -> {
                     try {
                         Module module = parsePom(pomPath, repoDir);
                         if (module != null) modules.add(module);
                     } catch (Exception e) {
                         log.warn("Skipping {}: {}", pomPath, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + repoDir, e);
        }
        return new RepositoryProject(repoDir.getFileName().toString(), modules);
    }

    private Module parsePom(Path pomPath, Path repoRoot) throws IOException, XmlPullParserException {
        Model model;
        try (FileReader fr = new FileReader(pomPath.toFile())) {
            model = reader.read(fr);
        }

        // Skip aggregator POMs (packaging=pom with declared submodules)
        if ("pom".equals(model.getPackaging()) && !model.getModules().isEmpty()) {
            return null;
        }

        String groupId = resolve(model.getGroupId(), model.getParent() != null ? model.getParent().getGroupId() : null);
        String artifactId = model.getArtifactId();
        String version = resolve(model.getVersion(), model.getParent() != null ? model.getParent().getVersion() : null);

        if (groupId == null || artifactId == null) return null;

        Artifact artifact = new Artifact(groupId, artifactId, version != null ? version : "unknown");

        List<Dependency> dependencies = new ArrayList<>();
        for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
            dependencies.add(new Dependency(
                new Artifact(dep.getGroupId(), dep.getArtifactId(),
                    dep.getVersion() != null ? dep.getVersion() : "unknown")
            ));
        }

        return new Module(artifact, dependencies, pomPath.getParent(), repoRoot);
    }

    private String resolve(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
