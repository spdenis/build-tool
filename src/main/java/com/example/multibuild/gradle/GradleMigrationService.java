package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.BuildSystem;
import com.example.multibuild.git.GitService;
import com.example.multibuild.maven.MavenParser;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.Dependency;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.model.RepositoryProject;
import com.example.multibuild.service.CommitMessageFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// Migrates a cloned repository from Maven to Gradle:
//   1. converts the poms (MavenToGradleConverter) and writes the Gradle build,
//   2. verifies the Gradle build yields the same artifacts (groupId:artifactId:version) and keeps every
//      external dependency — i.e. the cross-repo dependency graph and build order are unchanged.
//      On mismatch all changes are rolled back and the repo is reported as failed,
//   3. optionally generates the Gradle wrapper (needs `gradle` on PATH),
//   4. commits and pushes (unless dry mode).
@Service
public class GradleMigrationService {

    private static final Logger log = LoggerFactory.getLogger(GradleMigrationService.class);

    public enum Status { MIGRATED, ALREADY_GRADLE, NO_BUILD, FAILED }

    public record Result(Path repo, Status status, List<String> warnings, List<String> errors) {
        public String repoName() { return repo.getFileName().toString(); }
    }

    @Value("${migrate.gradle.dsl:groovy}")
    private String dsl;

    @Value("${migrate.gradle.wrapper:true}")
    private boolean generateWrapper;

    @Value("${migrate.gradle.version:8.10.2}")
    private String gradleVersion;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    private final MavenToGradleConverter converter;
    private final MavenParser mavenParser;
    private final GradleParser gradleParser;
    private final GitService gitService;
    private final CommitMessageFormatter commitFormatter;

    public GradleMigrationService(MavenToGradleConverter converter, MavenParser mavenParser,
                                  GradleParser gradleParser, GitService gitService,
                                  CommitMessageFormatter commitFormatter) {
        this.converter = converter;
        this.mavenParser = mavenParser;
        this.gradleParser = gradleParser;
        this.gitService = gitService;
        this.commitFormatter = commitFormatter;
    }

    public GradleDsl dsl() {
        return GradleDsl.parse(dsl);
    }

    // commit=false converts the working tree only (skip.git=true).
    public Result migrate(Path repoDir, RepoConfig config, boolean commit) {
        String name = repoDir.getFileName().toString();
        switch (BuildSystem.detect(repoDir)) {
            case GRADLE -> {
                log.info("  [{}] already built with Gradle — skipping", name);
                return new Result(repoDir, Status.ALREADY_GRADLE, List.of(), List.of());
            }
            case NONE -> {
                log.info("  [{}] no pom.xml — nothing to migrate", name);
                return new Result(repoDir, Status.NO_BUILD, List.of(), List.of());
            }
            default -> { }
        }

        RepositoryProject before = mavenParser.parse(repoDir);
        MavenToGradleConverter.Conversion conversion;
        try {
            conversion = converter.convert(repoDir, dsl());
        } catch (RuntimeException e) {
            log.error("  [{}] conversion failed", name, e);
            return new Result(repoDir, Status.FAILED, List.of(), List.of(e.getMessage()));
        }

        Map<Path, byte[]> backup = snapshot(conversion);
        converter.apply(conversion);
        List<String> problems = verify(before, gradleParser.parse(repoDir), conversion);
        if (!problems.isEmpty()) {
            restore(backup);
            log.error("  [{}] migration verification failed — changes rolled back:\n    {}",
                    name, String.join("\n    ", problems));
            return new Result(repoDir, Status.FAILED, conversion.warnings(), problems);
        }

        List<String> warnings = new ArrayList<>(conversion.warnings());
        if (generateWrapper) {
            String wrapperWarning = generateWrapper(repoDir);
            if (wrapperWarning != null) warnings.add(wrapperWarning);
        }
        warnings.forEach(w -> log.warn("  [{}] {}", name, w));
        log.info("  [{}] migrated to Gradle ({} DSL): {} module(s), {} file(s) written, {} removed",
                name, conversion.dsl().name().toLowerCase(), before.getModules().size(),
                conversion.writes().size(), conversion.deletes().size());

        if (commit) {
            boolean committed = gitService.commitAllIfDirty(repoDir,
                    commitFormatter.format("chore: migrate build from Maven to Gradle"));
            if (committed) {
                if (dryMode || (config != null && config.isDryRun())) {
                    log.info("  [{}] Dry mode — skipping push", name);
                } else {
                    gitService.push(repoDir);
                }
            }
        }
        return new Result(repoDir, Status.MIGRATED, warnings, List.of());
    }

    // Problems that would change what the repo publishes or how it fits into the cross-repo graph.
    static List<String> verify(RepositoryProject maven, RepositoryProject gradle,
                               MavenToGradleConverter.Conversion conversion) {
        List<String> problems = new ArrayList<>();
        Set<String> before = maven.getModules().stream().map(m -> m.getArtifact().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> after = gradle.getModules().stream().map(m -> m.getArtifact().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        for (String a : before) if (!after.contains(a)) problems.add("artifact missing in Gradle build: " + a);
        for (String a : after) if (!before.contains(a)) problems.add("unexpected artifact in Gradle build: " + a);

        Set<String> internal = new HashSet<>(conversion.internalKeys());
        Set<String> gradleDeps = externalDependencyKeys(gradle.getModules(), List.of(), internal);
        for (String key : externalDependencyKeys(maven.getModules(), maven.getAggregatorParents(), internal)) {
            if (conversion.droppedKeys().contains(key)) continue;
            // The Maven parser keeps placeholders such as ${project.groupId} unresolved; such keys never
            // match an artifact of another repo, so they cannot affect the graph.
            if (key.contains("${")) continue;
            String expected = conversion.substitutions().getOrDefault(key, key);
            if (!gradleDeps.contains(expected)) problems.add("dependency missing in Gradle build: " + key);
        }
        return problems;
    }

    private static Set<String> externalDependencyKeys(List<Module> modules, List<Artifact> extra, Set<String> internal) {
        Set<String> keys = new TreeSet<>();
        for (Module m : modules) {
            for (Dependency d : m.getDependencies()) keys.add(d.getArtifact().key());
        }
        for (Artifact a : extra) keys.add(a.key());
        keys.removeAll(internal);
        return keys;
    }

    // Original content of every file the conversion touches (null = did not exist).
    private static Map<Path, byte[]> snapshot(MavenToGradleConverter.Conversion conversion) {
        Map<Path, byte[]> backup = new LinkedHashMap<>();
        List<Path> touched = new ArrayList<>(conversion.writes().keySet());
        touched.addAll(conversion.deletes());
        for (Path p : touched) {
            try {
                backup.put(p, Files.isRegularFile(p) ? Files.readAllBytes(p) : null);
            } catch (IOException e) {
                throw new RuntimeException("Failed to back up " + p, e);
            }
        }
        return backup;
    }

    private static void restore(Map<Path, byte[]> backup) {
        backup.forEach((path, content) -> {
            try {
                if (content == null) Files.deleteIfExists(path);
                else Files.write(path, content);
            } catch (IOException e) {
                log.error("Failed to restore {}", path, e);
            }
        });
    }

    // Runs `gradle wrapper`. Returns a warning when the wrapper could not be generated.
    private String generateWrapper(Path repoDir) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = List.of(windows ? "gradle.bat" : "gradle", "wrapper",
                "--gradle-version", gradleVersion, "--no-daemon", "-q");
        try {
            Process process = new ProcessBuilder(cmd).directory(repoDir.toFile()).inheritIO().start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "Gradle wrapper generation timed out — run 'gradle wrapper' in the repo";
            }
            if (process.exitValue() != 0) {
                return "Gradle wrapper generation failed (exit " + process.exitValue()
                        + ") — run 'gradle wrapper --gradle-version " + gradleVersion + "' in the repo";
            }
            return null;
        } catch (IOException e) {
            return "Gradle wrapper not generated: 'gradle' not found on PATH — run 'gradle wrapper --gradle-version "
                    + gradleVersion + "' in the repo";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Gradle wrapper generation interrupted";
        }
    }
}
