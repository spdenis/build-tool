package com.example.multibuild.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Renders the graph as a PlantUML component diagram. Components are grouped into
// one package per build layer, drawn top to bottom (layer 1 first), one layer per row.
// An arrow from A to B means A depends on B.
public class PlantUmlGraphRenderer implements GraphRenderer {

    @Override
    public String render(GraphReport report) {
        Map<String, List<String>> deps = report.graph().getAdjacencyList();
        Map<String, String> alias = new LinkedHashMap<>();
        // Number components in build order so aliases read top to bottom.
        List<String> nodes = report.hasCycle() ? List.copyOf(deps.keySet()) : report.buildOrder();
        for (String node : nodes) {
            alias.put(node, "c" + (alias.size() + 1));
        }

        StringBuilder out = new StringBuilder();
        out.append("@startuml\n");
        out.append("title ").append(report.title()).append('\n');
        out.append("skinparam componentStyle rectangle\n");
        out.append("skinparam packageStyle frame\n");
        out.append("top to bottom direction\n\n");

        if (report.hasCycle()) {
            for (String node : deps.keySet()) {
                out.append(component(node, alias, report, "")).append('\n');
            }
        } else {
            List<List<String>> layers = report.layers();
            for (int i = 0; i < layers.size(); i++) {
                out.append("package \"Layer ").append(i + 1).append("\" {\n");
                for (String node : layers.get(i)) {
                    out.append(component(node, alias, report, "  ")).append('\n');
                }
                out.append("}\n");
            }
        }
        out.append('\n');

        // "dep <-- consumer" keeps the arrow pointing at the dependency but ranks the
        // dependency above its consumer, so earlier layers end up higher.
        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            for (String dep : e.getValue()) {
                out.append(alias.get(dep)).append(" <-- ").append(alias.get(e.getKey())).append('\n');
            }
        }

        if (!report.hasCycle()) {
            appendLayerLayout(out, report.layers(), alias);
        }

        out.append("\nlegend right\n");
        out.append("  A --> B : A depends on B\n");
        if (report.target() != null) {
            out.append("  highlighted : ").append(escape(report.target())).append('\n');
        }
        if (report.hasCycle()) {
            out.append("  <color:red>").append(escape(report.cycleError())).append("</color>\n");
        } else {
            out.append("  ==Build order==\n");
            List<String> order = report.buildOrder();
            for (int i = 0; i < order.size(); i++) {
                out.append("  ").append(i + 1).append(". ").append(escape(order.get(i))).append('\n');
            }
        }
        out.append("endlegend\n");
        out.append("@enduml\n");
        return out.toString();
    }

    // Hidden links that pin the layout: neighbours in a layer share a row, and each
    // layer sits directly below the previous one even when no edge connects them.
    private static void appendLayerLayout(StringBuilder out, List<List<String>> layers,
                                          Map<String, String> alias) {
        StringBuilder layout = new StringBuilder();
        for (int i = 0; i < layers.size(); i++) {
            List<String> layer = layers.get(i);
            for (int j = 1; j < layer.size(); j++) {
                layout.append(alias.get(layer.get(j - 1))).append(" -[hidden]right- ")
                        .append(alias.get(layer.get(j))).append('\n');
            }
            if (i > 0) {
                layout.append(alias.get(layers.get(i - 1).get(0))).append(" -[hidden]down-> ")
                        .append(alias.get(layer.get(0))).append('\n');
            }
        }
        if (!layout.isEmpty()) {
            out.append("\n' layout: one layer per row, top to bottom\n").append(layout);
        }
    }

    private static String component(String node, Map<String, String> alias, GraphReport report, String indent) {
        String line = indent + "component \"" + escape(node) + "\" as " + alias.get(node);
        return node.equals(report.target()) ? line + " #LightSkyBlue" : line;
    }

    private static String escape(String s) {
        return s.replace("\"", "'");
    }
}
