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
import java.util.stream.Collectors;

// Migrates a cloned repository from Maven to Gradle:
//   1. converts the poms (MavenToGradleConverter) and writes the Gradle build,
//   2. verifies the Gradle build yields the same artifacts (groupId:artifactId:version) and keeps every
//      external dependency — i.e. the cross-repo dependency graph and build order are unchanged.
//      On mismatch all changes are rolled back and the repo is reported as failed,
//   3. adds the Gradle wrapper (GradleWrapperGenerator — no Gradle installation needed),
//   4. commits and pushes (unless dry mode).
// With migrate.keep.maven=true the poms and the Maven wrapper stay in the repo: Gradle takes priority
// (it is parsed and built), while version / dependency updates keep the poms in sync so the Maven build
// remains a working fallback. Running migrate-gradle again without the flag completes the migration:
// the Maven build files are removed (MAVEN_REMOVED). Repos already on Gradle but without a wrapper get
// the wrapper added (WRAPPER_ADDED).
@Service
public class GradleMigrationService {

    private static final Logger log = LoggerFactory.getLogger(GradleMigrationService.class);

    public enum Status { MIGRATED, MAVEN_REMOVED, WRAPPER_ADDED, ALREADY_GRADLE, NO_BUILD, FAILED }

    public record Result(Path repo, Status status, List<String> warnings, List<String> errors) {
        public String repoName() { return repo.getFileName().toString(); }
    }

    @Value("${migrate.gradle.dsl:groovy}")
    private String dsl;

    @Value("${migrate.gradle.wrapper:true}")
    private boolean generateWrapper;

    @Value("${migrate.keep.maven:false}")
    private boolean keepMaven;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    private final MavenToGradleConverter converter;
    private final MavenParser mavenParser;
    private final GradleParser gradleParser;
    private final GitService gitService;
    private final CommitMessageFormatter commitFormatter;
    private final GradleWrapperGenerator wrapperGenerator;

    public GradleMigrationService(MavenToGradleConverter converter, MavenParser mavenParser,
                                  GradleParser gradleParser, GitService gitService,
                                  CommitMessageFormatter commitFormatter, GradleWrapperGenerator wrapperGenerator) {
        this.converter = converter;
        this.mavenParser = mavenParser;
        this.gradleParser = gradleParser;
        this.gitService = gitService;
        this.commitFormatter = commitFormatter;
        this.wrapperGenerator = wrapperGenerator;
    }

    public GradleDsl dsl() {
        return GradleDsl.parse(dsl);
    }

    // commit=false converts the working tree only (skip.git=true).
    public Result migrate(Path repoDir, RepoConfig config, boolean commit) {
        String name = repoDir.getFileName().toString();
        switch (BuildSystem.detect(repoDir)) {
            case GRADLE -> {
                return continueMigration(repoDir, config, commit);
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

        if (keepMaven) conversion = conversion.keepingMaven();
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
            String wrapperWarning = wrapperGenerator.generate(repoDir);
            if (wrapperWarning != null) warnings.add(wrapperWarning);
        }
        warnings.forEach(w -> log.warn("  [{}] {}", name, w));
        log.info("  [{}] migrated to Gradle ({} DSL): {} module(s), {} file(s) written, {} removed",
                name, conversion.dsl().name().toLowerCase(), before.getModules().size(),
                conversion.writes().size(), conversion.deletes().size());
        if (keepMaven) {
            log.info("  [{}] Maven build kept (migrate.keep.maven=true); Gradle takes priority — run migrate-gradle "
                    + "without it to remove the Maven files once the migration is complete", name);
        }

        if (commit) commitAndPush(repoDir, config, "chore: migrate build from Maven to Gradle");
        return new Result(repoDir, Status.MIGRATED, warnings, List.of());
    }

    // A repo already on Gradle: adds a missing wrapper (e.g. Gradle was not installed when it was migrated)
    // and, unless migrate.keep.maven=true, removes Maven files kept by an earlier migration.
    private Result continueMigration(Path repoDir, RepoConfig config, boolean commit) {
        String name = repoDir.getFileName().toString();
        List<String> warnings = new ArrayList<>();
        Status status = Status.ALREADY_GRADLE;

        if (generateWrapper && !GradleWrapperGenerator.hasWrapper(repoDir)) {
            String warning = wrapperGenerator.generate(repoDir);
            if (warning == null) {
                status = Status.WRAPPER_ADDED;
                if (commit) commit(repoDir, "chore: add Gradle wrapper");
            } else {
                warnings.add(warning);
            }
        }

        if (!keepMaven && BuildSystem.hasMavenBuild(repoDir)) {
            if (!generatedByMigration(repoDir)) {
                log.info("  [{}] pom.xml kept: the Gradle build was not generated by migrate-gradle", name);
            } else {
                warnings.addAll(removeMavenBuild(repoDir));
                status = Status.MAVEN_REMOVED;
                if (commit) commit(repoDir, "chore: remove Maven build (migration to Gradle completed)");
            }
        }

        if (status == Status.ALREADY_GRADLE && warnings.isEmpty()) {
            log.info("  [{}] already built with Gradle — skipping", name);
        }
        warnings.forEach(w -> log.warn("  [{}] {}", name, w));
        if (commit && status != Status.ALREADY_GRADLE) push(repoDir, config);
        return new Result(repoDir, status, warnings, List.of());
    }

    // Deletes the kept Maven build. Returns warnings when it no longer matches the Gradle build
    // (Gradle is authoritative, so the removal goes ahead).
    private List<String> removeMavenBuild(Path repoDir) {
        List<String> warnings = new ArrayList<>();
        List<Path> files;
        try {
            MavenToGradleConverter.Conversion conversion = converter.convert(repoDir, dsl());
            for (String p : verify(mavenParser.parse(repoDir), gradleParser.parse(repoDir), conversion)) {
                warnings.add("kept Maven build differed from the Gradle build (" + p + ") — removed anyway, Gradle is authoritative");
            }
            files = conversion.deletes();
        } catch (RuntimeException e) {
            files = converter.mavenBuildFiles(repoDir);
        }
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                warnings.add("could not delete " + repoDir.relativize(f) + ": " + e.getMessage());
            }
        }
        log.info("  [{}] Maven build removed: {} file(s)", repoDir.getFileName(), files.size());
        return warnings;
    }

    static boolean generatedByMigration(Path repoDir) {
        for (String f : BuildSystem.GRADLE_ROOT_FILES) {
            Path file = repoDir.resolve(f);
            try {
                if (Files.isRegularFile(file) && Files.readString(file).contains(MavenToGradleConverter.HEADER)) return true;
            } catch (IOException e) {
                // unreadable: treat as not generated
            }
        }
        return false;
    }

    private void commit(Path repoDir, String message) {
        gitService.commitAllIfDirty(repoDir, commitFormatter.format(message));
    }

    private void push(Path repoDir, RepoConfig config) {
        if (dryMode || (config != null && config.isDryRun())) {
            log.info("  [{}] Dry mode — skipping push", repoDir.getFileName());
        } else {
            gitService.push(repoDir);
        }
    }

    private void commitAndPush(Path repoDir, RepoConfig config, String message) {
        if (gitService.commitAllIfDirty(repoDir, commitFormatter.format(message))) push(repoDir, config);
    }

    // Commit subjects (without the commit prefix) this service creates; a branch holding only these can be
    // reset without losing anybody's work.
    static final List<String> MIGRATION_COMMITS = List.of(
            "chore: migrate build from Maven to Gradle", "chore: add Gradle wrapper",
            "chore: remove Maven build (migration to Gradle completed)");

    public record ResetResult(boolean ok, String message) {}

    // Throws away a previous migration so it can start from scratch: deletes the migration branch locally and
    // (unless dry mode) on origin — which also closes its pull request — and checks out the branch freshly
    // created from the source branch with a clean working tree. Refuses when the branch carries commits
    // other than migration commits (someone's manual fixes) unless force is set.
    public ResetResult reset(Path repoDir, String branch, String sourceBranch, boolean force, RepoConfig config) {
        String name = repoDir.getFileName().toString();
        if (branch.equals(sourceBranch)) {
            return new ResetResult(false, "migration branch equals the source branch '" + sourceBranch + "' — refusing to delete it");
        }
        List<String> ahead = gitService.commitsAhead(repoDir, branch, sourceBranch);
        List<String> foreign = ahead.stream().filter(c -> !isMigrationCommit(c)).toList();
        if (!foreign.isEmpty() && !force) {
            return new ResetResult(false, "branch '" + branch + "' has " + foreign.size()
                    + " commit(s) not made by the migration (" + String.join("; ", foreign.subList(0, Math.min(3, foreign.size())))
                    + (foreign.size() > 3 ? "; ..." : "") + ") — set migrate.reset.force=true to discard them");
        }
        boolean dry = dryMode || (config != null && config.isDryRun());
        gitService.createFreshBranch(repoDir, sourceBranch, sourceBranch);
        boolean remoteDeleted = gitService.deleteBranch(repoDir, branch, !dry);
        gitService.createFreshBranch(repoDir, branch, sourceBranch);
        String msg = "reset: discarded " + ahead.size() + " commit(s) of '" + branch + "'"
                + (remoteDeleted ? ", deleted origin/" + branch + " (its pull request is closed)"
                : dry ? " locally (dry mode — origin/" + branch + " left untouched)" : "");
        log.info("  [{}] {}", name, msg);
        return new ResetResult(true, msg);
    }

    private boolean isMigrationCommit(String subject) {
        for (String c : MIGRATION_COMMITS) {
            if (subject.equals(c) || subject.equals(commitFormatter.format(c)) || subject.endsWith(" " + c)) return true;
        }
        return false;
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
}
