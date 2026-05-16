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

    // Fast-forward merges origin/<currentBranch> into the current branch.
    // No-op if already up to date. Throws if the branches have diverged.
    void pull(Path repoDir);

    void push(Path repoDir);

    void createTag(Path repoDir, String tagName, String message);

    // Deletes the tag locally and from the remote if it exists. No-op if it doesn't exist.
    void deleteTagIfExists(Path repoDir, String tagName);

    void pushTag(Path repoDir, String tagName);
}
