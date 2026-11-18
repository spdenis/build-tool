package com.example.multibuild.gradle;

import com.example.multibuild.buildsystem.BuildSystem;
import com.example.multibuild.git.JGitService;
import com.example.multibuild.maven.MavenParserImpl;
import com.example.multibuild.model.RepoConfig;
import com.example.multibuild.service.CommitMessageFormatter;
import com.example.multibuild.testutil.TestGit;
import org.eclipse.jgit.api.Git;
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
                git, formatter);
        ReflectionTestUtils.setField(service, "dsl", "groovy");
        ReflectionTestUtils.setField(service, "generateWrapper", false);
        ReflectionTestUtils.setField(service, "gradleVersion", "8.10.2");
        ReflectionTestUtils.setField(service, "dryMode", false);
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
