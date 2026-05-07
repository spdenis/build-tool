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
import java.util.LinkedHashMap;
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

    // Builds a repository-level dependency graph. Each node is a repo root path.
    // An edge fromRepo → toRepo means fromRepo has at least one artifact that depends on
    // an artifact produced by toRepo. Dependencies on artifacts not produced by any
    // in-scope repo are ignored.
    public DependencyGraph<Path> buildGraph(List<RepositoryProject> projects) {
        // Map artifact key → repo root for all in-scope artifacts
        Map<String, Path> repoByKey = new HashMap<>();
        // Map repo root → canonical project for deduplication
        Map<Path, RepositoryProject> projectByRepo = new LinkedHashMap<>();

        for (RepositoryProject project : projects) {
            for (Module module : project.getModules()) {
                repoByKey.put(module.getArtifact().key(), module.getRepoRoot());
            }
            projectByRepo.putIfAbsent(project.getRepoRoot(), project);
        }

        DependencyGraph<Path> graph = new DependencyGraph<>();
        for (Path repoRoot : projectByRepo.keySet()) {
            graph.addNode(repoRoot);
        }

        for (RepositoryProject project : projects) {
            if (project.getModules().isEmpty()) continue;
            Path fromRepo = project.getRepoRoot();

            for (Module module : project.getModules()) {
                for (Dependency dep : module.getDependencies()) {
                    Path toRepo = repoByKey.get(dep.getArtifact().key());
                    // Ignore: not in scope, or same repo
                    if (toRepo == null || toRepo.equals(fromRepo)) continue;
                    graph.addEdge(fromRepo, toRepo);
                }
            }

            // Aggregator-pom parents declared in this project that live in another repo
            for (Artifact parent : project.getAggregatorParents()) {
                Path parentRepo = repoByKey.get(parent.key());
                if (parentRepo == null || parentRepo.equals(fromRepo)) continue;
                graph.addEdge(fromRepo, parentRepo);
            }
        }

        return graph;
    }
}
