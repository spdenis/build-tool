package com.example.multibuild.git;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
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
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "git.implementation", havingValue = "jgit", matchIfMissing = true)
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

    // 0 means full clone; any positive value enables shallow clone with that depth.
    @Value("${git.clone.depth:0}")
    private int cloneDepth;

    @Value("${git.timeout:120}")
    private int gitTimeoutSeconds;

    @Value("${git.proxy.host:}")
    private String proxyHost;

    @Value("${git.proxy.port:8080}")
    private int proxyPort;

    @Value("${git.proxy.username:}")
    private String proxyUsername;

    @Value("${git.proxy.password:}")
    private String proxyPassword;

    // Comma-separated domains that should use the proxy. Empty = all HTTPS.
    @Value("${git.proxy.domains:}")
    private String proxyDomains;

    // Comma-separated domains that bypass the proxy.
    @Value("${git.proxy.bypass:}")
    private String proxyBypass;

    private volatile SshdSessionFactory sshdFactory;

    @PostConstruct
    private void configureProxy() {
        if (proxyHost.isBlank()) return;

        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        Set<String> includeDomains = parseDomainList(proxyDomains);
        Set<String> bypassDomainSet = parseDomainList(proxyBypass);
        ProxySelector original = ProxySelector.getDefault();

        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String scheme = uri.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                    return original.select(uri);
                }
                String host = uri.getHost();
                if (host == null) return original.select(uri);
                if (!bypassDomainSet.isEmpty() && matchesDomain(host, bypassDomainSet)) {
                    return List.of(Proxy.NO_PROXY);
                }
                if (!includeDomains.isEmpty() && !matchesDomain(host, includeDomains)) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                original.connectFailed(uri, sa, ioe);
            }
        });

        if (!proxyUsername.isBlank()) {
            String user = proxyUsername;
            char[] pass = proxyPassword.toCharArray();
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(user, pass);
                    }
                    return null;
                }
            });
        }

        log.info("Git HTTP proxy: {}:{} (domains={}, bypass={})",
                proxyHost, proxyPort,
                includeDomains.isEmpty() ? "all" : includeDomains,
                bypassDomainSet.isEmpty() ? "none" : bypassDomainSet);
    }

    private static Set<String> parseDomainList(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean matchesDomain(String host, Set<String> patterns) {
        String lower = host.toLowerCase();
        return patterns.stream().anyMatch(p -> lower.equals(p) || lower.endsWith("." + p));
    }

    @Override
    public Path cloneRepo(String url, Path targetDir) {
        File repoDir = targetDir.toFile();
        if (repoDir.exists() && new File(repoDir, ".git").exists()) {
            return reuseRepo(url, targetDir);
        }
        return doClone(url, targetDir);
    }

    private Path reuseRepo(String url, Path targetDir) {
        log.info("Reusing existing repo {} — fetching latest", targetDir.getFileName());
        try (Git git = Git.open(targetDir.toFile())) {
            String configuredUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
            if (!url.equals(configuredUrl)) {
                log.warn("  Remote URL mismatch in {} (expected '{}', found '{}') — re-cloning",
                        targetDir.getFileName(), url, configuredUrl);
                return doClone(url, targetDir);
            }
            // Discard any uncommitted changes left by a previous run
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
            git.clean().setCleanDirectories(true).setForce(true).call();

            var fetch = git.fetch()
                    .setRemote("origin")
                    .setTransportConfigCallback(sshTransportCallback())
                    .setTimeout(gitTimeoutSeconds);
            if (!isSsh(url) && !githubToken.isBlank() && !hasEmbeddedCredentials(url)) {
                fetch.setCredentialsProvider(httpCredentials());
            }
            if (cloneDepth > 0) {
                fetch.setDepth(cloneDepth);
            }
            fetch.call();
            return targetDir;
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to update " + url, e);
        }
    }

    private Path doClone(String url, Path targetDir) {
        FileSystemUtils.deleteRecursively(targetDir.toFile());
        log.info("Cloning {} into {}", url, targetDir.toAbsolutePath());
        try {
            CloneCommand cmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(targetDir.toFile())
                    .setTransportConfigCallback(sshTransportCallback())
                    .setTimeout(gitTimeoutSeconds);

            if (!isSsh(url) && !githubToken.isBlank() && !hasEmbeddedCredentials(url)) {
                cmd.setCredentialsProvider(httpCredentials());
            }
            if (cloneDepth > 0) {
                cmd.setDepth(cloneDepth);
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
                    .setTransportConfigCallback(sshTransportCallback())
                    .setTimeout(gitTimeoutSeconds);

            if (!isSshRemote(git) && !githubToken.isBlank() && !remoteHasEmbeddedCredentials(git)) {
                push.setCredentialsProvider(httpCredentials());
            }

            push.call();

            // Set upstream tracking so the branch behaves like `git push -u origin <branch>`
            StoredConfig config = git.getRepository().getConfig();
            config.setString("branch", branch, "remote", "origin");
            config.setString("branch", branch, "merge", "refs/heads/" + branch);
            config.save();
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
                    .setTransportConfigCallback(sshTransportCallback())
                    .setTimeout(gitTimeoutSeconds);
            if (!isSshRemote(git) && !githubToken.isBlank() && !remoteHasEmbeddedCredentials(git)) {
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
                    .setTransportConfigCallback(sshTransportCallback())
                    .setTimeout(gitTimeoutSeconds);
            if (!isSshRemote(git) && !githubToken.isBlank() && !remoteHasEmbeddedCredentials(git)) {
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

    private static boolean hasEmbeddedCredentials(String url) {
        try {
            String userInfo = new java.net.URI(url).getUserInfo();
            return userInfo != null && !userInfo.isBlank();
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static boolean remoteHasEmbeddedCredentials(Git git) {
        String url = git.getRepository().getConfig().getString("remote", "origin", "url");
        return url != null && hasEmbeddedCredentials(url);
    }

    private UsernamePasswordCredentialsProvider httpCredentials() {
        return new UsernamePasswordCredentialsProvider("token", githubToken);
    }
}
