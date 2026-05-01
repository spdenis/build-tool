package com.example.multibuild.model;

import java.util.List;

public class RepositoryProject {
    private final String name;
    private final List<Module> modules;

    public RepositoryProject(String name, List<Module> modules) {
        this.name = name;
        this.modules = modules;
    }

    public String getName() { return name; }
    public List<Module> getModules() { return modules; }
}
