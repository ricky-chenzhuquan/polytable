package com.polytable.formula;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a deterministic evaluation order and identifies members of formula cycles. */
public final class FormulaDependencyGraph {

    private final FormulaParser parser;

    public FormulaDependencyGraph(FormulaParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    public EvaluationOrder order(List<FormulaDefinition> definitions) {
        Map<String, FormulaDefinition> byKey = new LinkedHashMap<>();
        for (FormulaDefinition definition : definitions) {
            if (byKey.putIfAbsent(definition.fieldKey(), definition) != null) {
                throw new IllegalArgumentException("duplicate formula field: " + definition.fieldKey());
            }
        }
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (FormulaDefinition definition : byKey.values()) {
            Set<String> refs = new LinkedHashSet<>();
            collectReferences(definition.expression(), refs);
            refs.retainAll(byKey.keySet()); // Deleted/non-formula fields are ordinary input values.
            dependencies.put(definition.fieldKey(), refs);
        }

        Tarjan tarjan = new Tarjan(dependencies);
        Set<String> cycles = tarjan.cyclicMembers();
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, List<String>> consumers = new HashMap<>();
        for (String key : byKey.keySet()) {
            if (cycles.contains(key)) continue;
            int count = 0;
            for (String dependency : dependencies.get(key)) {
                if (!cycles.contains(dependency)) {
                    count++;
                    consumers.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(key);
                }
            }
            remaining.put(key, count);
        }
        List<String> ready = new ArrayList<>();
        remaining.forEach((key, count) -> { if (count == 0) ready.add(key); });
        List<String> ordered = new ArrayList<>();
        for (int index = 0; index < ready.size(); index++) {
            String key = ready.get(index);
            ordered.add(key);
            for (String consumer : consumers.getOrDefault(key, List.of())) {
                int count = remaining.computeIfPresent(consumer, (ignored, value) -> value - 1);
                if (count == 0) ready.add(consumer);
            }
        }
        return new EvaluationOrder(ordered, cycles);
    }

    private void collectReferences(FormulaAst.Expression expression, Set<String> refs) {
        if (expression instanceof FormulaAst.FieldReference reference) {
            refs.add(reference.name());
        } else if (expression instanceof FormulaAst.Unary unary) {
            collectReferences(unary.operand(), refs);
        } else if (expression instanceof FormulaAst.Binary binary) {
            collectReferences(binary.left(), refs);
            collectReferences(binary.right(), refs);
        } else if (expression instanceof FormulaAst.Call call) {
            collectReferences(call.callee(), refs);
            call.arguments().forEach(argument -> collectReferences(argument, refs));
        } else if (expression instanceof FormulaAst.Member member) {
            collectReferences(member.target(), refs);
        }
    }

    public record FormulaDefinition(String fieldKey, FormulaAst.Expression expression) {
        public FormulaDefinition {
            if (fieldKey == null || fieldKey.isBlank()) throw new IllegalArgumentException("fieldKey cannot be blank");
            java.util.Objects.requireNonNull(expression, "expression");
        }
    }

    public record EvaluationOrder(List<String> orderedFieldKeys, Set<String> cyclicFieldKeys) {
        public EvaluationOrder {
            orderedFieldKeys = List.copyOf(orderedFieldKeys);
            cyclicFieldKeys = Collections.unmodifiableSet(new LinkedHashSet<>(cyclicFieldKeys));
        }
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> indexByNode = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final List<String> stack = new ArrayList<>();
        private final Set<String> onStack = new HashSet<>();
        private final Set<String> cycles = new LinkedHashSet<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) { this.graph = graph; }

        private Set<String> cyclicMembers() {
            graph.keySet().forEach(node -> { if (!indexByNode.containsKey(node)) visit(node); });
            return cycles;
        }

        private void visit(String node) {
            indexByNode.put(node, nextIndex);
            lowLink.put(node, nextIndex++);
            stack.add(node);
            onStack.add(node);
            for (String dependency : graph.getOrDefault(node, Set.of())) {
                if (!indexByNode.containsKey(dependency)) {
                    visit(dependency);
                    lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(dependency)));
                } else if (onStack.contains(dependency)) {
                    lowLink.put(node, Math.min(lowLink.get(node), indexByNode.get(dependency)));
                }
            }
            if (lowLink.get(node).equals(indexByNode.get(node))) {
                List<String> component = new ArrayList<>();
                String current;
                do {
                    current = stack.remove(stack.size() - 1);
                    onStack.remove(current);
                    component.add(current);
                } while (!current.equals(node));
                if (component.size() > 1 || graph.getOrDefault(node, Set.of()).contains(node)) cycles.addAll(component);
            }
        }
    }
}
