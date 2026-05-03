package com.example.multibuild.git;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Collections;
import java.util.List;

@Service
public class JGitService implements GitService {

    private static final Logger log = LoggerFactory.getLogger(JGitService.class);

    @Value("${github.token:}")
    private String githubToken;

    @Value("${ssh.key.path:}")
    private String sshKeyPath;

    @Value("${ssh.passphrase:}")
    private String sshPassphrase;

    @Value("${ssh.strict.host.key.checking:true}")
    private boolean sshStrictHostKeyChecking;

    private volatile SshdSessionFactory sshdFactory;

    @Override
    public Path cloneRepo(String url, Path targetDir) {
        FileSystemUtils.deleteRecursively(targetDir.toFile());
        log.info("Cloning {} into {}", url, targetDir.toAbsolutePath());
        try {
            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(targetDir.toFile())
                    .setTransportConfigCallback(sshTransportCallback());

            if (!isSsh(url) && !githubToken.isBlank()) {
                cmd.setCredentialsProvider(httpCredentials());
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
            if (branchName.equals(git.getRepository().getBranch())) {
                return false;
            }

            boolean localExists = git.branchList().call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/heads/" + branchName));
            if (localExists) {
                git.checkout().setName(branchName).call();
                return false;
            }

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
    public boolean hasRemoteBranch(Path repoDir, String branchName) {
        try (Git git = Git.open(repoDir.toFile())) {
            return git.branchList()
                    .setListMode(ListBranchCommand.ListMode.REMOTE)
                    .call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/remotes/origin/" + branchName));
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to list branches in " + repoDir, e);
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
    public boolean commitAllIfDirty(Path repoDir, String message) {
        try (Git git = Git.open(repoDir.toFile())) {
            git.add().addFilepattern(".").call();
            if (git.status().call().isClean()) return false;
            git.commit().setMessage(message).call();
            return true;
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
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .setTransportConfigCallback(sshTransportCallback());

            if (!isSshRemote(git) && !githubToken.isBlank()) {
                push.setCredentialsProvider(httpCredentials());
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
    public void deleteTagIfExists(Path repoDir, String tagName) {
        try (Git git = Git.open(repoDir.toFile())) {
            boolean localExists = git.tagList().call().stream()
                    .anyMatch(ref -> ref.getName().equals("refs/tags/" + tagName));
            if (!localExists) return;

            log.info("Tag {} already exists in {}, deleting local and remote", tagName, repoDir.getFileName());
            git.tagDelete().setTags(tagName).call();

            var push = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec(":refs/tags/" + tagName))
                    .setTransportConfigCallback(sshTransportCallback());
            if (!isSshRemote(git) && !githubToken.isBlank()) {
                push.setCredentialsProvider(httpCredentials());
            }
            push.call();
            log.info("Deleted remote tag {} in {}", tagName, repoDir.getFileName());
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to delete tag " + tagName + " in " + repoDir, e);
        }
    }

    @Override
    public void pushTag(Path repoDir, String tagName) {
        try (Git git = Git.open(repoDir.toFile())) {
            log.info("Pushing tag {} in {}", tagName, repoDir.getFileName());
            var push = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/tags/" + tagName + ":refs/tags/" + tagName))
                    .setTransportConfigCallback(sshTransportCallback());
            if (!isSshRemote(git) && !githubToken.isBlank()) {
                push.setCredentialsProvider(httpCredentials());
            }
            push.call();
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to push tag " + tagName + " in " + repoDir, e);
        }
    }

    // --- Transport helpers ---

    private TransportConfigCallback sshTransportCallback() {
        SshdSessionFactory factory = sshdSessionFactory();
        return transport -> {
            if (transport instanceof SshTransport sshTransport) {
                sshTransport.setSshSessionFactory(factory);
            }
        };
    }

    private synchronized SshdSessionFactory sshdSessionFactory() {
        if (sshdFactory != null) return sshdFactory;

        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(FS.DETECTED.userHome())
                .setSshDirectory(new File(System.getProperty("user.home"), ".ssh"));

        if (!sshKeyPath.isBlank()) {
            Path keyFile = Path.of(sshKeyPath);
            builder.setDefaultIdentities(ignored -> List.of(keyFile));
        }

        if (!sshPassphrase.isBlank()) {
            char[] pp = sshPassphrase.toCharArray();
            builder.setKeyPasswordProvider(ignored -> new KeyPasswordProvider() {
                private int maxAttempts = 1;

                @Override
                public char[] getPassphrase(URIish uri, int attempt) {
                    return attempt < maxAttempts ? pp.clone() : null;
                }

                @Override
                public void setAttempts(int attempts) {
                    this.maxAttempts = attempts;
                }

                @Override
                public boolean keyLoaded(URIish uri, int attempt, Exception err) {
                    return false;
                }
            });
        }

        if (!sshStrictHostKeyChecking) {
            builder.setServerKeyDatabase((homeDir, sshDir) -> new ServerKeyDatabase() {
                @Override
                public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress,
                                              ServerKeyDatabase.Configuration config) {
                    return Collections.emptyList();
                }

                @Override
                public boolean accept(String connectAddress, InetSocketAddress remoteAddress,
                                      PublicKey serverKey, ServerKeyDatabase.Configuration config,
                                      CredentialsProvider provider) {
                    return true;
                }
            });
        }

        sshdFactory = builder.build(null);
        return sshdFactory;
    }

    private static boolean isSsh(String url) {
        return url.startsWith("git@") || url.startsWith("ssh://");
    }

    private static boolean isSshRemote(Git git) {
        String url = git.getRepository().getConfig().getString("remote", "origin", "url");
        return url != null && isSsh(url);
    }

    private UsernamePasswordCredentialsProvider httpCredentials() {
        return new UsernamePasswordCredentialsProvider("token", githubToken);
    }
}
