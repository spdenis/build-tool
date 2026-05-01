package com.example.multibuild.model;

import java.util.List;

public class RepositoryProject {
    private final String name;
    private final List<Module> modules;
    // Parents declared on aggregator POMs (packaging=pom with submodules). These are not captured
    // in any Module's dependency list because aggregator POMs are not added as modules.
    private final List<Artifact> aggregatorParents;

    public RepositoryProject(String name, List<Module> modules, List<Artifact> aggregatorParents) {
        this.name = name;
        this.modules = modules;
        this.aggregatorParents = aggregatorParents;
    }

    public String getName() { return name; }
    public List<Module> getModules() { return modules; }
    public List<Artifact> getAggregatorParents() { return aggregatorParents; }
}
