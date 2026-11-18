package com.example.multibuild.buildsystem;

import com.example.multibuild.gradle.GradleDependencyUpdater;
import com.example.multibuild.maven.DependencyVersionUpdater;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Splits repos by build system and delegates each group to the matching dependency updater.
@Component
@Primary
public class ProjectDependencyUpdater implements DependencyUpdater {

    private final DependencyVersionUpdater maven;
    private final GradleDependencyUpdater gradle;

    public ProjectDependencyUpdater(DependencyVersionUpdater maven, GradleDependencyUpdater gradle) {
        this.maven = maven;
        this.gradle = gradle;
    }

    @Override
    public Set<Path> update(List<Path> repoRoots, Map<String, String> versionByKey) {
        List<Path> mavenRepos = new ArrayList<>();
        List<Path> gradleRepos = new ArrayList<>();
        for (Path repo : repoRoots) {
            switch (BuildSystem.detect(repo)) {
                case GRADLE -> gradleRepos.add(repo);
                case MAVEN -> mavenRepos.add(repo);
                case NONE -> { }
            }
        }
        Set<Path> modified = new LinkedHashSet<>();
        if (!mavenRepos.isEmpty()) modified.addAll(maven.update(mavenRepos, versionByKey));
        if (!gradleRepos.isEmpty()) modified.addAll(gradle.update(gradleRepos, versionByKey));
        // Keep the caller's repo order.
        Set<Path> ordered = new LinkedHashSet<>();
        for (Path repo : repoRoots) if (modified.contains(repo)) ordered.add(repo);
        return ordered;
    }
}
