package com.example.multibuild.app;

import com.example.multibuild.build.BuildFailedException;
import com.example.multibuild.build.BuildService;
import com.example.multibuild.git.GitService;
import com.example.multibuild.graph.DependencyGraph;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.model.RepositoryProject;
import com.example.multibuild.model.ResumeState;
import com.example.multibuild.service.BranchService;
import com.example.multibuild.service.DependencyVersionService;
import com.example.multibuild.service.ProjectAggregator;
import com.example.multibuild.service.ReleaseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootApplication
@ComponentScan(basePackages = "com.example.multibuild")
public class Main implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Value("${clone.dir}")
    private String cloneDir;

    @Value("${build.enabled:false}")
    private boolean buildEnabled;

    @Value("${build.mode:snapshot}")
    private String buildMode;

    @Value("${build.resume.state.file:}")
    private String resumeStateFile;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    private final GitService gitService;
    private final BranchService branchService;
    private final PomVersionUpdater pomVersionUpdater;
    private final ProjectAggregator aggregator;
    private final DependencyVersionService dependencyVersionService;
    private final BuildService buildService;
    private final ReleaseService releaseService;
    private final ObjectMapper objectMapper;

    public Main(GitService gitService, BranchService branchService,
                PomVersionUpdater pomVersionUpdater,
                ProjectAggregator aggregator, DependencyVersionService dependencyVersionService,
                BuildService buildService, ReleaseService releaseService, ObjectMapper objectMapper) {
        this.gitService = gitService;
        this.branchService = branchService;
        this.pomVersionUpdater = pomVersionUpdater;
        this.aggregator = aggregator;
        this.dependencyVersionService = dependencyVersionService;
        this.buildService = buildService;
        this.releaseService = releaseService;
        this.objectMapper = objectMapper;
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar multibuild.jar repos.json");
            System.exit(1);
        }

        List<RepoConfig> repoEntries = objectMapper.readValue(
                Paths.get(args[0]).toFile(), new TypeReference<List<RepoConfig>>() {});

        Path workDir = Paths.get(cloneDir);
        Files.createDirectories(workDir);

        Path stateFile = resumeStateFile.isBlank()
                ? workDir.resolve(".multibuild-resume.json")
                : Paths.get(resumeStateFile);

        ResumeState resumeState = loadResumeState(stateFile);

        List<Path> clonedPaths = new ArrayList<>();
        Map<Path, RepoConfig> repoConfigByPath = new LinkedHashMap<>();
        for (RepoConfig entry : repoEntries) {
            String url = entry.getUrl();
            String repoName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");
            Path cloned = gitService.cloneRepo(url, workDir.resolve(repoName));
            branchService.apply(cloned, entry.getEffectiveSourceBranch());
            if (entry.hasVersionOverride()) {
                applyVersionOverride(cloned, entry.getVersion());
            }
            clonedPaths.add(cloned);
            repoConfigByPath.put(cloned, entry);
        }

        // Validate/fix versions before parsing pom.xml files so that moduleMap
        // and cross-repo dependency updates see the correct feature-branch versions.
        // Repos with an explicit version override are excluded — the override IS the version.
        if (buildEnabled && !"release".equalsIgnoreCase(buildMode)) {
            List<Path> pathsToValidate = clonedPaths.stream()
                    .filter(p -> !repoConfigByPath.get(p).hasVersionOverride())
                    .toList();
            try {
                branchService.validateVersions(pathsToValidate);
            } catch (RuntimeException e) {
                System.err.println("\n[BUILD FAILED]\n" + e.getMessage());
                System.exit(1);
            }
        }

        List<RepositoryProject> projects = aggregator.aggregate(clonedPaths);
        DependencyGraph graph = aggregator.buildGraph(projects);
        List<List<Artifact>> buildLayers = graph.topologicalLayers();
        List<Artifact> buildOrder = buildLayers.stream().flatMap(List::stream).toList();

        Map<Artifact, Module> moduleMap = projects.stream()
                .flatMap(p -> p.getModules().stream())
                .collect(Collectors.toMap(Module::getArtifact, m -> m));

        if (buildEnabled) {
            try {
                if ("release".equalsIgnoreCase(buildMode)) {
                    releaseService.execute(buildLayers, moduleMap, clonedPaths, repoConfigByPath, resumeState);
                } else {
                    dependencyVersionService.apply(moduleMap, clonedPaths);
                    try {
                        buildService.buildAll(buildLayers, moduleMap, repoConfigByPath,
                                resumeState.getCompletedRepoNames(), Collections.emptyMap());
                    } catch (BuildFailedException e) {
                        e.getSucceeded().forEach(resumeState::markCompleted);
                        throw e;
                    }
                }
                // Success — remove stale state file if present
                Files.deleteIfExists(stateFile);
            } catch (RuntimeException e) {
                saveResumeState(resumeState, stateFile);
                System.err.println("\n[BUILD FAILED]\n" + e.getMessage());
                System.err.println("\nTo resume this build, re-run with:");
                System.err.println("  --build.resume.state.file=" + stateFile.toAbsolutePath());
                System.exit(1);
            }
        }

        BuildResult result = new BuildResult();
        result.artifacts = new ArrayList<>();
        result.dependencies = new ArrayList<>();
        result.buildOrder = new ArrayList<>();

        for (Map.Entry<Artifact, List<Artifact>> entry : graph.getAdjacencyList().entrySet()) {
            result.artifacts.add(new BuildResult.ArtifactDto(entry.getKey()));
            for (Artifact dep : entry.getValue()) {
                result.dependencies.add(new BuildResult.DependencyDto(
                    entry.getKey().toString(), dep.toString()
                ));
            }
        }

        for (Artifact a : buildOrder) {
            result.buildOrder.add(a.toString());
        }

        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private void applyVersionOverride(Path repoDir, String version) {
        log.info("Applying version override {} in {}", version, repoDir.getFileName());
        pomVersionUpdater.setVersions(repoDir, version);
        boolean committed = gitService.commitAllIfDirty(repoDir, "chore: set version to " + version);
        if (committed) {
            if (dryMode) {
                log.info("Dry mode — skipping push for {}", repoDir.getFileName());
            } else {
                gitService.push(repoDir);
            }
        }
    }

    private ResumeState loadResumeState(Path stateFile) {
        if (Files.exists(stateFile)) {
            try {
                ResumeState state = objectMapper.readValue(stateFile.toFile(), ResumeState.class);
                System.out.println("Resuming from state file: " + stateFile.toAbsolutePath());
                System.out.println("Already completed repos: " + state.getCompletedRepoNames());
                return state;
            } catch (IOException e) {
                System.err.println("Warning: could not read resume state from " + stateFile + ": " + e.getMessage());
            }
        }
        return new ResumeState();
    }

    private void saveResumeState(ResumeState state, Path stateFile) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
            System.err.println("Resume state saved to: " + stateFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Warning: could not save resume state to " + stateFile + ": " + e.getMessage());
        }
    }
}
