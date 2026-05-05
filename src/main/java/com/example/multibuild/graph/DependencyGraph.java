package com.example.multibuild.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DependencyGraph<T> {

    // adjacencyList[A] = [B, C] means A depends on B and C (B and C must be built before A)
    private final Map<T, List<T>> adjacencyList = new LinkedHashMap<>();

    public void addNode(T node) {
        adjacencyList.putIfAbsent(node, new ArrayList<>());
    }

    public void addEdge(T from, T to) {
        List<T> deps = adjacencyList.computeIfAbsent(from, k -> new ArrayList<>());
        if (!deps.contains(to)) {
            deps.add(to);
        }
        adjacencyList.putIfAbsent(to, new ArrayList<>());
    }

    public Map<T, List<T>> getAdjacencyList() {
        return Collections.unmodifiableMap(adjacencyList);
    }

    // Returns nodes grouped into parallel layers: all nodes in a layer are independent of
    // each other and can be processed simultaneously. Layers are ordered so that each
    // layer's dependencies are fully covered by earlier layers.
    public List<List<T>> topologicalLayers() {
        Map<T, Integer> inDegree = new HashMap<>();
        Map<T, List<T>> reversedAdj = new HashMap<>();

        for (Map.Entry<T, List<T>> entry : adjacencyList.entrySet()) {
            T node = entry.getKey();
            List<T> deps = entry.getValue();
            inDegree.put(node, deps.size());
            for (T dep : deps) {
                reversedAdj.computeIfAbsent(dep, k -> new ArrayList<>()).add(node);
            }
        }

        List<List<T>> layers = new ArrayList<>();
        List<T> currentLayer = new ArrayList<>();
        for (Map.Entry<T, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) currentLayer.add(entry.getKey());
        }

        int processed = 0;
        while (!currentLayer.isEmpty()) {
            layers.add(currentLayer);
            processed += currentLayer.size();
            List<T> nextLayer = new ArrayList<>();
            for (T node : currentLayer) {
                for (T dependent : reversedAdj.getOrDefault(node, Collections.emptyList())) {
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

    public List<T> topologicalSort() {
        return topologicalLayers().stream().flatMap(Collection::stream).toList();
    }
}
