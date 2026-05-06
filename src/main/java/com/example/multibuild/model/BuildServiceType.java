package com.example.multibuild.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum BuildServiceType {
    LOCAL, TEAMCITY, LIGHTSPEED, DUMMY;

    @JsonCreator
    public static BuildServiceType fromValue(String value) {
        if (value == null) return null;
        return switch (value.toUpperCase()) {
            case "LOCAL" -> LOCAL;
            case "TEAMCITY" -> TEAMCITY;
            case "LIGHTSPEED" -> LIGHTSPEED;
            case "DUMMY" -> DUMMY;
            default -> throw new IllegalArgumentException("Unknown build service type: '" + value +
                    "'. Valid values: local, teamcity, lightspeed, dummy");
        };
    }
}
