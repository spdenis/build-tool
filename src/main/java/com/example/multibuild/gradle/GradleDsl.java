package com.example.multibuild.gradle;

// Script language of the Gradle build files written by migrate-gradle.
public enum GradleDsl {
    GROOVY(""), KOTLIN(".kts");

    private final String extension;

    GradleDsl(String extension) {
        this.extension = extension;
    }

    public String buildFile() { return "build.gradle" + extension; }

    public String settingsFile() { return "settings.gradle" + extension; }

    public static GradleDsl parse(String value) {
        if (value == null || value.isBlank()) return GROOVY;
        return switch (value.trim().toLowerCase()) {
            case "groovy" -> GROOVY;
            case "kotlin", "kts" -> KOTLIN;
            default -> throw new IllegalArgumentException(
                    "Unknown migrate.gradle.dsl '" + value + "'. Valid values: groovy, kotlin");
        };
    }
}
