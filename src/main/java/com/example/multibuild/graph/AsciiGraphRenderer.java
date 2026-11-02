package com.example.multibuild.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AsciiGraphRenderer implements GraphRenderer {

    @Override
    public String render(GraphReport report) {
        DependencyGraph<String> graph = report.graph();
        Map<String, List<String>> deps = graph.getAdjacencyList();
        StringBuilder out = new StringBuilder();

        out.append("== ").append(report.title()).append(" ==\n");
        out.append(deps.size()).append(" component(s), ").append(graph.edgeCount())
                .append(" dependency edge(s)\n\n");

        out.append("-- Dependencies (component -> what it depends on) --\n");
        int width = deps.keySet().stream().mapToInt(n -> label(n, report).length()).max().orElse(0);
        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            String name = label(e.getKey(), report);
            out.append("  ").append(name);
            if (!e.getValue().isEmpty()) {
                out.append(" ".repeat(width - name.length())).append("  -> ")
                        .append(String.join(", ", e.getValue()));
            }
            out.append('\n');
        }
        out.append('\n');

        out.append("-- Dependency tree (dependents listed under what they depend on; ^ = shown above) --\n");
        renderTree(out, report);
        out.append('\n');

        if (report.hasCycle()) {
            out.append("-- Build order: not available --\n  ").append(report.cycleError()).append('\n');
            return out.toString();
        }

        List<List<String>> layers = report.layers();
        out.append("-- Build order: ").append(layers.size())
                .append(" layer(s); components in one layer can be built in parallel --\n");
        for (int i = 0; i < layers.size(); i++) {
            out.append("  Layer ").append(i + 1).append(": ")
                    .append(String.join(", ", layers.get(i).stream().map(n -> label(n, report)).toList()))
                    .append('\n');
        }
        out.append("\n  Sequential:\n");
        List<String> order = report.buildOrder();
        for (int i = 0; i < order.size(); i++) {
            out.append(String.format("  %3d. %s%n", i + 1, label(order.get(i), report)));
        }
        return out.toString();
    }

    private static String label(String node, GraphReport report) {
        return node.equals(report.target()) ? node + " *" : node;
    }

    private static void renderTree(StringBuilder out, GraphReport report) {
        DependencyGraph<String> graph = report.graph();
        Map<String, List<String>> dependents = graph.getDependentsList();
        List<String> roots = graph.getAdjacencyList().entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (roots.isEmpty()) roots = new ArrayList<>(graph.getAdjacencyList().keySet());

        Set<String> printed = new LinkedHashSet<>();
        for (String root : roots) {
            if (printed.contains(root)) continue;
            out.append("  ").append(label(root, report)).append('\n');
            printed.add(root);
            List<String> children = dependents.get(root);
            for (int i = 0; i < children.size(); i++) {
                renderNode(out, children.get(i), "", i == children.size() - 1, dependents, printed, report);
            }
        }
    }

    private static void renderNode(StringBuilder out, String node, String prefix, boolean last,
                                   Map<String, List<String>> dependents, Set<String> printed,
                                   GraphReport report) {
        out.append("  ").append(prefix).append(last ? "└── " : "├── ").append(label(node, report));
        if (!printed.add(node)) {
            out.append(" ^\n");
            return;
        }
        out.append('\n');
        List<String> children = dependents.get(node);
        String childPrefix = prefix + (last ? "    " : "│   ");
        for (int i = 0; i < children.size(); i++) {
            renderNode(out, children.get(i), childPrefix, i == children.size() - 1, dependents, printed, report);
        }
    }
}
