package com.example.multibuild.git;

import java.nio.file.Path;
import java.util.List;

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

    // Pushes the current branch only if local is ahead of the remote tracking branch.
    // No-op when already up to date. Pushes unconditionally when no remote tracking ref exists.
    // Used to flush commits left behind by a previous dry-mode run.
    void pushIfAhead(Path repoDir);

    // Subjects of the commits on `branch` (origin's copy when it exists there, else the local one) that are
    // not on origin/<baseBranch>, newest first. Empty when the branch exists nowhere.
    List<String> commitsAhead(Path repoDir, String branch, String baseBranch);

    // Deletes `branch` (and its remote tracking ref) locally and, when deleteRemote is true, on origin.
    // Missing branches are skipped.
    // Returns true when a remote branch was deleted.
    boolean deleteBranch(Path repoDir, String branch, boolean deleteRemote);

    // Discards local changes and untracked files, then checks out `branch` freshly created from
    // origin/<sourceBranch> (an existing local branch of that name is replaced).
    void createFreshBranch(Path repoDir, String branch, String sourceBranch);

    void createTag(Path repoDir, String tagName, String message);

    // Deletes the tag locally and from the remote if it exists. No-op if it doesn't exist.
    void deleteTagIfExists(Path repoDir, String tagName);

    void pushTag(Path repoDir, String tagName);

    // Force-pushes the tag, overwriting the remote if it already exists.
    // Use in Phase 2 of a release to avoid "rejected: already exists" when retrying
    // after a crash that pushed the tag but did not finish the build.
    void pushTagForce(Path repoDir, String tagName);
}
