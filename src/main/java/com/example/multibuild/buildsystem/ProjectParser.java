package com.example.multibuild.buildsystem;

import com.example.multibuild.model.RepositoryProject;

import java.nio.file.Path;

// Discovers the artifacts a repository produces and the artifacts they depend on.
public interface ProjectParser {
    RepositoryProject parse(Path repoDir);
}
