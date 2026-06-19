package com.example.multibuild.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeState {

    // For release builds: true once Phase 1 (version set + tag + push) is complete for all repos.
    private boolean releasePhase1Complete = false;

    private List<RepoEntry> repos = new ArrayList<>();

    // Called after every state change so the caller can persist to disk immediately.
    // Not serialized — set at runtime by Main before any build starts.
    @JsonIgnore
    private Runnable onUpdate;

    public void setOnUpdate(Runnable onUpdate) {
        this.onUpdate = onUpdate;
    }

    public boolean isReleasePhase1Complete() { return releasePhase1Complete; }

    public void setReleasePhase1Complete(boolean b) {
        this.releasePhase1Complete = b;
        notifyUpdate();
    }

    public List<RepoEntry> getRepos() { return repos; }
    public void setRepos(List<RepoEntry> repos) { this.repos = repos; }

    // Phase 1 (before cloning): creates PENDING entries from repos.json configs.
    // Uses the URL to derive the directory name, matching the logic in cloneAndBranch.
    // Existing entries from a previous run are preserved.
    public void initReposFromConfigs(List<RepoConfig> configs) {
        Set<String> existing = repos.stream().map(RepoEntry::getName).collect(Collectors.toSet());
        for (RepoConfig config : configs) {
            String name = repoNameFromUrl(config.getUrl());
            if (!existing.contains(name)) {
                RepoEntry entry = new RepoEntry();
                entry.setName(name);
                entry.setConfig(config);
                entry.setLayer(-1);
                repos.add(entry);
                existing.add(name);
            }
        }
    }

    // Phase 2 (after graph computation): rebuilds the repos list in topological order and
    // refreshes every layer index. New repos (not yet in state) are added as PENDING.
    // Repos that exist in the old state but are no longer in allLayers (removed from
    // repos.json) are appended after the ordered entries so they stay visible for audit.
    public void initRepos(List<List<Path>> allLayers, Map<Path, RepoConfig> configByPath) {
        Map<String, RepoEntry> existing = repos.stream()
                .collect(Collectors.toMap(RepoEntry::getName, e -> e, (a, b) -> a));

        List<RepoEntry> ordered = new ArrayList<>();
        Set<String> inCurrentBuild = new LinkedHashSet<>();

        for (int layer = 0; layer < allLayers.size(); layer++) {
            for (Path p : allLayers.get(layer)) {
                String name = p.getFileName().toString();
                RepoEntry entry = existing.get(name);
                if (entry == null) {
                    entry = new RepoEntry();
                    entry.setName(name);
                    entry.setConfig(configByPath.get(p));
                }
                entry.setLayer(layer);
                ordered.add(entry);
                inCurrentBuild.add(name);
            }
        }

        // Preserve entries that are no longer in the current build (e.g. removed from
        // repos.json). They keep their status so history is not lost.
        for (RepoEntry old : existing.values()) {
            if (!inCurrentBuild.contains(old.getName())) {
                ordered.add(old);
            }
        }

        this.repos = ordered;
    }

    // Returns the version that was actually built for each completed repo in the given list.
    // Used on resume so that version maps reference the real built version rather than the
    // current pom version (which may have been bumped to next snapshot after the build).
    public Map<Path, String> getCompletedVersionsByRepo(Collection<Path> repoRoots) {
        Map<String, String> byName = repos.stream()
                .filter(e -> e.getStatus() == RepoStatus.SUCCESS && e.getVersion() != null)
                .collect(Collectors.toMap(RepoEntry::getName, RepoEntry::getVersion, (a, b) -> a));
        Map<Path, String> result = new LinkedHashMap<>();
        for (Path repo : repoRoots) {
            String v = byName.get(repo.getFileName().toString());
            if (v != null) result.put(repo, v);
        }
        return result;
    }

    // Derived view used by BuildService implementations to skip already-completed repos.
    public Set<String> getCompletedRepoNames() {
        return repos.stream()
                .filter(e -> e.getStatus() == RepoStatus.SUCCESS)
                .map(RepoEntry::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // Used by ReleaseService to check if a repo's full release cycle is done.
    public boolean isCompleted(Path repoRoot) {
        String name = repoRoot.getFileName().toString();
        return repos.stream().anyMatch(e -> name.equals(e.getName()) && e.getStatus() == RepoStatus.SUCCESS);
    }

    public synchronized void markCompleted(Path repoRoot) {
        markCompleted(repoRoot, null);
    }

    // version: the version string that was actually built (e.g. "1.2.3" for release,
    // "1.2.3-branch-SNAPSHOT" for snapshot). Null if unknown.
    public synchronized void markCompleted(Path repoRoot, String version) {
        findEntry(repoRoot.getFileName().toString()).ifPresent(e -> {
            e.setStatus(RepoStatus.SUCCESS);
            e.setVersion(version);
            e.setCompletedAt(Instant.now().toString());
            e.setError(null);
        });
        notifyUpdate();
    }

    public synchronized void markFailed(Path repoRoot, String error) {
        findEntry(repoRoot.getFileName().toString()).ifPresent(e -> {
            e.setStatus(RepoStatus.FAILED);
            e.setCompletedAt(Instant.now().toString());
            e.setError(error);
        });
        notifyUpdate();
    }

    private Optional<RepoEntry> findEntry(String name) {
        return repos.stream().filter(e -> name.equals(e.getName())).findFirst();
    }

    private void notifyUpdate() {
        if (onUpdate != null) onUpdate.run();
    }

    private static String repoNameFromUrl(String url) {
        if (url == null || url.isBlank()) return "unknown";
        String last = url.substring(url.lastIndexOf('/') + 1);
        return last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoEntry {

        private String name;
        private RepoConfig config;
        private int layer = -1;
        private RepoStatus status = RepoStatus.PENDING;
        // The version that was built. Set on success; null while pending or on failure.
        private String version;
        private String completedAt;
        private String error;

        public RepoEntry() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public RepoConfig getConfig() { return config; }
        public void setConfig(RepoConfig config) { this.config = config; }

        public int getLayer() { return layer; }
        public void setLayer(int layer) { this.layer = layer; }

        public RepoStatus getStatus() { return status; }
        public void setStatus(RepoStatus status) { this.status = status; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getCompletedAt() { return completedAt; }
        public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
