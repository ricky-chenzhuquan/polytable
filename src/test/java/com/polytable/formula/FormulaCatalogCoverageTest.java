package com.polytable.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FormulaCatalogCoverageTest {

    @Test
    void parsesAndExecutesAnOfficialExampleForEveryCatalogFunction() throws Exception {
        JsonNode catalog = new ObjectMapper().readTree(Path.of(
                "src/main/resources/formula/bitable-formula-catalog.json").toFile());
        FormulaParser parser = new FormulaParser();
        FormulaEvaluator evaluator = new FormulaEvaluator(new FormulaFunctionRegistry());
        List<String> failures = new ArrayList<>();
        for (JsonNode function : catalog.path("functions")) {
            String name = function.path("name").asText();
            String sample = function.path("examples").path(0).asText();
            String call = firstCall(name, sample);
            if (call == null) {
                failures.add(name + ": official example has no extractable function call");
                continue;
            }
            try {
                FormulaAst.Expression ast = parser.parse(call);
                assertNotNull(evaluator.evaluate(ast, FormulaEvaluationContext.empty()));
            } catch (RuntimeException ex) {
                failures.add(name + ": " + ex.getMessage());
            }
        }
        assertEquals(List.of(), failures, String.join("\n", failures));
    }

    private static String firstCall(String function, String sample) {
        String upper = sample.toUpperCase(Locale.ROOT);
        String marker = function.toUpperCase(Locale.ROOT) + "(";
        int start = upper.indexOf(marker);
        if (start < 0) return null;
        int open = start + marker.length() - 1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = open; index < sample.length(); index++) {
            char current = sample.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') inString = true;
            else if (current == '(') depth++;
            else if ((current == ')' || current == '）') && --depth == 0) return sample.substring(start, index + 1);
        }
        return null;
    }
}
