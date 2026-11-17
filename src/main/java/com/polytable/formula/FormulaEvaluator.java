package com.polytable.formula;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FormulaEvaluator {

    private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL128;
    private static final int MAX_EVALUATION_DEPTH = 256;
    private final FormulaFunctionRegistry functions;

    public FormulaEvaluator(FormulaFunctionRegistry functions) {
        this.functions = functions;
    }

    public FormulaValue evaluate(FormulaAst.Expression expression, FormulaEvaluationContext context) {
        return evaluate(expression, context == null ? FormulaEvaluationContext.empty() : context, 0);
    }

    private FormulaValue evaluate(FormulaAst.Expression expression, FormulaEvaluationContext context, int depth) {
        if (depth > MAX_EVALUATION_DEPTH) return FormulaValue.error("#NUM!", "formula evaluation depth exceeded");
        if (expression instanceof FormulaAst.Literal literal) return literal.value();
        if (expression instanceof FormulaAst.FieldReference field) {
            return context.fields().getOrDefault(field.name(), FormulaValue.error("#REF!", "unknown field: " + field.name()));
        }
        if (expression instanceof FormulaAst.Name name) {
            return lookup(context.variables(), name.identifier());
        }
        if (expression instanceof FormulaAst.Unary unary) {
            FormulaValue value = evaluate(unary.operand(), context, depth + 1);
            if (value.kind() == FormulaValue.Kind.ERROR) return value;
            if (unary.operator().equals("+")) return FormulaFunctionRegistry.parseNumber(value);
            FormulaValue number = FormulaFunctionRegistry.parseNumber(value);
            return number.kind() == FormulaValue.Kind.ERROR ? number : FormulaValue.decimal(number.asDecimal().negate());
        }
        if (expression instanceof FormulaAst.Binary binary) return evaluateBinary(binary, context, depth);
        if (expression instanceof FormulaAst.Call call) return evaluateCall(call, context, depth);
        if (expression instanceof FormulaAst.Member member) return evaluateMember(member, context, depth);
        return FormulaValue.error("#VALUE!", "unsupported expression node");
    }

    private FormulaValue evaluateBinary(FormulaAst.Binary binary, FormulaEvaluationContext context, int depth) {
        FormulaValue left = evaluate(binary.left(), context, depth + 1);
        if (left.kind() == FormulaValue.Kind.ERROR) return left;
        if (binary.operator().equals("&&") && !FormulaFunctionRegistry.truthy(left)) return FormulaValue.bool(false);
        if (binary.operator().equals("||") && FormulaFunctionRegistry.truthy(left)) return FormulaValue.bool(true);
        FormulaValue right = evaluate(binary.right(), context, depth + 1);
        if (right.kind() == FormulaValue.Kind.ERROR) return right;
        return switch (binary.operator()) {
            case "&&" -> FormulaValue.bool(FormulaFunctionRegistry.truthy(right));
            case "||" -> FormulaValue.bool(FormulaFunctionRegistry.truthy(right));
            case "&" -> FormulaValue.text(FormulaFunctionRegistry.toText(left) + FormulaFunctionRegistry.toText(right));
            case "=" -> FormulaValue.bool(compare(left, right) == 0);
            case "!=" -> FormulaValue.bool(compare(left, right) != 0);
            case ">" -> FormulaValue.bool(compare(left, right) > 0);
            case ">=" -> FormulaValue.bool(compare(left, right) >= 0);
            case "<" -> FormulaValue.bool(compare(left, right) < 0);
            case "<=" -> FormulaValue.bool(compare(left, right) <= 0);
            default -> arithmetic(binary.operator(), left, right);
        };
    }

    private FormulaValue evaluateCall(FormulaAst.Call call, FormulaEvaluationContext context, int depth) {
        if (call.callee() instanceof FormulaAst.Member member) {
            FormulaValue target = evaluate(member.target(), context, depth + 1);
            if (target.kind() == FormulaValue.Kind.ERROR) return target;
            return evaluateMethod(member.member(), target, call.arguments(), context, depth + 1);
        }
        if (call.callee() instanceof FormulaAst.Name name) {
            String functionName = name.identifier().toUpperCase(Locale.ROOT);
            if (functionName.equals("IF")) return evaluateIf(call.arguments(), context, depth);
            if (functionName.equals("IFS")) return evaluateIfs(call.arguments(), context, depth);
            if (functionName.equals("IFERROR")) return evaluateIfError(call.arguments(), context, depth);
            if (functionName.equals("LET")) return evaluateLet(call.arguments(), context, depth);
            if (functionName.equals("FILTER")) return filter(call.arguments(), null, context, depth);
            if (functionName.equals("MAP")) return map(call.arguments(), context, depth);
            if (functionName.equals("COUNTIF")) return countIf(call.arguments(), context, depth, null);
            if (functionName.equals("SUMIF")) return sumIf(call.arguments(), context, depth, null);
            if (functionName.equals("SORTBY")) {
                if (call.arguments().size() < 2) return FormulaValue.error("#VALUE!", "SORTBY expects range and sort key");
                FormulaValue range = evaluate(call.arguments().get(0), context, depth + 1);
                return sortBy(range, call.arguments().subList(1, call.arguments().size()), context, depth + 1);
            }
            FormulaFunctionRegistry.FormulaFunction function = functions.resolve(functionName);
            if (function == null) return FormulaValue.error("#NAME?", "unknown function: " + name.identifier());
            List<FormulaValue> args = evaluateArguments(call.arguments(), context, depth);
            FormulaValue error = functionName.equals("ISERROR") ? null : firstError(args);
            return error == null ? function.apply(args, context) : error;
        }
        return FormulaValue.error("#VALUE!", "method calls require a record or list source");
    }

    private FormulaValue evaluateMethod(String method, FormulaValue target, List<FormulaAst.Expression> expressions,
                                        FormulaEvaluationContext context, int depth) {
        String name = method.toUpperCase(Locale.ROOT);
        if (name.equals("FILTER")) return filter(expressions, target, context, depth);
        if (name.equals("MAP")) return mapWithTarget(target, expressions, context, depth);
        if (name.equals("COUNTIF")) return countIf(expressions, context, depth, target);
        if (name.equals("SUMIF")) return sumIf(expressions, context, depth, target);
        if (name.equals("SORTBY")) return sortBy(target, expressions, context, depth);
        List<FormulaValue> args = new ArrayList<>();
        args.add(target);
        args.addAll(evaluateArguments(expressions, context, depth));
        FormulaValue error = args.stream().filter(v -> v.kind() == FormulaValue.Kind.ERROR).findFirst().orElse(null);
        if (error != null) return error;
        FormulaFunctionRegistry.FormulaFunction function = functions.resolve(name);
        return function == null ? FormulaValue.error("#NAME?", "unknown method: " + method) : function.apply(args, context);
    }

    private FormulaValue filter(List<FormulaAst.Expression> expressions, FormulaValue suppliedRange,
                                FormulaEvaluationContext context, int depth) {
        FormulaValue range = suppliedRange;
        FormulaAst.Expression predicate;
        if (range == null) {
            if (expressions.size() != 2) return FormulaValue.error("#VALUE!", "FILTER expects range and condition");
            range = evaluate(expressions.get(0), context, depth + 1);
            predicate = expressions.get(1);
        } else {
            if (expressions.size() != 1) return FormulaValue.error("#VALUE!", "FILTER method expects one condition");
            predicate = expressions.get(0);
        }
        if (range.kind() == FormulaValue.Kind.ERROR) return range;
        List<FormulaValue> result = new ArrayList<>();
        for (FormulaValue item : asList(range)) {
            FormulaValue test = evaluate(predicate, context.withVariable("CurrentValue", item), depth + 1);
            if (test.kind() == FormulaValue.Kind.ERROR) return test;
            if (FormulaFunctionRegistry.truthy(test)) result.add(item);
        }
        return FormulaValue.list(result);
    }

    private FormulaValue map(List<FormulaAst.Expression> expressions, FormulaEvaluationContext context, int depth) {
        if (expressions.size() != 2) return FormulaValue.error("#VALUE!", "MAP expects range and mapping expression");
        FormulaValue range = evaluate(expressions.get(0), context, depth + 1);
        return mapWithTarget(range, expressions.subList(1, 2), context, depth);
    }

    private FormulaValue mapWithTarget(FormulaValue range, List<FormulaAst.Expression> expressions,
                                       FormulaEvaluationContext context, int depth) {
        if (expressions.size() != 1) return FormulaValue.error("#VALUE!", "MAP expects one mapping expression");
        List<FormulaValue> result = new ArrayList<>();
        for (FormulaValue item : asList(range)) {
            FormulaValue mapped = evaluate(expressions.get(0), context.withVariable("CurrentValue", item), depth + 1);
            if (mapped.kind() == FormulaValue.Kind.ERROR) return mapped;
            result.add(mapped);
        }
        return FormulaValue.list(result);
    }

    private FormulaValue countIf(List<FormulaAst.Expression> expressions, FormulaEvaluationContext context,
                                 int depth, FormulaValue suppliedRange) {
        FormulaValue range = suppliedRange;
        FormulaAst.Expression condition;
        if (range == null) {
            if (expressions.size() != 2) return FormulaValue.error("#VALUE!", "COUNTIF expects range and condition");
            range = evaluate(expressions.get(0), context, depth + 1);
            condition = expressions.get(1);
        } else {
            if (expressions.size() != 1) return FormulaValue.error("#VALUE!", "COUNTIF method expects one condition");
            condition = expressions.get(0);
        }
        if (range.kind() == FormulaValue.Kind.ERROR) return range;
        long matches = 0;
        for (FormulaValue item : asList(range)) {
            FormulaValue result = evaluate(condition, context.withVariable("CurrentValue", item), depth + 1);
            if (result.kind() == FormulaValue.Kind.ERROR) return result;
            if (FormulaFunctionRegistry.truthy(result)) matches++;
        }
        return FormulaValue.decimal(BigDecimal.valueOf(matches));
    }

    private FormulaValue sumIf(List<FormulaAst.Expression> expressions, FormulaEvaluationContext context,
                               int depth, FormulaValue suppliedRange) {
        FormulaValue range = suppliedRange;
        FormulaAst.Expression condition;
        if (range == null) {
            if (expressions.size() != 2) return FormulaValue.error("#VALUE!", "SUMIF expects range and condition");
            range = evaluate(expressions.get(0), context, depth + 1);
            condition = expressions.get(1);
        } else {
            if (expressions.size() != 1) return FormulaValue.error("#VALUE!", "SUMIF method expects one condition");
            condition = expressions.get(0);
        }
        if (range.kind() == FormulaValue.Kind.ERROR) return range;
        BigDecimal sum = BigDecimal.ZERO;
        for (FormulaValue item : asList(range)) {
            FormulaValue result = evaluate(condition, context.withVariable("CurrentValue", item), depth + 1);
            if (result.kind() == FormulaValue.Kind.ERROR) return result;
            if (FormulaFunctionRegistry.truthy(result)) {
                FormulaValue number = FormulaFunctionRegistry.parseNumber(item);
                if (number.kind() == FormulaValue.Kind.ERROR) return number;
                sum = sum.add(number.asDecimal());
            }
        }
        return FormulaValue.decimal(sum);
    }

    private FormulaValue evaluateLet(List<FormulaAst.Expression> expressions, FormulaEvaluationContext context, int depth) {
        if (expressions.size() < 3 || expressions.size() % 2 == 0) return FormulaValue.error("#VALUE!", "LET expects variable/value pairs and a result");
        FormulaEvaluationContext current = context;
        for (int i = 0; i < expressions.size() - 1; i += 2) {
            if (!(expressions.get(i) instanceof FormulaAst.Name name)) return FormulaValue.error("#NAME?", "LET variable must be a name");
            FormulaValue value = evaluate(expressions.get(i + 1), current, depth + 1);
            if (value.kind() == FormulaValue.Kind.ERROR) return value;
            current = current.withVariable(name.identifier(), value);
        }
        return evaluate(expressions.get(expressions.size() - 1), current, depth + 1);
    }

    private FormulaValue sortBy(FormulaValue target, List<FormulaAst.Expression> expressions,
                                FormulaEvaluationContext context, int depth) {
        if (expressions.isEmpty()) return FormulaValue.error("#VALUE!", "SORTBY needs a sort key");
        List<FormulaValue> rows = asList(target);
        boolean ascending = expressions.size() < 2 || FormulaFunctionRegistry.truthy(evaluate(expressions.get(1), context, depth + 1));
        List<SortEntry> entries = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            FormulaValue key = evaluate(expressions.get(0), context.withVariable("CurrentValue", rows.get(i)), depth + 1);
            if (key.kind() == FormulaValue.Kind.ERROR) return key;
            entries.add(new SortEntry(rows.get(i), key, i));
        }
        entries.sort((left, right) -> {
            int compare = compare(left.key(), right.key());
            if (!ascending) compare = -compare;
            return compare == 0 ? Integer.compare(left.originalIndex(), right.originalIndex()) : compare;
        });
        return FormulaValue.list(entries.stream().map(SortEntry::value).toList());
    }

    private record SortEntry(FormulaValue value, FormulaValue key, int originalIndex) {
    }

    private FormulaValue evaluateIf(List<FormulaAst.Expression> args, FormulaEvaluationContext context, int depth) {
        if (args.size() < 2 || args.size() > 3) return FormulaValue.error("#VALUE!", "IF expects 2 or 3 arguments");
        FormulaValue condition = evaluate(args.get(0), context, depth + 1);
        if (condition.kind() == FormulaValue.Kind.ERROR) return condition;
        if (FormulaFunctionRegistry.truthy(condition)) return evaluate(args.get(1), context, depth + 1);
        return args.size() == 3 ? evaluate(args.get(2), context, depth + 1) : FormulaValue.blank();
    }

    private FormulaValue evaluateIfs(List<FormulaAst.Expression> args, FormulaEvaluationContext context, int depth) {
        if (args.isEmpty() || args.size() % 2 != 0) return FormulaValue.error("#VALUE!", "IFS expects condition/value pairs");
        for (int i = 0; i < args.size(); i += 2) {
            FormulaValue condition = evaluate(args.get(i), context, depth + 1);
            if (condition.kind() == FormulaValue.Kind.ERROR) return condition;
            if (FormulaFunctionRegistry.truthy(condition)) return evaluate(args.get(i + 1), context, depth + 1);
        }
        return FormulaValue.error("#N/A", "no IFS condition matched");
    }

    private FormulaValue evaluateIfError(List<FormulaAst.Expression> args, FormulaEvaluationContext context, int depth) {
        if (args.size() != 2) return FormulaValue.error("#VALUE!", "IFERROR expects 2 arguments");
        FormulaValue first = evaluate(args.get(0), context, depth + 1);
        return first.kind() == FormulaValue.Kind.ERROR ? evaluate(args.get(1), context, depth + 1) : first;
    }

    private FormulaValue evaluateMember(FormulaAst.Member member, FormulaEvaluationContext context, int depth) {
        FormulaValue target = evaluate(member.target(), context, depth + 1);
        if (target.kind() == FormulaValue.Kind.ERROR) return target;
        if (target.kind() == FormulaValue.Kind.LIST) {
            List<FormulaValue> projected = new ArrayList<>();
            for (FormulaValue item : asList(target)) {
                if (!(item.rawValue() instanceof Map<?, ?> map)) {
                    return FormulaValue.error("#REF!", "list item has no member: " + member.member());
                }
                Object value = map.get(member.member());
                if (value == null) return FormulaValue.error("#REF!", "unknown member: " + member.member());
                projected.add(fromObject(value));
            }
            return FormulaValue.list(projected);
        }
        if (target.rawValue() instanceof Map<?, ?> map) {
            Object value = map.get(member.member());
            return value == null ? FormulaValue.error("#REF!", "unknown member: " + member.member()) : fromObject(value);
        }
        return FormulaValue.error("#REF!", "unknown member: " + member.member());
    }

    private List<FormulaValue> evaluateArguments(List<FormulaAst.Expression> expressions,
                                                  FormulaEvaluationContext context, int depth) {
        List<FormulaValue> values = new ArrayList<>(expressions.size());
        for (FormulaAst.Expression expression : expressions) values.add(evaluate(expression, context, depth + 1));
        return List.copyOf(values);
    }

    private static FormulaValue arithmetic(String operator, FormulaValue left, FormulaValue right) {
        if (left.kind() == FormulaValue.Kind.DATE_TIME || right.kind() == FormulaValue.Kind.DATE_TIME) {
            FormulaValue dateResult = dateArithmetic(operator, left, right);
            if (dateResult != null) return dateResult;
        }
        FormulaValue l = FormulaFunctionRegistry.parseNumber(left);
        FormulaValue r = FormulaFunctionRegistry.parseNumber(right);
        if (l.kind() == FormulaValue.Kind.ERROR) return l;
        if (r.kind() == FormulaValue.Kind.ERROR) return r;
        BigDecimal a = l.asDecimal();
        BigDecimal b = r.asDecimal();
        try {
            return FormulaValue.decimal(switch (operator) {
                case "+" -> a.add(b);
                case "-" -> a.subtract(b);
                case "*" -> a.multiply(b);
                case "/" -> a.divide(b, DIVISION_CONTEXT);
                case "%" -> a.remainder(b);
                default -> throw new IllegalArgumentException("unknown arithmetic operator: " + operator);
            });
        } catch (ArithmeticException ex) {
            return FormulaValue.error("#DIV/0!", "invalid division or remainder");
        }
    }

    private static FormulaValue dateArithmetic(String operator, FormulaValue left, FormulaValue right) {
        if (left.kind() == FormulaValue.Kind.DATE_TIME && right.kind() == FormulaValue.Kind.DATE_TIME
                && operator.equals("-")) {
            try {
                Duration difference = Duration.between(asDateTime(right), asDateTime(left));
                BigDecimal seconds = BigDecimal.valueOf(difference.getSeconds())
                        .add(BigDecimal.valueOf(difference.getNano(), 9));
                return FormulaValue.decimal(seconds.divide(BigDecimal.valueOf(86_400), MathContext.DECIMAL128));
            } catch (RuntimeException ex) {
                return FormulaValue.error("#VALUE!", "invalid date subtraction");
            }
        }
        if (left.kind() != FormulaValue.Kind.DATE_TIME || (!operator.equals("+") && !operator.equals("-"))) return null;
        try {
            LocalDateTime date = asDateTime(left);
            Duration amount;
            if (right.kind() == FormulaValue.Kind.DATE_TIME && right.rawValue() instanceof Duration duration) {
                amount = duration;
            } else {
                FormulaValue days = FormulaFunctionRegistry.parseNumber(right);
                if (days.kind() == FormulaValue.Kind.ERROR) return days;
                BigDecimal seconds = days.asDecimal().multiply(BigDecimal.valueOf(86_400));
                amount = Duration.ofNanos(seconds.multiply(BigDecimal.valueOf(1_000_000_000L)).longValue());
            }
            return FormulaValue.dateTime(operator.equals("+") ? date.plus(amount) : date.minus(amount));
        } catch (RuntimeException ex) {
            return FormulaValue.error("#VALUE!", "invalid date arithmetic");
        }
    }

    private static LocalDateTime asDateTime(FormulaValue value) {
        Object raw = value.rawValue();
        if (raw instanceof LocalDateTime dateTime) return dateTime;
        if (raw instanceof LocalDate date) return date.atStartOfDay();
        if (raw instanceof java.time.Instant instant) return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
        throw new IllegalArgumentException("unsupported date value");
    }

    private static int compare(FormulaValue left, FormulaValue right) {
        if (left.kind() == FormulaValue.Kind.DECIMAL && right.kind() == FormulaValue.Kind.DECIMAL) {
            return left.asDecimal().compareTo(right.asDecimal());
        }
        if (left.kind() == FormulaValue.Kind.BOOLEAN && right.kind() == FormulaValue.Kind.BOOLEAN) {
            return Boolean.compare((Boolean) left.rawValue(), (Boolean) right.rawValue());
        }
        return FormulaFunctionRegistry.toText(left).compareTo(FormulaFunctionRegistry.toText(right));
    }

    private static FormulaValue lookup(Map<String, FormulaValue> values, String key) {
        FormulaValue direct = values.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, FormulaValue> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return FormulaValue.error("#NAME?", "unknown name: " + key);
    }

    private static FormulaValue firstError(List<FormulaValue> values) {
        return values.stream().filter(v -> v.kind() == FormulaValue.Kind.ERROR).findFirst().orElse(null);
    }

    private static FormulaValue fromObject(Object value) {
        if (value == null) return FormulaValue.blank();
        if (value instanceof FormulaValue formulaValue) return formulaValue;
        if (value instanceof Boolean bool) return FormulaValue.bool(bool);
        if (value instanceof Number number) return FormulaValue.decimal(new BigDecimal(number.toString()));
        if (value instanceof List<?> list) return FormulaValue.list(list.stream().map(FormulaEvaluator::fromObject).toList());
        if (value instanceof Map<?, ?> map) return FormulaValue.recordReference(map);
        return FormulaValue.text(String.valueOf(value));
    }

    private static List<FormulaValue> asList(FormulaValue value) {
        return value.kind() == FormulaValue.Kind.LIST ? FormulaFunctionRegistry.castList(value.rawValue()) : List.of(value);
    }
}
