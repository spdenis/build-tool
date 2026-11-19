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
import java.util.function.Supplier;

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

    // Retries apply only to commands that talk to the remote (clone, fetch, ls-remote, push) —
    // a transient network/remote error is worth retrying, a local failure (bad merge, dirty
    // tree, etc.) is not and would just waste time re-running.
    @Value("${git.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${git.retry.initial-backoff-ms:1000}")
    private long retryInitialBackoffMs;

    @Value("${git.retry.backoff-multiplier:2.0}")
    private double retryBackoffMultiplier;

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

    // Like exec(), but retries with exponential backoff — for commands that hit the remote.
    private String execRemote(Path dir, String... cmd) {
        return retry(() -> exec(dir, cmd), cmd);
    }

    // Like execLive(), but retries with exponential backoff — for commands that hit the remote.
    private void execLiveRemote(Path dir, String... cmd) {
        retry(() -> {
            execLive(dir, cmd);
            return null;
        }, cmd);
    }

    private <T> T retry(Supplier<T> op, String[] cmd) {
        long backoffMs = retryInitialBackoffMs;
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return op.get();
            } catch (RuntimeException e) {
                lastException = e;
                if (attempt == retryMaxAttempts) {
                    throw e;
                }
                log.warn("  Remote git command failed on attempt {}/{}: {} — retrying in {}ms ({})",
                        attempt, retryMaxAttempts, String.join(" ", cmd), backoffMs, e.getMessage());
            }
            sleep(backoffMs);
            backoffMs = (long) (backoffMs * retryBackoffMultiplier);
        }
        // Unreachable: the loop always returns or throws on its last attempt.
        throw lastException;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to retry git command", e);
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
                    targetDir.getFileName(), maskUrl(url), maskUrl(configuredUrl));
            return doClone(url, targetDir);
        }
        exec(targetDir, "git", "reset", "--hard");
        exec(targetDir, "git", "clean", "-fd");

        List<String> fetchCmd = new ArrayList<>(List.of("git", "fetch", "origin"));
        if (cloneDepth > 0) {
            fetchCmd.addAll(List.of("--depth", String.valueOf(cloneDepth)));
        }
        execLiveRemote(targetDir, fetchCmd.toArray(String[]::new));
        return targetDir;
    }

    private Path doClone(String url, Path targetDir) {
        FileSystemUtils.deleteRecursively(targetDir.toFile());
        log.info("Cloning {} into {}", maskUrl(url), targetDir.toAbsolutePath());
        List<String> cmd = new ArrayList<>(List.of("git", "clone"));
        if (cloneDepth > 0) {
            cmd.addAll(List.of("--depth", String.valueOf(cloneDepth)));
        }
        cmd.add(url);
        cmd.add(targetDir.toAbsolutePath().toString());
        execLiveRemote(null, cmd.toArray(String[]::new));
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

        // Use ls-remote to query the actual remote: local tracking refs may be absent
        // with shallow clones (git clone --depth N only fetches the default branch).
        String lsRemote = execRemote(repoDir, "git", "ls-remote", "--heads", "origin", branchName);
        if (!lsRemote.isBlank()) {
            // Register the branch in the remote's fetch refspecs before fetching.
            // Without this, shallow single-branch clones silently skip writing the
            // remote tracking ref (refs/remotes/origin/X), which causes both:
            //   - depth=1: checkout --track fails ("starting point not a branch")
            //   - depth=0: fetch appears to create the tracking ref but a stale local
            //              branch may already exist, causing checkout -b to fail
            exec(repoDir, "git", "remote", "set-branches", "--add", "origin", branchName);
            List<String> fetchCmd = new ArrayList<>(List.of("git", "fetch", "origin", branchName));
            if (cloneDepth > 0) {
                fetchCmd.addAll(List.of("--depth", String.valueOf(cloneDepth)));
            }
            execRemote(repoDir, fetchCmd.toArray(String[]::new));
            // DWIM checkout: if local branch already exists, switches to it;
            // if only the tracking ref exists, auto-creates a local tracking branch.
            exec(repoDir, "git", "checkout", branchName);
            return false;
        }

        log.info("Creating branch {} from origin/{} in {}", branchName, sourceBranch, repoDir.getFileName());
        // --no-track: a branch created from origin/<source> would otherwise track the SOURCE branch, which
        // makes `git push` fail ("upstream branch ... does not match the name of your current branch") or,
        // with push.default=upstream, push onto the source branch.
        exec(repoDir, "git", "checkout", "--no-track", "-b", branchName, "origin/" + sourceBranch);
        return true;
    }

    @Override
    public boolean hasRemoteBranch(Path repoDir, String branchName) {
        String result = exec(repoDir, "git", "branch", "-r", "--list", "origin/" + branchName);
        return !result.isBlank();
    }

    @Override
    public void commitAll(Path repoDir, String message) {
        exec(repoDir, "git", "add", "-A");
        exec(repoDir, "git", "commit", "-m", message);
    }

    @Override
    public boolean commitAllIfDirty(Path repoDir, String message) {
        exec(repoDir, "git", "add", "-A");
        String staged = exec(repoDir, "git", "status", "--porcelain");
        if (staged.isBlank()) return false;
        exec(repoDir, "git", "commit", "-m", message);
        return true;
    }

    @Override
    public void pull(Path repoDir) {
        String branch = exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        String trackingRef = exec(repoDir, "git", "branch", "-r", "--list", "origin/" + branch).trim();
        if (trackingRef.isBlank()) {
            log.info("  No remote tracking ref for {} in {} — skipping sync", branch, repoDir.getFileName());
            return;
        }
        log.info("  Resetting {} to origin/{} in {}", branch, branch, repoDir.getFileName());
        exec(repoDir, "git", "reset", "--hard", "origin/" + branch);
    }

    // Removes the upstream of `branch` when it is another branch (or any upstream when `any` is set).
    private void dropForeignUpstream(Path repoDir, String branch, boolean any) {
        String upstream = exec(repoDir, "git", "for-each-ref", "--format=%(upstream:short)", "refs/heads/" + branch).trim();
        if (upstream.isEmpty() || (!any && upstream.equals("origin/" + branch))) return;
        log.info("  [{}] Branch '{}' tracked '{}' — removing that upstream", repoDir.getFileName(), branch, upstream);
        exec(repoDir, "git", "branch", "--unset-upstream", branch);
    }

    @Override
    public void push(Path repoDir) {
        String branch = exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        log.info("Pushing branch {} in {}", branch, repoDir.getFileName());
        // Clones made by earlier versions may track another branch (e.g. origin/main): drop that upstream.
        dropForeignUpstream(repoDir, branch, false);
        // Full refspec: the destination is always the same-named branch, whatever push.default says.
        execLiveRemote(repoDir, "git", "push", "-u", "origin", "refs/heads/" + branch + ":refs/heads/" + branch);
    }

    @Override
    public void pushIfAhead(Path repoDir) {
        String branch = exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        String tracking = exec(repoDir, "git", "branch", "-r", "--list", "origin/" + branch).trim();
        if (tracking.isBlank()) {
            log.info("  [{}] Branch '{}' not on remote — pushing", repoDir.getFileName(), branch);
            push(repoDir);
            return;
        }
        String aheadCount = exec(repoDir, "git", "rev-list", "--count", "origin/" + branch + "..HEAD").trim();
        if (!"0".equals(aheadCount)) {
            log.info("  [{}] Local '{}' is {} commit(s) ahead of remote — pushing",
                    repoDir.getFileName(), branch, aheadCount);
            push(repoDir);
        }
    }

    // Fetches origin/<branch> into its remote tracking ref. Returns false when origin has no such branch.
    private boolean fetchBranch(Path repoDir, String branch) {
        if (execRemote(repoDir, "git", "ls-remote", "--heads", "origin", branch).isBlank()) return false;
        execRemote(repoDir, "git", "fetch", "origin", "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        return true;
    }

    private boolean refExists(Path repoDir, String ref) {
        return !exec(repoDir, "git", "for-each-ref", ref).isBlank();
    }

    @Override
    public List<String> commitsAhead(Path repoDir, String branch, String baseBranch) {
        fetchBranch(repoDir, baseBranch);
        String tip = fetchBranch(repoDir, branch) ? "refs/remotes/origin/" + branch : "refs/heads/" + branch;
        if (!refExists(repoDir, tip)) return List.of();
        List<String> cmd = new ArrayList<>(List.of("git", "log", "--format=%s", tip));
        if (refExists(repoDir, "refs/remotes/origin/" + baseBranch)) cmd.add("^refs/remotes/origin/" + baseBranch);
        String out = exec(repoDir, cmd.toArray(String[]::new));
        return out.isBlank() ? List.of() : List.of(out.strip().split("\\R"));
    }

    @Override
    public boolean deleteBranch(Path repoDir, String branch, boolean deleteRemote) {
        String current = exec(repoDir, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (branch.equals(current)) {
            throw new IllegalStateException("Cannot delete the checked-out branch " + branch + " in " + repoDir);
        }
        if (refExists(repoDir, "refs/heads/" + branch)) {
            exec(repoDir, "git", "branch", "-D", branch);
            log.info("  Deleted local branch {} in {}", branch, repoDir.getFileName());
        }
        boolean remoteDeleted = false;
        if (deleteRemote && !execRemote(repoDir, "git", "ls-remote", "--heads", "origin", branch).isBlank()) {
            execLiveRemote(repoDir, "git", "push", "origin", ":refs/heads/" + branch);
            log.info("  Deleted remote branch origin/{} in {}", branch, repoDir.getFileName());
            remoteDeleted = true;
        }
        // Drop the tracking ref too, so a later sync does not reset the branch back to the old remote copy.
        if (refExists(repoDir, "refs/remotes/origin/" + branch)) {
            exec(repoDir, "git", "branch", "-r", "-D", "origin/" + branch);
        }
        return remoteDeleted;
    }

    @Override
    public void createFreshBranch(Path repoDir, String branch, String sourceBranch) {
        exec(repoDir, "git", "reset", "--hard");
        exec(repoDir, "git", "clean", "-fd");
        fetchBranch(repoDir, sourceBranch);
        exec(repoDir, "git", "checkout", "--no-track", "-B", branch, "origin/" + sourceBranch);
        dropForeignUpstream(repoDir, branch, true);
        log.info("  Created fresh branch {} from origin/{} in {}", branch, sourceBranch, repoDir.getFileName());
    }

    @Override
    public List<String> recentCommits(Path repoDir, int max) {
        String out = exec(repoDir, "git", "log", "-n", String.valueOf(max), "--format=%h %s");
        return out.isBlank() ? List.of() : List.of(out.strip().split("\\R"));
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
        execLiveRemote(repoDir, "git", "push", "origin", ":refs/tags/" + tagName);
        log.info("Deleted remote tag {} in {}", tagName, repoDir.getFileName());
    }

    @Override
    public void pushTag(Path repoDir, String tagName) {
        log.info("Pushing tag {} in {}", tagName, repoDir.getFileName());
        execLiveRemote(repoDir, "git", "push", "origin", "refs/tags/" + tagName);
    }

    @Override
    public void pushTagForce(Path repoDir, String tagName) {
        log.info("Force-pushing tag {} in {}", tagName, repoDir.getFileName());
        execLiveRemote(repoDir, "git", "push", "--force", "origin", "refs/tags/" + tagName);
    }

    private static String maskUrl(String url) {
        return url.replaceAll("(https?://)([^@]+@)", "$1***@");
    }
}
