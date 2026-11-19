package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.BuildSystem;
import com.example.multibuild.git.JGitService;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.service.CommitMessageFormatter;
import com.example.multibuild.testutil.TestGit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class GradleMigrationServiceTest {

    @TempDir
    Path tempDir;

    private JGitService git;
    private GradleMigrationService service;

    @BeforeEach
    void setUp() {
        git = TestGit.jgit();
        MavenParserImpl mavenParser = new MavenParserImpl();
        ReflectionTestUtils.setField(mavenParser, "declaredModulesOnly", true);
        CommitMessageFormatter formatter = new CommitMessageFormatter();
        ReflectionTestUtils.setField(formatter, "prefix", "PROJ-1");
        service = new GradleMigrationService(new MavenToGradleConverter(), mavenParser, new GradleParser(),
                git, formatter,
                GradleWrapperGeneratorTest.generator(tempDir, "", tempDir.resolve("served").toUri().toString()));
        ReflectionTestUtils.setField(service, "dsl", "groovy");
        ReflectionTestUtils.setField(service, "generateWrapper", false);
        ReflectionTestUtils.setField(service, "dryMode", false);
        ReflectionTestUtils.setField(service, "keepMaven", false);
    }

    @Test
    void migrate_commitsGradleBuildAndPomDeletionsAndPushes() throws Exception {
        Path remote = TestGit.initRemote("repo-multi", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-multi"));
        git.checkoutOrCreateBranch(work, "gradle-migration", "main");

        GradleMigrationService.Result result = service.migrate(work, new RepoConfig(), true);

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.MIGRATED);
        assertThat(result.errors()).isEmpty();
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.status().call().isClean()).isTrue();
            RevCommit head = g.log().setMaxCount(1).call().iterator().next();
            assertThat(head.getFullMessage()).isEqualTo("PROJ-1 chore: migrate build from Maven to Gradle");
            List<String> files = filesIn(g, head);
            assertThat(files).contains("settings.gradle", "build.gradle", "gradle.properties",
                    "module-core/build.gradle", "module-service/build.gradle", ".gitignore");
            assertThat(files).noneMatch(f -> f.endsWith("pom.xml"));
        }
        try (Git r = Git.open(remote.toFile())) {
            assertThat(r.getRepository().resolve("refs/heads/gradle-migration")).isNotNull();
        }
    }

    @Test
    void migrate_dryRunCommitsWithoutPushing() throws Exception {
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));
        git.checkoutOrCreateBranch(work, "gradle-migration", "main");
        RepoConfig config = new RepoConfig();
        config.setDryRun(true);

        assertThat(service.migrate(work, config, true).status()).isEqualTo(GradleMigrationService.Status.MIGRATED);

        try (Git r = Git.open(remote.toFile())) {
            assertThat(r.getRepository().resolve("refs/heads/gradle-migration")).isNull();
        }
    }

    @Test
    void migrate_withoutCommit_onlyConvertsWorkingTree() throws Exception {
        Path work = TestGit.cloneFixture(git, "repo-b", tempDir);

        assertThat(service.migrate(work, null, false).status()).isEqualTo(GradleMigrationService.Status.MIGRATED);

        assertThat(BuildSystem.detect(work)).isEqualTo(BuildSystem.GRADLE);
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.status().call().isClean()).isFalse();
        }
    }

    @Test
    void migrate_kotlinDsl() {
        ReflectionTestUtils.setField(service, "dsl", "kotlin");
        Path work = TestGit.cloneFixture(git, "repo-b-property", tempDir);

        service.migrate(work, null, false);

        assertThat(work.resolve("build.gradle.kts")).exists();
        assertThat(work.resolve("settings.gradle.kts")).exists();
    }

    @Test
    void migrate_skipsGradleAndBuildlessRepos() {
        Path gradle = write(tempDir.resolve("g/build.gradle"), "plugins { id 'java' }\n").getParent();
        Path none = write(tempDir.resolve("n/README.md"), "docs").getParent();

        assertThat(service.migrate(gradle, null, false).status()).isEqualTo(GradleMigrationService.Status.ALREADY_GRADLE);
        assertThat(service.migrate(none, null, false).status()).isEqualTo(GradleMigrationService.Status.NO_BUILD);
    }

    @Test
    void migrate_verificationFailure_rollsBackEverything() {
        // The module lives outside the repo root and cannot become a Gradle subproject, so the
        // Gradle build would lose an artifact: the migration must fail and restore the Maven build.
        Path repo = tempDir.resolve("agg");
        String rootPom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>agg</artifactId><version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>inner</module><module>../sibling</module></modules>
                </project>
                """;
        write(repo.resolve("pom.xml"), rootPom);
        write(repo.resolve("inner/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.acme</groupId><artifactId>agg</artifactId><version>1.0.0</version></parent>
                  <artifactId>inner</artifactId>
                </project>
                """);
        write(tempDir.resolve("sibling/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>sibling</artifactId><version>1.0.0</version>
                </project>
                """);
        write(repo.resolve(".gitignore"), "target/\n");

        GradleMigrationService.Result result = service.migrate(repo, null, false);

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.FAILED);
        assertThat(result.errors()).containsExactly("artifact missing in Gradle build: com.acme:sibling:1.0.0");
        assertThat(read(repo.resolve("pom.xml"))).isEqualTo(rootPom);
        assertThat(repo.resolve("inner/pom.xml")).exists();
        assertThat(read(repo.resolve(".gitignore"))).isEqualTo("target/\n");
        assertThat(repo.resolve("settings.gradle")).doesNotExist();
        assertThat(repo.resolve("build.gradle")).doesNotExist();
        assertThat(repo.resolve("inner/build.gradle")).doesNotExist();
        assertThat(repo.resolve("gradle.properties")).doesNotExist();
        assertThat(BuildSystem.detect(repo)).isEqualTo(BuildSystem.MAVEN);
    }

    @Test
    void migrate_withWrapper_commitsExecutableGradlewWithoutGradleInstalled() throws Exception {
        GradleWrapperGeneratorTest.wrapperDir(tempDir.resolve("served"));
        ReflectionTestUtils.setField(service, "generateWrapper", true);
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));
        git.checkoutOrCreateBranch(work, "gradle-migration", "main");

        GradleMigrationService.Result result = service.migrate(work, new RepoConfig(), true);

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.MIGRATED);
        assertThat(result.warnings()).noneMatch(w -> w.contains("wrapper"));
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.status().call().isClean()).isTrue();
            RevCommit head = g.log().setMaxCount(1).call().iterator().next();
            assertThat(filesIn(g, head)).contains("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                    "gradle/wrapper/gradle-wrapper.properties", ".gitattributes");
            assertThat(modeOf(g, head, "gradlew")).isEqualTo(FileMode.EXECUTABLE_FILE);
        }
    }

    @Test
    void alreadyGradleWithoutWrapper_getsWrapperCommitted_andIsSkippedNextTime() throws Exception {
        GradleWrapperGeneratorTest.wrapperDir(tempDir.resolve("served"));
        ReflectionTestUtils.setField(service, "generateWrapper", true);
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));
        git.checkoutOrCreateBranch(work, "gradle-migration", "main");
        ReflectionTestUtils.setField(service, "generateWrapper", false);
        service.migrate(work, new RepoConfig(), true);          // migrated earlier, no wrapper
        assertThat(GradleWrapperGenerator.hasWrapper(work)).isFalse();
        ReflectionTestUtils.setField(service, "generateWrapper", true);

        GradleMigrationService.Result result = service.migrate(work, new RepoConfig(), true);

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.WRAPPER_ADDED);
        try (Git g = Git.open(work.toFile())) {
            RevCommit head = g.log().setMaxCount(1).call().iterator().next();
            assertThat(head.getFullMessage()).isEqualTo("PROJ-1 chore: add Gradle wrapper");
            assertThat(filesIn(g, head)).contains("gradlew", "gradle/wrapper/gradle-wrapper.jar");
        }
        try (Git r = Git.open(remote.toFile())) {
            assertThat(r.getRepository().resolve("refs/heads/gradle-migration")).isNotNull();
        }
        assertThat(service.migrate(work, new RepoConfig(), true).status())
                .isEqualTo(GradleMigrationService.Status.ALREADY_GRADLE);
    }

    @Test
    void keepMaven_keepsPomsUntilMigrationIsCompleted() throws Exception {
        Path remote = TestGit.initRemote("repo-multi", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-multi"));
        git.checkoutOrCreateBranch(work, "gradle-migration", "main");
        ReflectionTestUtils.setField(service, "keepMaven", true);

        assertThat(service.migrate(work, new RepoConfig(), true).status()).isEqualTo(GradleMigrationService.Status.MIGRATED);
        assertThat(work.resolve("pom.xml")).exists();
        assertThat(work.resolve("module-core/pom.xml")).exists();
        assertThat(BuildSystem.detect(work)).isEqualTo(BuildSystem.GRADLE);
        try (Git g = Git.open(work.toFile())) {
            RevCommit head = g.log().setMaxCount(1).call().iterator().next();
            assertThat(filesIn(g, head)).contains("pom.xml", "module-core/pom.xml", "settings.gradle", "build.gradle");
        }

        // rerun while still keeping Maven: nothing to do
        assertThat(service.migrate(work, new RepoConfig(), true).status())
                .isEqualTo(GradleMigrationService.Status.ALREADY_GRADLE);
        assertThat(work.resolve("pom.xml")).exists();

        // complete the migration
        ReflectionTestUtils.setField(service, "keepMaven", false);
        GradleMigrationService.Result result = service.migrate(work, new RepoConfig(), true);

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.MAVEN_REMOVED);
        assertThat(result.warnings()).isEmpty();
        assertThat(work.resolve("pom.xml")).doesNotExist();
        assertThat(work.resolve("module-core/pom.xml")).doesNotExist();
        assertThat(work.resolve("module-core/build.gradle")).exists();
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.status().call().isClean()).isTrue();
            RevCommit head = g.log().setMaxCount(1).call().iterator().next();
            assertThat(head.getFullMessage()).isEqualTo("PROJ-1 chore: remove Maven build (migration to Gradle completed)");
            assertThat(filesIn(g, head)).noneMatch(f -> f.endsWith("pom.xml")).contains("settings.gradle");
        }
        try (Git r = Git.open(remote.toFile())) {
            RevCommit pushed = r.log().add(r.getRepository().resolve("refs/heads/gradle-migration")).setMaxCount(1)
                    .call().iterator().next();
            assertThat(pushed.getFullMessage()).contains("remove Maven build");
        }
        assertThat(service.migrate(work, new RepoConfig(), true).status())
                .isEqualTo(GradleMigrationService.Status.ALREADY_GRADLE);
    }

    @Test
    void completingMigration_warnsWhenKeptPomsDriftedFromGradle() {
        Path work = TestGit.cloneFixture(git, "repo-b", tempDir);
        ReflectionTestUtils.setField(service, "keepMaven", true);
        service.migrate(work, null, false);
        // a dependency added to the pom only after the migration
        write(work.resolve("pom.xml"), read(work.resolve("pom.xml")).replace("</dependencies>",
                "<dependency><groupId>org.extra</groupId><artifactId>only-in-maven</artifactId><version>1</version></dependency></dependencies>"));

        ReflectionTestUtils.setField(service, "keepMaven", false);
        GradleMigrationService.Result result = service.migrate(work, null, false);

        assertThat(result.status()).isEqualTo(GradleMigrationService.Status.MAVEN_REMOVED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("org.extra:only-in-maven") && w.contains("Gradle is authoritative"));
        assertThat(work.resolve("pom.xml")).doesNotExist();
    }

    @Test
    void handWrittenGradleBuild_keepsItsPom() {
        Path repo = write(tempDir.resolve("hand/build.gradle"), "plugins { id 'java' }\n").getParent();
        write(repo.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>");

        assertThat(service.migrate(repo, null, false).status()).isEqualTo(GradleMigrationService.Status.ALREADY_GRADLE);
        assertThat(repo.resolve("pom.xml")).exists();
    }

    private Path migratedAndPushed(String fixture) {
        Path remote = TestGit.initRemote(fixture, tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/" + fixture));
        git.checkoutOrCreateBranch(work, "gradle-migration", "main");
        assertThat(service.migrate(work, new RepoConfig(), true).status()).isEqualTo(GradleMigrationService.Status.MIGRATED);
        return work;
    }

    private static org.eclipse.jgit.lib.ObjectId remoteRef(Path work, String branch) throws Exception {
        try (Git g = Git.open(work.toFile())) {
            String url = g.getRepository().getConfig().getString("remote", "origin", "url");
            try (Git r = Git.open(new java.io.File(java.net.URI.create(url)))) {
                return r.getRepository().resolve("refs/heads/" + branch);
            }
        }
    }

    @Test
    void reset_deletesBranchEverywhere_cleansCloneAndMigratesFromScratch() throws Exception {
        Path work = migratedAndPushed("repo-multi");
        write(work.resolve("leftover/notes.txt"), "untracked");
        assertThat(remoteRef(work, "gradle-migration")).isNotNull();

        GradleMigrationService.ResetResult reset = service.reset(work, "gradle-migration", "main", false, new RepoConfig());

        assertThat(reset.ok()).isTrue();
        assertThat(reset.message()).contains("discarded 1 commit(s)", "deleted origin/gradle-migration");
        assertThat(remoteRef(work, "gradle-migration")).isNull();
        assertThat(work.resolve("pom.xml")).exists();
        assertThat(work.resolve("settings.gradle")).doesNotExist();
        assertThat(work.resolve("leftover")).doesNotExist();
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.getRepository().getBranch()).isEqualTo("gradle-migration");
            assertThat(g.getRepository().resolve("HEAD")).isEqualTo(g.getRepository().resolve("refs/remotes/origin/main"));
            assertThat(g.getRepository().resolve("refs/remotes/origin/gradle-migration")).isNull();
        }

        // ... and the migration starts over and pushes a fresh branch
        ReflectionTestUtils.setField(service, "dsl", "kotlin");
        assertThat(service.migrate(work, new RepoConfig(), true).status()).isEqualTo(GradleMigrationService.Status.MIGRATED);
        assertThat(work.resolve("settings.gradle.kts")).exists();
        assertThat(remoteRef(work, "gradle-migration")).isNotNull();
        assertThat(git.commitsAhead(work, "gradle-migration", "main"))
                .containsExactly("PROJ-1 chore: migrate build from Maven to Gradle");
    }

    @Test
    void reset_refusesBranchWithManualCommits_unlessForced() throws Exception {
        Path work = migratedAndPushed("repo-a");
        write(work.resolve("build.gradle"), read(work.resolve("build.gradle")) + "\n// hand-tuned\n");
        git.commitAllIfDirty(work, "PROJ-1 fix: tune gradle build");
        git.push(work);

        GradleMigrationService.ResetResult refused = service.reset(work, "gradle-migration", "main", false, new RepoConfig());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).contains("1 commit(s) not made by the migration", "fix: tune gradle build",
                "migrate.reset.force=true");
        assertThat(remoteRef(work, "gradle-migration")).isNotNull();
        assertThat(read(work.resolve("build.gradle"))).contains("hand-tuned");

        assertThat(service.reset(work, "gradle-migration", "main", true, new RepoConfig()).ok()).isTrue();
        assertThat(remoteRef(work, "gradle-migration")).isNull();
        assertThat(work.resolve("pom.xml")).exists();
    }

    @Test
    void reset_inDryMode_onlyResetsLocally() throws Exception {
        Path work = migratedAndPushed("repo-a");
        org.eclipse.jgit.lib.ObjectId pushed = remoteRef(work, "gradle-migration");
        RepoConfig dry = new RepoConfig();
        dry.setDryRun(true);

        GradleMigrationService.ResetResult reset = service.reset(work, "gradle-migration", "main", false, dry);

        assertThat(reset.message()).contains("dry mode");
        assertThat(remoteRef(work, "gradle-migration")).isEqualTo(pushed);
        assertThat(work.resolve("pom.xml")).exists();
        git.pull(work);   // no tracking ref any more: does not bring the old migration back
        assertThat(work.resolve("build.gradle")).doesNotExist();
    }

    @Test
    void reset_neverDeletesTheSourceBranch() {
        Path work = TestGit.cloneFixture(git, "repo-a", tempDir);
        assertThat(service.reset(work, "main", "main", true, null).ok()).isFalse();
    }

    @Test
    void reset_ofBranchThatNeverExisted_justCreatesIt() throws Exception {
        Path remote = TestGit.initRemote("repo-a", tempDir);
        Path work = git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work/repo-a"));

        GradleMigrationService.ResetResult reset = service.reset(work, "gradle-migration", "main", false, new RepoConfig());

        assertThat(reset.ok()).isTrue();
        assertThat(reset.message()).contains("discarded 0 commit(s)");
        try (Git g = Git.open(work.toFile())) {
            assertThat(g.getRepository().getBranch()).isEqualTo("gradle-migration");
        }
    }

    private static FileMode modeOf(Git g, RevCommit commit, String path) throws Exception {
        try (TreeWalk walk = TreeWalk.forPath(g.getRepository(), path, commit.getTree())) {
            return walk.getFileMode(0);
        }
    }

    private static List<String> filesIn(Git g, RevCommit commit) throws Exception {
        List<String> files = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(g.getRepository())) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) files.add(walk.getPathString());
        }
        return files;
    }
}
