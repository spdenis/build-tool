package com.example.multibuild.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Renders the graph as a PlantUML component diagram. Components are grouped into
// one package per build layer; an arrow A --> B means A depends on B.
public class PlantUmlGraphRenderer implements GraphRenderer {

    @Override
    public String render(GraphReport report) {
        Map<String, List<String>> deps = report.graph().getAdjacencyList();
        Map<String, String> alias = new LinkedHashMap<>();
        for (String node : deps.keySet()) {
            alias.put(node, "c" + (alias.size() + 1));
        }

        StringBuilder out = new StringBuilder();
        out.append("@startuml\n");
        out.append("title ").append(report.title()).append('\n');
        out.append("skinparam componentStyle rectangle\n");
        out.append("skinparam packageStyle frame\n\n");

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

        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            for (String dep : e.getValue()) {
                out.append(alias.get(e.getKey())).append(" --> ").append(alias.get(dep)).append('\n');
            }
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

    private static String component(String node, Map<String, String> alias, GraphReport report, String indent) {
        String line = indent + "component \"" + escape(node) + "\" as " + alias.get(node);
        return node.equals(report.target()) ? line + " #LightSkyBlue" : line;
    }

    private static String escape(String s) {
        return s.replace("\"", "'");
    }
}
