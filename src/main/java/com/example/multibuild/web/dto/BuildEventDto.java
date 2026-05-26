package com.example.multibuild.web.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BuildEventDto.LogLine.class, name = "LOG"),
        @JsonSubTypes.Type(value = BuildEventDto.LayerStarted.class, name = "LAYER_STARTED"),
        @JsonSubTypes.Type(value = BuildEventDto.RepoStarted.class, name = "REPO_STARTED"),
        @JsonSubTypes.Type(value = BuildEventDto.RepoFinished.class, name = "REPO_FINISHED"),
        @JsonSubTypes.Type(value = BuildEventDto.BuildFinished.class, name = "BUILD_FINISHED")
})
public sealed interface BuildEventDto permits
        BuildEventDto.LogLine,
        BuildEventDto.LayerStarted,
        BuildEventDto.RepoStarted,
        BuildEventDto.RepoFinished,
        BuildEventDto.BuildFinished {

    record LogLine(String message, String level, long timestamp) implements BuildEventDto {}

    record LayerStarted(int layerIndex, int totalLayers, List<String> repos) implements BuildEventDto {}

    record RepoStarted(String repo, int layerIndex) implements BuildEventDto {}

    record RepoFinished(String repo, boolean success, String errorMessage, long durationMs) implements BuildEventDto {}

    record BuildFinished(boolean success, String errorMessage, long totalDurationMs) implements BuildEventDto {}
}
