package com.polytable.formula;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.LinkedHashMap;

public record FormulaEvaluationContext(
        Map<String, FormulaValue> fields,
        Map<String, FormulaValue> variables,
        ZoneId zoneId,
        Clock clock) {

    public FormulaEvaluationContext {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        zoneId = zoneId == null ? ZoneId.of("Asia/Shanghai") : zoneId;
        clock = clock == null ? Clock.system(zoneId) : clock;
    }

    public static FormulaEvaluationContext empty() {
        return new FormulaEvaluationContext(Map.of(), Map.of(), ZoneId.of("Asia/Shanghai"),
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public FormulaEvaluationContext withVariable(String name, FormulaValue value) {
        Map<String, FormulaValue> next = new LinkedHashMap<>(variables);
        next.put(name, value);
        return new FormulaEvaluationContext(fields, next, zoneId, clock);
    }
}
