package com.example.multibuild.graph;

import java.util.Locale;

public enum GraphFormat {
    ASCII(new AsciiGraphRenderer()),
    PLANTUML(new PlantUmlGraphRenderer());

    private final GraphRenderer renderer;

    GraphFormat(GraphRenderer renderer) {
        this.renderer = renderer;
    }

    public GraphRenderer renderer() {
        return renderer;
    }

    public static GraphFormat parse(String value) {
        String v = value.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (v.equals("PUML") || v.equals("UML")) return PLANTUML;
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown analyze.format '" + value
                    + "'. Expected one of: ascii, plantuml");
        }
    }
}
