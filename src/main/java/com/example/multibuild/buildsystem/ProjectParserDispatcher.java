package com.example.multibuild.buildsystem;

import com.example.multibuild.gradle.GradleParser;
import com.example.multibuild.maven.MavenParser;
import com.example.multibuild.model.RepositoryProject;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

// Parses each repo with the parser of its build system.
@Component
@Primary
public class ProjectParserDispatcher implements ProjectParser {

    private final MavenParser mavenParser;
    private final GradleParser gradleParser;

    public ProjectParserDispatcher(MavenParser mavenParser, GradleParser gradleParser) {
        this.mavenParser = mavenParser;
        this.gradleParser = gradleParser;
    }

    @Override
    public RepositoryProject parse(Path repoDir) {
        return switch (BuildSystem.detect(repoDir)) {
            case GRADLE -> gradleParser.parse(repoDir);
            case MAVEN -> mavenParser.parse(repoDir);
            case NONE -> new RepositoryProject(repoDir.getFileName().toString(), repoDir, List.of(), List.of());
        };
    }
}
