package com.example.multibuild.testutil;

import com.example.multibuild.git.JGitService;
import org.eclipse.jgit.api.Git;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

// Local git remotes and a JGitService configured for them.
public final class TestGit {

    private TestGit() {}

    public static JGitService jgit() {
        JGitService git = new JGitService();
        ReflectionTestUtils.setField(git, "githubToken", "");
        ReflectionTestUtils.setField(git, "sshKeyPath", "");
        ReflectionTestUtils.setField(git, "sshPassphrase", "");
        ReflectionTestUtils.setField(git, "sshStrictHostKeyChecking", true);
        ReflectionTestUtils.setField(git, "cloneDepth", 0);
        ReflectionTestUtils.setField(git, "gitTimeoutSeconds", 120);
        ReflectionTestUtils.setField(git, "proxyHost", "");
        ReflectionTestUtils.setField(git, "proxyPort", 8080);
        ReflectionTestUtils.setField(git, "proxyUsername", "");
        ReflectionTestUtils.setField(git, "proxyPassword", "");
        ReflectionTestUtils.setField(git, "proxyDomains", "");
        ReflectionTestUtils.setField(git, "proxyBypass", "");
        return git;
    }

    // Copies fixture `name` to tempDir/remotes/name and commits it on 'main'.
    public static Path initRemote(String name, Path tempDir) {
        Path remote = Fixtures.copy(name, tempDir.resolve("remotes"));
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial commit").call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return remote;
    }

    // Remote + clone into tempDir/work/name.
    public static Path cloneFixture(JGitService git, String name, Path tempDir) {
        Path remote = initRemote(name, tempDir);
        return git.cloneRepo(remote.toUri().toString(), tempDir.resolve("work").resolve(name));
    }
}
