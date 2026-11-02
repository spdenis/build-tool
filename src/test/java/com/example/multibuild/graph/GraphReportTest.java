package com.example.multibuild.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphReportTest {

    //   core <- api <- service <- app
    //   core <- util <- service
    //   other (independent)
    private static DependencyGraph<String> sample() {
        DependencyGraph<String> g = new DependencyGraph<>();
        g.addNode("core");
        g.addNode("util");
        g.addNode("api");
        g.addNode("service");
        g.addNode("app");
        g.addNode("other");
        g.addEdge("util", "core");
        g.addEdge("api", "core");
        g.addEdge("service", "api");
        g.addEdge("service", "util");
        g.addEdge("app", "service");
        return g;
    }

    @Test
    void full_containsAllComponentsInLayerOrder() {
        GraphReport report = GraphReport.full(sample());

        assertThat(report.layers()).containsExactly(
                List.of("core", "other"),
                List.of("util", "api"),
                List.of("service"),
                List.of("app"));
        assertThat(report.buildOrder()).containsExactly("core", "other", "util", "api", "service", "app");
    }

    @Test
    void downstream_keepsTargetAndConsumers() {
        GraphReport report = GraphReport.around(sample(), "util", AnalyzeScope.DOWNSTREAM);

        assertThat(report.buildOrder()).containsExactly("util", "service", "app");
        // edge service -> api is dropped because api is outside the subgraph
        assertThat(report.graph().getAdjacencyList().get("service")).containsExactly("util");
    }

    @Test
    void upstream_keepsTargetAndDependencies() {
        GraphReport report = GraphReport.around(sample(), "service", AnalyzeScope.UPSTREAM);

        assertThat(report.layers()).containsExactly(
                List.of("core"), List.of("util", "api"), List.of("service"));
    }

    @Test
    void both_keepsDependenciesAndConsumers() {
        GraphReport report = GraphReport.around(sample(), "api", AnalyzeScope.BOTH);

        assertThat(report.buildOrder()).containsExactly("core", "api", "service", "app");
    }

    @Test
    void unknownTarget_listsAvailableComponents() {
        assertThatThrownBy(() -> GraphReport.around(sample(), "nope", AnalyzeScope.BOTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("core, util");
    }

    @Test
    void cycle_reportedWithoutBuildOrder() {
        DependencyGraph<String> g = new DependencyGraph<>();
        g.addEdge("a", "b");
        g.addEdge("b", "a");

        GraphReport report = GraphReport.full(g);

        assertThat(report.hasCycle()).isTrue();
        assertThat(report.cycleError()).contains("Cycle detected");
        assertThat(new AsciiGraphRenderer().render(report)).contains("Build order: not available");
        assertThat(new PlantUmlGraphRenderer().render(report)).contains("c1 --> c2", "c2 --> c1");
    }

    @Test
    void ascii_showsDependenciesTreeAndBuildOrder() {
        String out = new AsciiGraphRenderer().render(GraphReport.around(sample(), "api", AnalyzeScope.BOTH));

        assertThat(out)
                .contains("Dependency graph: api (both)")
                .contains("4 component(s), 3 dependency edge(s)")
                .contains("  api *    -> core")
                .contains("  core\n  └── api *\n      └── service\n          └── app\n")
                .contains("Layer 2: api *")
                .contains("    1. core\n");
    }

    @Test
    void plantuml_groupsLayersAndHighlightsTarget() {
        String out = new PlantUmlGraphRenderer().render(GraphReport.around(sample(), "util", AnalyzeScope.DOWNSTREAM));

        assertThat(out)
                .startsWith("@startuml\n")
                .endsWith("@enduml\n")
                .contains("package \"Layer 1\" {\n  component \"util\" as c1 #LightSkyBlue\n}")
                .contains("c2 --> c1")
                .contains("  1. util\n  2. service\n  3. app\n");
    }

    @Test
    void formatAndScope_parseCaseInsensitively() {
        assertThat(GraphFormat.parse("PlantUML")).isEqualTo(GraphFormat.PLANTUML);
        assertThat(GraphFormat.parse("puml")).isEqualTo(GraphFormat.PLANTUML);
        assertThat(GraphFormat.parse("ascii")).isEqualTo(GraphFormat.ASCII);
        assertThat(AnalyzeScope.parse("Upstream")).isEqualTo(AnalyzeScope.UPSTREAM);
        assertThatThrownBy(() -> GraphFormat.parse("svg")).hasMessageContaining("ascii, plantuml");
    }
}
