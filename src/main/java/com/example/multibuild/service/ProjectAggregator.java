package com.example.multibuild.service;

import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.maven.MavenParser;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Dependency;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepositoryProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectAggregator {

    private static final Logger log = LoggerFactory.getLogger(ProjectAggregator.class);

    private final MavenParser mavenParser;

    public ProjectAggregator(MavenParser mavenParser) {
        this.mavenParser = mavenParser;
    }

    public List<RepositoryProject> aggregate(List<Path> repoDirs) {
        List<RepositoryProject> projects = new ArrayList<>();
        for (Path dir : repoDirs) {
            RepositoryProject project = mavenParser.parse(dir);
            log.info("Parsed {}: {} module(s)", project.getName(), project.getModules().size());
            projects.add(project);
        }
        return projects;
    }

    public DependencyGraph buildGraph(List<RepositoryProject> projects) {
        // Index all scanned artifacts by groupId:artifactId for internal resolution
        Map<String, Artifact> internalArtifacts = new HashMap<>();
        for (RepositoryProject project : projects) {
            for (Module module : project.getModules()) {
                Artifact a = module.getArtifact();
                internalArtifacts.put(a.key(), a);
            }
        }

        DependencyGraph graph = new DependencyGraph();
        for (Artifact a : internalArtifacts.values()) {
            graph.addArtifact(a);
        }

        // Add edges for internal dependencies only
        for (RepositoryProject project : projects) {
            for (Module module : project.getModules()) {
                Artifact from = module.getArtifact();
                for (Dependency dep : module.getDependencies()) {
                    Artifact internal = internalArtifacts.get(dep.getArtifact().key());
                    if (internal != null && !internal.equals(from)) {
                        graph.addDependency(from, internal);
                    }
                }
            }

            // Aggregator-pom parents are not attached to any module. Add edges from all
            // modules in this project to the parent so the repo build order is correct.
            for (Artifact parent : project.getAggregatorParents()) {
                Artifact internal = internalArtifacts.get(parent.key());
                if (internal == null) continue;
                for (Module module : project.getModules()) {
                    if (!internal.equals(module.getArtifact())) {
                        graph.addDependency(module.getArtifact(), internal);
                    }
                }
            }
        }

        return graph;
    }
}
