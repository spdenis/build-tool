package com.example.multibuild.build;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

// Thrown when one or more repos fail to build.
// getSucceeded() contains repos that completed successfully before or alongside the failure
// so callers can record partial progress for resume.
public class BuildFailedException extends RuntimeException {

    private final Set<Path> succeeded;

    public BuildFailedException(String message, Set<Path> succeeded) {
        super(message);
        this.succeeded = Collections.unmodifiableSet(new LinkedHashSet<>(succeeded));
    }

    public Set<Path> getSucceeded() {
        return succeeded;
    }
}
