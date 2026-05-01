package com.example.multibuild.git;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public class JGitService implements GitService {

    private static final Logger log = LoggerFactory.getLogger(JGitService.class);

    @Value("${github.token:}")
    private String githubToken;

    @Override
    public Path cloneRepo(String url, Path targetDir) {
        FileSystemUtils.deleteRecursively(targetDir.toFile());
        log.info("Cloning {} into {}", url, targetDir.toAbsolutePath());
        try {
            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(targetDir.toFile());

            if (!githubToken.isBlank()) {
                cmd.setCredentialsProvider(credentials());
            }

            cmd.call().close();
            return targetDir;
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to clone " + url, e);
        }
    }

    @Override
    public boolean checkoutOrCreateBranch(Path repoDir, String branchName, String sourceBranch) {
        try (Git git = Git.open(repoDir.toFile())) {
            // Already on this branch (e.g. it's the default branch)
            if (branchName.equals(git.getRepository().getBranch())) {
                return false;
            }

            // Local branch exists
            boolean localExists = git.branchList().call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/heads/" + branchName));
            if (localExists) {
                git.checkout().setName(branchName).call();
                return false;
            }

            // Remote branch exists — create local tracking branch
            List<Ref> remoteBranches = git.branchList()
                    .setListMode(ListBranchCommand.ListMode.REMOTE)
                    .call();
            boolean remoteExists = remoteBranches.stream()
                    .anyMatch(ref -> ref.getName().equals("refs/remotes/origin/" + branchName));
            if (remoteExists) {
                git.checkout()
                        .setCreateBranch(true)
                        .setName(branchName)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .setStartPoint("origin/" + branchName)
                        .call();
                return false;
            }

            // Branch doesn't exist anywhere — create it from sourceBranch
            log.info("Creating branch {} from origin/{} in {}", branchName, sourceBranch, repoDir.getFileName());
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .setStartPoint("refs/remotes/origin/" + sourceBranch)
                    .call();
            return true;

        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to handle branch " + branchName + " in " + repoDir, e);
        }
    }

    @Override
    public void commitAll(Path repoDir, String message) {
        try (Git git = Git.open(repoDir.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to commit in " + repoDir, e);
        }
    }

    @Override
    public void push(Path repoDir) {
        try (Git git = Git.open(repoDir.toFile())) {
            String branch = git.getRepository().getBranch();
            log.info("Pushing branch {} in {}", branch, repoDir.getFileName());

            var push = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch));

            if (!githubToken.isBlank()) {
                push.setCredentialsProvider(credentials());
            }

            push.call();
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to push in " + repoDir, e);
        }
    }

    @Override
    public void createTag(Path repoDir, String tagName, String message) {
        try (Git git = Git.open(repoDir.toFile())) {
            git.tag().setName(tagName).setMessage(message).call();
            log.info("Created tag {} in {}", tagName, repoDir.getFileName());
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to create tag " + tagName + " in " + repoDir, e);
        }
    }

    @Override
    public void pushTag(Path repoDir, String tagName) {
        try (Git git = Git.open(repoDir.toFile())) {
            log.info("Pushing tag {} in {}", tagName, repoDir.getFileName());
            var push = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/tags/" + tagName + ":refs/tags/" + tagName));
            if (!githubToken.isBlank()) {
                push.setCredentialsProvider(credentials());
            }
            push.call();
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to push tag " + tagName + " in " + repoDir, e);
        }
    }

    private UsernamePasswordCredentialsProvider credentials() {
        return new UsernamePasswordCredentialsProvider("token", githubToken);
    }
}
