package com.polytable.formula;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaEvaluatorGoldenTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FormulaParser parser = new FormulaParser();
    private final FormulaEvaluator evaluator = new FormulaEvaluator(new FormulaFunctionRegistry());

    @Test
    void evaluatesPublicGoldenExamples() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/formula/bitable-formula-golden.json")) {
            assertNotNull(stream);
            JsonNode cases = mapper.readTree(stream);
            for (JsonNode testCase : cases) {
                String id = testCase.path("id").asText();
                FormulaValue actual = evaluator.evaluate(
                        parser.parse(testCase.path("expression").asText()),
                        context(testCase.path("fields")));
                if (testCase.has("expectedError")) {
                    assertEquals(FormulaValue.Kind.ERROR, actual.kind(), id);
                    assertEquals(testCase.path("expectedError").asText(), actual.errorCode(), id);
                } else {
                    JsonNode expected = testCase.path("expectedValue");
                    assertEquals(expected.asText(), actual.asText(), id);
                }
            }
        }
    }

    @Test
    void arithmeticComparisonAndLogicalOperatorsReturnTypedValues() {
        FormulaValue result = evaluator.evaluate(
                parser.parse("(2 + 3 * 4 >= 14) && NOT(FALSE)"),
                FormulaEvaluationContext.empty());

        assertEquals(FormulaValue.Kind.BOOLEAN, result.kind());
        assertEquals(Boolean.TRUE, result.rawValue());
    }

    @Test
    void registryRecognizesEveryFunctionInTheFrozenPublicCatalog() throws Exception {
        JsonNode catalog = mapper.readTree(java.nio.file.Path.of(
                "src/main/resources/formula/bitable-formula-catalog.json").toFile());
        var registry = new FormulaFunctionRegistry();
        var missing = new java.util.ArrayList<String>();
        for (JsonNode function : catalog.path("functions")) {
            String name = function.path("name").asText();
            if (!registry.supports(name)) missing.add(name);
        }
        assertTrue(missing.isEmpty(), "functions without a registered implementation: " + missing);
    }

    @Test
    void evaluatesNumericTextAndListFunctions() {
        assertEquals(new BigDecimal("6"), evaluator.evaluate(parser.parse("SUM(LIST(1, 2, 3))"),
                FormulaEvaluationContext.empty()).asDecimal());
        assertEquals("多维", evaluator.evaluate(parser.parse("LEFT(\"多维表格\", 2)"),
                FormulaEvaluationContext.empty()).asText());
        assertEquals(new BigDecimal("3"), evaluator.evaluate(parser.parse("LIST(1, 3, 5, 7).NTH(2)"),
                FormulaEvaluationContext.empty()).asDecimal());
    }

    @Test
    void filtersRowsWithCurrentValueThenAggregatesTheResult() {
        FormulaValue result = evaluator.evaluate(parser.parse("FILTER(LIST(1, 2, 3, 4), CurrentValue > 2).SUM()"),
                FormulaEvaluationContext.empty());

        assertEquals(new BigDecimal("7"), result.asDecimal());
    }

    @Test
    void evaluatesDateAndTimeFunctionsAgainstTheInjectedClock() {
        FormulaEvaluationContext context = new FormulaEvaluationContext(Map.of(), Map.of(), ZoneId.of("Asia/Shanghai"),
                Clock.fixed(java.time.Instant.parse("2026-11-04T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        FormulaValue today = evaluator.evaluate(parser.parse("TODAY()"), context);
        assertEquals(java.time.LocalDate.of(2026, 11, 4), today.rawValue());
        assertEquals(new BigDecimal("7"), evaluate("DATEDIF(\"2000-01-01\", \"2000-01-08\", \"D\")").asDecimal());
        assertEquals("2000-02-29", evaluate("TEXT(EOMONTH(\"2000-01-01\", 1), \"YYYY-MM-DD\")").asText());
        assertEquals(java.time.LocalDateTime.of(2020, 1, 2, 0, 0),
                evaluate("DATE(2020, 1, 1) + DURATION(1)").rawValue());
        assertEquals(0, BigDecimal.ONE.compareTo(
                evaluate("DATE(2024, 1, 2) - DATE(2024, 1, 1)").asDecimal()));
    }

    @Test
    void evaluatesLetSwitchMapAndCountIf() {
        assertEquals(new BigDecimal("10"), evaluate("LET(rate, 2, rate * 5)").asDecimal());
        assertEquals(new BigDecimal("2"), evaluate("SWITCH(\"B\", \"A\", 1, \"B\", 2, 0)").asDecimal());
        FormulaValue mapped = evaluate("MAP(LIST(1, 2), CurrentValue * 2)");
        assertEquals(List.of(new BigDecimal("2"), new BigDecimal("4")),
                FormulaFunctionRegistry.castList(mapped.rawValue()).stream().map(FormulaValue::asDecimal).toList());
        assertEquals(new BigDecimal("2"), evaluate("COUNTIF(LIST(1, 2, 3), CurrentValue > 1)").asDecimal());
    }

    @Test
    void evaluatesPublicCrossTableFilterProjectionAndSumShapeInMemory() {
        Map<String, Object> rowOne = Map.of("销售额", 800);
        Map<String, Object> rowTwo = Map.of("销售额", 1200);
        Map<String, Object> rowThree = Map.of("销售额", 1400);
        Map<String, FormulaValue> fields = Map.of(
                "销售总表", FormulaValue.list(List.of(FormulaValue.recordReference(rowOne),
                        FormulaValue.recordReference(rowTwo), FormulaValue.recordReference(rowThree))),
                "销售人员", FormulaValue.text("甲"),
                "门槛", FormulaValue.decimal(new BigDecimal("1000")));
        FormulaEvaluationContext context = new FormulaEvaluationContext(fields, Map.of(), ZoneId.of("Asia/Shanghai"),
                Clock.fixed(java.time.Instant.parse("2026-11-04T00:00:00Z"), ZoneId.of("Asia/Shanghai")));

        FormulaValue result = evaluator.evaluate(parser.parse(
                "[销售总表].FILTER(CurrentValue.[销售额] > [门槛]).[销售额].SUM()"), context);

        assertEquals(new BigDecimal("2600"), result.asDecimal());
    }

    @Test
    void evaluatesTextFormattingAndErrorPredicates() {
        assertEquals("23.17", evaluate("TEXT(23.17, \"0.00\")").asText());
        assertEquals("123", evaluate("REGEXEXTRACT(\"abc123\", \"[0-9]+\")").asText());
        assertEquals("ABCBC", evaluate("SUBSTITUTE(\"ABCABC\", \"A\", \"\", 2)").asText());
        assertEquals("a-b-c", evaluate("ARRAYJOIN(SPLIT(\"a,b,c\", \",\"), \"-\")").asText());
        assertEquals("%E5%A4%9A%E7%BB%B4", evaluate("ENCODEURL(\"多维\")").asText());
        FormulaValue isError = evaluate("ISERROR(1 / 0)");
        assertEquals(FormulaValue.Kind.BOOLEAN, isError.kind());
        assertEquals(Boolean.TRUE, isError.rawValue());
    }

    @Test
    void containFunctionsMatchSubstringSemanticsUsedByExistingOperationsFormulas() {
        FormulaEvaluationContext context = new FormulaEvaluationContext(
                Map.of("value", FormulaValue.text("106.9w")), Map.of(), null, null);
        assertEquals(FormulaValue.bool(true), evaluator.evaluate(parser.parse("CONTAINS([value], \"w\")"), context));
        assertEquals(FormulaValue.bool(false), evaluator.evaluate(parser.parse("CONTAIN([value], \"106\")"), context));
        assertEquals(FormulaValue.bool(true), evaluator.evaluate(parser.parse("CONTAIN([value], \"106.9w\")"), context));
        assertEquals(FormulaValue.bool(true), evaluator.evaluate(parser.parse("CONTAINTEXT([value], \"106\")"), context));
    }

    @Test
    void evaluatesAggregateSortingLookupAndConditionalFunctions() {
        assertEquals(new BigDecimal("5"), evaluate("SUMIF(LIST(1, 2, 3), CurrentValue > 1)").asDecimal());
        assertEquals(new BigDecimal("3"), evaluate("LARGE(LIST(1, 3, 2), 1)").asDecimal());
        assertEquals(new BigDecimal("2.5"), evaluate("MEDIAN(LIST(1, 2, 3, 4))").asDecimal());
        assertEquals("found", evaluate("LOOKUP(2, LIST(1, 2), LIST(\"miss\", \"found\"))").asText());
        assertEquals("fallback", evaluate("IFBLANK(\"\", \"fallback\")").asText());
        assertEquals(new BigDecimal("3"), evaluate("SORT(LIST(3, 1, 2)).LAST()").asDecimal());
        FormulaValue sorted = evaluate("SORTBY(LIST(3, 1, 2), CurrentValue, TRUE)");
        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3")),
                FormulaFunctionRegistry.castList(sorted.rawValue()).stream().map(FormulaValue::asDecimal).toList());
    }

    @Test
    void evaluatesWorkdayAndGeographicFunctions() {
        assertEquals(java.time.LocalDate.of(2000, 1, 11), evaluate("WORKDAY(\"2000/01/01\", 7)").rawValue());
        assertEquals(new BigDecimal("8"), evaluate("NETWORKDAYS(\"2000-01-01\", \"2000-01-12\")").asDecimal());
        assertEquals(new BigDecimal("7"), evaluate("WEEKDAY(\"2000-01-01\")").asDecimal());
        FormulaValue distance = evaluator.evaluate(parser.parse("DISTANCE([A], [B])"),
                new FormulaEvaluationContext(Map.of(
                        "A", FormulaValue.recordReference(Map.of("latitude", 0, "longitude", 0)),
                        "B", FormulaValue.recordReference(Map.of("latitude", 0, "longitude", 1))), Map.of(),
                        ZoneId.of("Asia/Shanghai"), Clock.systemUTC()));
        assertEquals(new BigDecimal("111.195"), distance.asDecimal().setScale(3, java.math.RoundingMode.HALF_UP));
    }

    private FormulaValue evaluate(String expression) {
        return evaluator.evaluate(parser.parse(expression), FormulaEvaluationContext.empty());
    }

    private FormulaEvaluationContext context(JsonNode fieldNode) {
        Map<String, FormulaValue> fields = new HashMap<>();
        fieldNode.fields().forEachRemaining(entry -> fields.put(entry.getKey(), toValue(entry.getValue())));
        return new FormulaEvaluationContext(fields, Map.of(), ZoneId.of("Asia/Shanghai"),
                Clock.fixed(java.time.Instant.parse("2026-11-04T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    private FormulaValue toValue(JsonNode value) {
        if (value.isNull()) return FormulaValue.blank();
        if (value.isBoolean()) return FormulaValue.bool(value.asBoolean());
        if (value.isNumber()) return FormulaValue.decimal(new BigDecimal(value.asText()));
        return FormulaValue.text(value.asText());
    }
}
