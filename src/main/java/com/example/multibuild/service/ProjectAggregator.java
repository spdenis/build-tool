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
        Map<String, Path> repoByKey = new HashMap<>();

        for (RepositoryProject project : projects) {
            for (Module module : project.getModules()) {
                Artifact a = module.getArtifact();
                internalArtifacts.put(a.key(), a);
                repoByKey.put(a.key(), module.getRepoRoot());
            }
        }

        DependencyGraph graph = new DependencyGraph();
        for (Artifact a : internalArtifacts.values()) {
            graph.addArtifact(a);
        }

        // Only add cross-repo dependency edges. All artifacts within the same repo are
        // built together from the root pom.xml, so intra-repo edges are irrelevant.
        for (RepositoryProject project : projects) {
            for (Module module : project.getModules()) {
                Artifact from = module.getArtifact();
                Path fromRepo = module.getRepoRoot();
                for (Dependency dep : module.getDependencies()) {
                    Artifact internal = internalArtifacts.get(dep.getArtifact().key());
                    if (internal == null || internal.equals(from)) continue;
                    if (fromRepo.equals(repoByKey.get(internal.key()))) continue; // same repo
                    graph.addDependency(from, internal);
                }
            }

            // Aggregator-pom parents: add cross-repo edges from all modules in this project
            // to the parent artifact (if it lives in a different repo).
            for (Artifact parent : project.getAggregatorParents()) {
                Artifact internal = internalArtifacts.get(parent.key());
                if (internal == null) continue;
                Path parentRepo = repoByKey.get(internal.key());
                for (Module module : project.getModules()) {
                    if (module.getRepoRoot().equals(parentRepo)) continue; // same repo
                    graph.addDependency(module.getArtifact(), internal);
                }
            }
        }

        return graph;
    }
}
