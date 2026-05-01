package com.example.multibuild.git;

import java.nio.file.Path;

public interface GitService {
    Path cloneRepo(String url, Path targetDir);

    // Returns true if the branch was newly created (didn't exist locally or remotely).
    // sourceBranch is used as the start point when creating a new branch.
    boolean checkoutOrCreateBranch(Path repoDir, String branchName, String sourceBranch);

    void commitAll(Path repoDir, String message);

    void push(Path repoDir);

    void createTag(Path repoDir, String tagName, String message);

    void pushTag(Path repoDir, String tagName);
}
