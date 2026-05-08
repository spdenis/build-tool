package com.example.multibuild.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
            // Find one cycle among the remaining (unprocessed) nodes via DFS
            Set<T> remaining = adjacencyList.keySet().stream()
                    .filter(n -> inDegree.getOrDefault(n, 0) > 0)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            throw new RuntimeException("Cycle detected in dependency graph: " + findCycle(remaining));
        }

        return layers;
    }

    // Returns a human-readable cycle path, e.g. "A → B → C → A"
    private String findCycle(Set<T> candidates) {
        Set<T> visited = new LinkedHashSet<>();
        Deque<T> stack = new ArrayDeque<>();

        for (T start : candidates) {
            if (visited.contains(start)) continue;
            List<T> cycle = dfs(start, candidates, visited, stack);
            if (cycle != null) {
                return cycle.stream().map(Object::toString).collect(Collectors.joining(" → "));
            }
        }
        return "(could not determine cycle path)";
    }

    private List<T> dfs(T node, Set<T> candidates, Set<T> visited, Deque<T> stack) {
        visited.add(node);
        stack.push(node);

        for (T dep : adjacencyList.getOrDefault(node, List.of())) {
            if (!candidates.contains(dep)) continue;
            if (stack.contains(dep)) {
                // Reconstruct the cycle from the stack up to and including dep
                List<T> cycle = new ArrayList<>();
                for (T n : stack) {
                    cycle.add(0, n);
                    if (n.equals(dep)) break;
                }
                cycle.add(dep);
                return cycle;
            }
            if (!visited.contains(dep)) {
                List<T> cycle = dfs(dep, candidates, visited, stack);
                if (cycle != null) return cycle;
            }
        }

        stack.pop();
        return null;
    }

    public List<T> topologicalSort() {
        return topologicalLayers().stream().flatMap(Collection::stream).toList();
    }
}
