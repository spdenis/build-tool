package com.example.multibuild.graph;

import com.example.multibuild.model.Artifact;

import java.util.*;
import java.util.stream.Collectors;

public class DependencyGraph {

    // adjacencyList[A] = [B, C] means A depends on B and C (B and C must be built before A)
    private final Map<Artifact, List<Artifact>> adjacencyList = new LinkedHashMap<>();

    public void addArtifact(Artifact artifact) {
        adjacencyList.putIfAbsent(artifact, new ArrayList<>());
    }

    public void addDependency(Artifact from, Artifact to) {
        List<Artifact> deps = adjacencyList.computeIfAbsent(from, k -> new ArrayList<>());
        if (!deps.contains(to)) {
            deps.add(to);
        }
        adjacencyList.putIfAbsent(to, new ArrayList<>());
    }

    public Map<Artifact, List<Artifact>> getAdjacencyList() {
        return Collections.unmodifiableMap(adjacencyList);
    }

    // Returns artifacts grouped into parallel layers: all artifacts in a layer
    // are independent of each other and can be built simultaneously.
    // Layers are ordered so that each layer's dependencies are fully covered by earlier layers.
    public List<List<Artifact>> topologicalLayers() {
        Map<Artifact, Integer> inDegree = new HashMap<>();
        Map<Artifact, List<Artifact>> reversedAdj = new HashMap<>();

        for (Map.Entry<Artifact, List<Artifact>> entry : adjacencyList.entrySet()) {
            Artifact artifact = entry.getKey();
            List<Artifact> deps = entry.getValue();
            inDegree.put(artifact, deps.size());
            for (Artifact dep : deps) {
                reversedAdj.computeIfAbsent(dep, k -> new ArrayList<>()).add(artifact);
            }
        }

        List<List<Artifact>> layers = new ArrayList<>();
        List<Artifact> currentLayer = new ArrayList<>();
        for (Map.Entry<Artifact, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) currentLayer.add(entry.getKey());
        }

        int processed = 0;
        while (!currentLayer.isEmpty()) {
            layers.add(currentLayer);
            processed += currentLayer.size();
            List<Artifact> nextLayer = new ArrayList<>();
            for (Artifact artifact : currentLayer) {
                for (Artifact dependent : reversedAdj.getOrDefault(artifact, Collections.emptyList())) {
                    if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                        nextLayer.add(dependent);
                    }
                }
            }
            currentLayer = nextLayer;
        }

        if (processed != adjacencyList.size()) {
            throw new RuntimeException("Cycle detected in dependency graph");
        }

        return layers;
    }

    public List<Artifact> topologicalSort() {
        return topologicalLayers().stream().flatMap(Collection::stream).toList();
    }
}
