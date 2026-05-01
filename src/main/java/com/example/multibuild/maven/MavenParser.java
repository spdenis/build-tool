package com.example.multibuild.maven;

import com.example.multibuild.model.RepositoryProject;

import java.nio.file.Path;

public interface MavenParser {
    RepositoryProject parse(Path repoDir);
}
