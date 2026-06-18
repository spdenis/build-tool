package com.example.multibuild.git;

import java.nio.file.Path;

public interface GitService {
    Path cloneRepo(String url, Path targetDir);

    // Returns true if the branch was newly created (didn't exist locally or remotely).
    // sourceBranch is used as the start point when creating a new branch.
    boolean checkoutOrCreateBranch(Path repoDir, String branchName, String sourceBranch);

    boolean hasRemoteBranch(Path repoDir, String branchName);

    void commitAll(Path repoDir, String message);

    // Stages all changes and commits only if there is something to commit.
    // Returns true if a commit was made, false if the working tree was already clean.
    boolean commitAllIfDirty(Path repoDir, String message);

    // Resets the current branch to origin/<currentBranch> (hard reset).
    // No-op if the remote tracking ref does not exist (branch not pushed yet).
    void pull(Path repoDir);

    void push(Path repoDir);

    void createTag(Path repoDir, String tagName, String message);

    // Deletes the tag locally and from the remote if it exists. No-op if it doesn't exist.
    void deleteTagIfExists(Path repoDir, String tagName);

    void pushTag(Path repoDir, String tagName);

    // Force-pushes the tag, overwriting the remote if it already exists.
    // Use in Phase 2 of a release to avoid "rejected: already exists" when retrying
    // after a crash that pushed the tag but did not finish the build.
    void pushTagForce(Path repoDir, String tagName);
}
