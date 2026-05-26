package com.example.multibuild.web.dto;

import java.util.List;

public record GraphResponse(List<NodeDto> nodes, List<EdgeDto> edges, List<List<String>> layers) {

    public record NodeDto(String id, String name, String version, String buildService) {}

    public record EdgeDto(String from, String to, List<String> dependencyVersions) {}
}
