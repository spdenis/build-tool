package com.example.multibuild.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeState {

    // For release builds: true once Phase 1 (version set + tag + push) is complete for all repos.
    private boolean releasePhase1Complete = false;

    // Directory names (last path component) of repos that fully completed their build cycle.
    // Snapshot: built successfully. Release: built + bumped to next SNAPSHOT + pushed.
    private Set<String> completedRepoNames = new LinkedHashSet<>();

    public boolean isReleasePhase1Complete() { return releasePhase1Complete; }
    public void setReleasePhase1Complete(boolean b) { this.releasePhase1Complete = b; }

    public Set<String> getCompletedRepoNames() { return completedRepoNames; }
    public void setCompletedRepoNames(Set<String> names) { this.completedRepoNames = names; }

    public boolean isCompleted(Path repoRoot) {
        return completedRepoNames.contains(repoRoot.getFileName().toString());
    }

    public synchronized void markCompleted(Path repoRoot) {
        completedRepoNames.add(repoRoot.getFileName().toString());
    }
}
