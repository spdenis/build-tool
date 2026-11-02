package com.example.multibuild.graph;

import java.util.Locale;

// Which part of the graph to keep around a target component.
public enum AnalyzeScope {
    // The target and everything that depends on it (what must be rebuilt after it changes).
    DOWNSTREAM,
    // The target and everything it depends on (what must be built before it).
    UPSTREAM,
    // Both of the above.
    BOTH;

    public static AnalyzeScope parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown analyze.scope '" + value
                    + "'. Expected one of: downstream, upstream, both");
        }
    }
}
