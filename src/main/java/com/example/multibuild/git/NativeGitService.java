package com.example.multibuild.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "git.implementation", havingValue = "native")
public class NativeGitService implements GitService {

    private static final Logger log = LoggerFactory.getLogger(NativeGitService.class);

    @Value("${git.clone.depth:0}")
    private int cloneDepth;

    @Value("${git.proxy.host:}")
    private String proxyHost;

    @Value("${git.proxy.port:8080}")
    private int proxyPort;

    @Value("${git.proxy.username:}")
    private String proxyUsername;

    @Value("${git.proxy.password:}")
    private String proxyPassword;

    // Comma-separated domains that bypass the proxy (no_proxy / NO_PROXY).
    // Note: proxyDomains (include-only list) has no env-var equivalent in native git;
    // configure no_proxy for bypass instead.
    @Value("${git.proxy.bypass:}")
    private String proxyBypass;

    private Map<String, String> gitEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (!proxyHost.isBlank()) {
            String proxyUrl = buildProxyUrl();
            env.put("http_proxy", proxyUrl);
            env.put("HTTP_PROXY", proxyUrl);
            env.put("https_proxy", proxyUrl);
            env.put("HTTPS_PROXY", proxyUrl);
        }
        if (!proxyBypass.isBlank()) {
            env.put("no_proxy", proxyBypass);
            env.put("NO_PROXY", proxyBypass);
        }
        return env;
    }

    private String buildProxyUrl() {
        if (!proxyUsername.isBlank()) {
            return String.format("http://%s:%s@%s:%d", proxyUsername, proxyPassword, proxyHost, proxyPort);
        }
        return String.format("http://%s:%d", proxyHost, proxyPort);
    }

    // Runs a git command in dir, captures stdout+stderr, throws on non-zero exit.
    private String exec(Path dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(dir != null ? dir.toFile() : null)
                    .redirectErrorStream(true);
            pb.environment().putAll(gitEnv());
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = proc.waitFor();
            if (exit != 0) {
                String location = dir != null ? " [" + dir.getFileName() + "]" : "";
                throw new RuntimeException("git command failed (exit " + exit + ")" + location + ": "
                        + String.join(" ", cmd) + "\n" + output.trim());
            }
            return output;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to run: " + String.join(" ", cmd), e);
        }
    }

    // Runs a git command with live I/O (for clone, fetch, push). Throws on non-zero exit.
    private void execLive(Path dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(dir != null ? dir.toFile() : null)
                    .inheritIO();
            pb.environment().putAll(gitEnv());
            int exit = pb.start().waitFor();
            if (exit != 0) {
                String location = dir != null ? " [" + dir.getFileName() + "]" : "";
                throw new RuntimeException("git command failed (exit " + exit + ")" + location + ": "
                        + String.join(" ", cmd));
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to run: " + String.join(" ", cmd), e);
        }
    }

    @Override
    public Path cloneRepo(String url, Path targetDir) {
        if (targetDir.toFile().exists() && targetDir.resolve(".git").toFile().exists()) {
            return reuseRepo(url, targetDir);
        }
        return doClone(url, targetDir);
    }

    private Path reuseRepo(String url, Path targetDir) {
        log.info("Reusing existing repo {} — fetching latest", targetDir.getFileName());
        String configuredUrl = exec(targetDir, "git", "remote", "get-url", "origin").trim();
        if (!url.equals(configuredUrl)) {
            log.warn("  Remote URL mismatch in {} (expected '{}', found '{}') — re-cloning",
                    targetDir.getFileName(), url, configuredUrl);
            return doClone(url, targetDir);
        }
        exec(targetDir, "git", "reset", "--hard");
        exec(targetDir, "git", "clean", "-fd");

        List<String> fetchCmd = new ArrayList<>(List.of("git", "fetch", "origin"));
        if (cloneDepth > 0) {
            fetchCmd.addAll(List.of("--depth", String.valueOf(cloneDepth)));
        }
        execLive(targetDir, fetchCmd.toArray(String[]::new));
        return targetDir;
    }

    private Path doClone(String url, Path targetDir) {
        FileSystemUtils.deleteRecursively(targetDir.toFile());
        log.info("Cloning {} into {}", url, targetDir.toAbsolutePath());
        List<String> cmd = new ArrayList<>(List.of("git", "clone"));
        if (cloneDepth > 0) {
            cmd.addAll(List.of("--depth", String.valueOf(cloneDepth)));
        }
        cmd.add(url);
        cmd.add(targetDir.toAbsolutePath().toString());
        execLive(null, cmd.toArray(String[]::new));
        return targetDir;
    }

    @Override
    public boolean checkoutOrCreateBranch(Path repoDir, String branchName, String sourceBranch) {
        String currentBranch = exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (branchName.equals(currentBranch)) return false;

        String localMatch = exec(repoDir, "git", "branch", "--list", branchName);
        if (!localMatch.isBlank()) {
            exec(repoDir, "git", "checkout", branchName);
            return false;
        }

        String remoteMatch = exec(repoDir, "git", "branch", "-r", "--list", "origin/" + branchName);
        if (!remoteMatch.isBlank()) {
            exec(repoDir, "git", "checkout", "-b", branchName, "--track", "origin/" + branchName);
            return false;
        }

        log.info("Creating branch {} from origin/{} in {}", branchName, sourceBranch, repoDir.getFileName());
        exec(repoDir, "git", "checkout", "-b", branchName, "origin/" + sourceBranch);
        return true;
    }

    @Override
    public boolean hasRemoteBranch(Path repoDir, String branchName) {
        String result = exec(repoDir, "git", "branch", "-r", "--list", "origin/" + branchName);
        return !result.isBlank();
    }

    @Override
    public void commitAll(Path repoDir, String message) {
        exec(repoDir, "git", "add", ".");
        exec(repoDir, "git", "commit", "-m", message);
    }

    @Override
    public boolean commitAllIfDirty(Path repoDir, String message) {
        exec(repoDir, "git", "add", ".");
        String staged = exec(repoDir, "git", "status", "--porcelain");
        if (staged.isBlank()) return false;
        exec(repoDir, "git", "commit", "-m", message);
        return true;
    }

    @Override
    public void push(Path repoDir) {
        String branch = exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        log.info("Pushing branch {} in {}", branch, repoDir.getFileName());
        execLive(repoDir, "git", "push", "-u", "origin", branch);
    }

    @Override
    public void createTag(Path repoDir, String tagName, String message) {
        exec(repoDir, "git", "tag", "-a", tagName, "-m", message);
        log.info("Created tag {} in {}", tagName, repoDir.getFileName());
    }

    @Override
    public void deleteTagIfExists(Path repoDir, String tagName) {
        String tags = exec(repoDir, "git", "tag", "-l", tagName);
        if (tags.isBlank()) return;
        log.info("Tag {} already exists in {}, deleting local and remote", tagName, repoDir.getFileName());
        exec(repoDir, "git", "tag", "-d", tagName);
        execLive(repoDir, "git", "push", "origin", ":refs/tags/" + tagName);
        log.info("Deleted remote tag {} in {}", tagName, repoDir.getFileName());
    }

    @Override
    public void pushTag(Path repoDir, String tagName) {
        log.info("Pushing tag {} in {}", tagName, repoDir.getFileName());
        execLive(repoDir, "git", "push", "origin", "refs/tags/" + tagName);
    }
}
