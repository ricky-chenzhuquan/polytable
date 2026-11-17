package com.polytable.formula;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaDependencyGraphTest {

    private final FormulaParser parser = new FormulaParser();
    private final FormulaDependencyGraph graph = new FormulaDependencyGraph(parser);

    @Test
    void ordersNestedFormulaDependenciesBeforeTheirConsumers() {
        var order = graph.order(List.of(
                new FormulaDependencyGraph.FormulaDefinition("total", parser.parse("[subtotal] + [tax]")),
                new FormulaDependencyGraph.FormulaDefinition("tax", parser.parse("[subtotal] * 0.1")),
                new FormulaDependencyGraph.FormulaDefinition("subtotal", parser.parse("[unit] * [count]"))));

        assertEquals(List.of("subtotal", "tax", "total"), order.orderedFieldKeys());
        assertTrue(order.cyclicFieldKeys().isEmpty());
    }

    @Test
    void marksOnlyCycleMembersAndExcludesThemFromEvaluationOrder() {
        var order = graph.order(List.of(
                new FormulaDependencyGraph.FormulaDefinition("first", parser.parse("[second] + 1")),
                new FormulaDependencyGraph.FormulaDefinition("second", parser.parse("[first] + 1")),
                new FormulaDependencyGraph.FormulaDefinition("safe", parser.parse("[first] + 5"))));

        assertEquals(java.util.Set.of("first", "second"), order.cyclicFieldKeys());
        assertTrue(order.orderedFieldKeys().contains("safe"));
        assertTrue(!order.orderedFieldKeys().contains("first"));
    }

    @Test
    void ignoresRepeatedAndDeletedInputReferencesButDetectsSelfCycle() {
        var order = graph.order(List.of(
                new FormulaDependencyGraph.FormulaDefinition("subtotal", parser.parse("[input] + [input] + [deleted]")),
                new FormulaDependencyGraph.FormulaDefinition("self", parser.parse("[self] + 1"))));

        assertEquals(List.of("subtotal"), order.orderedFieldKeys());
        assertEquals(java.util.Set.of("self"), order.cyclicFieldKeys());
    }
}
