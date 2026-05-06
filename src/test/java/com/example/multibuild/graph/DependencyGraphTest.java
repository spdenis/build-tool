package com.example.multibuild.graph;

import com.example.multibuild.model.Artifact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {

    @Test
    void topologicalSortRespectsOrder() {
        DependencyGraph<Artifact> graph = new DependencyGraph<>();
        Artifact a = new Artifact("com.example", "a", "1.0");
        Artifact b = new Artifact("com.example", "b", "1.0");
        Artifact c = new Artifact("com.example", "c", "1.0");

        graph.addNode(a);
        graph.addNode(b);
        graph.addNode(c);
        graph.addEdge(b, a); // b depends on a → a built first
        graph.addEdge(c, a); // c depends on a
        graph.addEdge(c, b); // c depends on b → b built before c

        List<Artifact> order = graph.topologicalSort();

        assertEquals(3, order.size());
        assertTrue(order.indexOf(a) < order.indexOf(b), "a must come before b");
        assertTrue(order.indexOf(a) < order.indexOf(c), "a must come before c");
        assertTrue(order.indexOf(b) < order.indexOf(c), "b must come before c");
    }

    @Test
    void independentArtifactsAreAllIncluded() {
        DependencyGraph<Artifact> graph = new DependencyGraph<>();
        Artifact a = new Artifact("com.example", "a", "1.0");
        Artifact b = new Artifact("com.example", "b", "1.0");
        graph.addNode(a);
        graph.addNode(b);

        List<Artifact> order = graph.topologicalSort();
        assertEquals(2, order.size());
        assertTrue(order.contains(a));
        assertTrue(order.contains(b));
    }

    @Test
    void cycleDetectionThrows() {
        DependencyGraph<Artifact> graph = new DependencyGraph<>();
        Artifact a = new Artifact("com.example", "a", "1.0");
        Artifact b = new Artifact("com.example", "b", "1.0");
        graph.addNode(a);
        graph.addNode(b);
        graph.addEdge(a, b);
        graph.addEdge(b, a);

        assertThrows(RuntimeException.class, graph::topologicalSort);
    }

    @Test
    void topologicalLayersGroupsIndependentArtifacts() {
        DependencyGraph<Artifact> graph = new DependencyGraph<>();
        Artifact a = new Artifact("com.example", "a", "1.0");
        Artifact b = new Artifact("com.example", "b", "1.0");
        Artifact c = new Artifact("com.example", "c", "1.0");
        Artifact d = new Artifact("com.example", "d", "1.0");

        graph.addNode(a);
        graph.addNode(b);
        graph.addNode(c);
        graph.addNode(d);
        graph.addEdge(c, a); // c depends on a
        graph.addEdge(d, b); // d depends on b
        // a and b are independent → layer 0; c and d are independent → layer 1

        List<List<Artifact>> layers = graph.topologicalLayers();

        assertEquals(2, layers.size(), "expected 2 layers");
        assertTrue(layers.get(0).contains(a) && layers.get(0).contains(b),
                "layer 0 must contain both independent roots a and b");
        assertTrue(layers.get(1).contains(c) && layers.get(1).contains(d),
                "layer 1 must contain both dependents c and d");
    }

    @Test
    void duplicateEdgeIgnored() {
        DependencyGraph<Artifact> graph = new DependencyGraph<>();
        Artifact a = new Artifact("com.example", "a", "1.0");
        Artifact b = new Artifact("com.example", "b", "1.0");
        graph.addNode(a);
        graph.addNode(b);
        graph.addEdge(b, a);
        graph.addEdge(b, a); // duplicate

        List<Artifact> order = graph.topologicalSort();
        assertEquals(2, order.size());
        assertTrue(order.indexOf(a) < order.indexOf(b));
    }
}
