package com.example.multibuild.graph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// A component dependency graph (whole or around one target) together with its build order.
// layers is null when the graph has a cycle; cycleError then explains it.
public record GraphReport(DependencyGraph<String> graph,
                          String target,
                          AnalyzeScope scope,
                          List<List<String>> layers,
                          String cycleError) {

    public static GraphReport full(DependencyGraph<String> graph) {
        return create(graph, null, null);
    }

    public static GraphReport around(DependencyGraph<String> graph, String target, AnalyzeScope scope) {
        if (!graph.getAdjacencyList().containsKey(target)) {
            throw new IllegalArgumentException("Component '" + target + "' not found. Available: "
                    + String.join(", ", graph.getAdjacencyList().keySet()));
        }
        Set<String> keep = new LinkedHashSet<>();
        keep.add(target);
        if (scope != AnalyzeScope.DOWNSTREAM) keep.addAll(graph.transitiveDependenciesOf(target));
        if (scope != AnalyzeScope.UPSTREAM) keep.addAll(graph.transitiveConsumersOf(target));
        return create(graph.subgraph(keep), target, scope);
    }

    private static GraphReport create(DependencyGraph<String> graph, String target, AnalyzeScope scope) {
        try {
            return new GraphReport(graph, target, scope, graph.topologicalLayers(), null);
        } catch (RuntimeException e) {
            return new GraphReport(graph, target, scope, null, e.getMessage());
        }
    }

    public boolean hasCycle() {
        return layers == null;
    }

    public List<String> buildOrder() {
        return layers == null ? List.of() : layers.stream().flatMap(List::stream).toList();
    }

    public String title() {
        if (target == null) return "Dependency graph: all components";
        return "Dependency graph: " + target + " (" + scope.name().toLowerCase() + ")";
    }
}
