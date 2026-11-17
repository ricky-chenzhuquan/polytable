package com.polytable.formula;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FormulaFunctionRegistry {

    @FunctionalInterface
    public interface FormulaFunction {
        FormulaValue apply(List<FormulaValue> arguments, FormulaEvaluationContext context);
    }

    private final Map<String, FormulaFunction> functions = new HashMap<>();

    public FormulaFunctionRegistry() {
        registerConstantsAndCoreLogic();
        registerTextFunctions();
        registerNumericFunctions();
        registerDateFunctions();
        registerListFunctions();
    }

    private void registerConstantsAndCoreLogic() {
        register("TRUE", (args, ctx) -> FormulaValue.bool(true));
        register("FALSE", (args, ctx) -> FormulaValue.bool(false));
        register("NOT", (args, ctx) -> FormulaValue.bool(!truthy(args.get(0))));
        register("AND", (args, ctx) -> FormulaValue.bool(args.stream().allMatch(FormulaFunctionRegistry::truthy)));
        register("OR", (args, ctx) -> FormulaValue.bool(args.stream().anyMatch(FormulaFunctionRegistry::truthy)));
        register("ISBLANK", (args, ctx) -> FormulaValue.bool(isBlank(args.get(0))));
        register("ISNULL", (args, ctx) -> FormulaValue.bool(args.get(0).kind() == FormulaValue.Kind.BLANK));
        register("ISERROR", (args, ctx) -> FormulaValue.bool(args.get(0).kind() == FormulaValue.Kind.ERROR));
        register("ISNUMBER", (args, ctx) -> FormulaValue.bool(args.get(0).kind() == FormulaValue.Kind.DECIMAL));
        register("IFBLANK", (args, ctx) -> isBlank(args.get(0)) ? args.get(1) : args.get(0));
        register("CONCATENATE", (args, ctx) -> FormulaValue.text(join(args, "")));
        register("CONTAIN", (args, ctx) -> FormulaValue.bool(contains(args)));
        register("CONTAINS", (args, ctx) -> FormulaValue.bool(containsText(args)));
        register("CONTAINTEXT", (args, ctx) -> FormulaValue.bool(toText(args.get(0)).toLowerCase(Locale.ROOT)
                .contains(toText(args.get(1)).toLowerCase(Locale.ROOT))));
        register("SUBSTITUTE", FormulaFunctionRegistry::substitute);
        register("VALUE", (args, ctx) -> parseNumber(args.get(0)));
        register("IF", (args, ctx) -> args.size() > 1
                ? (truthy(args.get(0)) ? args.get(1) : args.size() > 2 ? args.get(2) : FormulaValue.blank())
                : FormulaValue.error("#VALUE!", "IF expects at least 2 arguments"));
        register("IFS", (args, ctx) -> {
            for (int i = 0; i + 1 < args.size(); i += 2) if (truthy(args.get(i))) return args.get(i + 1);
            return FormulaValue.error("#N/A", "no IFS condition matched");
        });
        register("IFERROR", (args, ctx) -> args.size() > 1 && args.get(0).kind() == FormulaValue.Kind.ERROR
                ? args.get(1) : args.isEmpty() ? FormulaValue.error("#VALUE!", "IFERROR expects arguments") : args.get(0));
        register("ISODD", (args, ctx) -> {
            FormulaValue number = parseNumber(args.get(0));
            return number.kind() == FormulaValue.Kind.ERROR ? number
                    : FormulaValue.bool(number.asDecimal().remainder(BigDecimal.valueOf(2)).abs().compareTo(BigDecimal.ONE) == 0);
        });
        register("CONTAINSALL", (args, ctx) -> FormulaValue.bool(containsAll(args)));
        register("CONTAINSONLY", (args, ctx) -> FormulaValue.bool(containsOnly(args)));
        register("RANDOMBETWEEN", (args, ctx) -> randomBetween(args));
        register("RANDOMITEM", (args, ctx) -> randomItem(args));
        register("SWITCH", (args, ctx) -> switchValue(args));
        register("LET", (args, ctx) -> FormulaValue.error("#VALUE!", "LET is evaluated with lexical bindings"));
    }

    private void registerTextFunctions() {
        register("CHAR", (args, ctx) -> mapNumber(args, 0, n -> {
            int codePoint = n.intValue();
            return Character.isValidCodePoint(codePoint) ? FormulaValue.text(new String(Character.toChars(codePoint)))
                    : FormulaValue.error("#VALUE!", "invalid character code");
        }));
        register("FIND", (args, ctx) -> find(args));
        register("HYPERLINK", (args, ctx) -> FormulaValue.text(args.size() > 1 ? toText(args.get(1)) : toText(args.get(0))));
        register("LEFT", (args, ctx) -> sliceText(args, true));
        register("RIGHT", (args, ctx) -> sliceText(args, false));
        register("LEN", (args, ctx) -> FormulaValue.decimal(BigDecimal.valueOf(toText(args.get(0)).codePointCount(0, toText(args.get(0)).length()))));
        register("LOWER", (args, ctx) -> FormulaValue.text(toText(args.get(0)).toLowerCase(Locale.ROOT)));
        register("UPPER", (args, ctx) -> FormulaValue.text(toText(args.get(0)).toUpperCase(Locale.ROOT)));
        register("TRIM", (args, ctx) -> FormulaValue.text(toText(args.get(0)).trim().replaceAll("\\s+", " ")));
        register("MID", (args, ctx) -> mid(args));
        register("REPLACE", (args, ctx) -> replaceText(args));
        register("SPLIT", (args, ctx) -> split(args));
        register("ENCODEURL", (args, ctx) -> FormulaValue.text(URLEncoder.encode(toText(args.get(0)), StandardCharsets.UTF_8).replace("+", "%20")));
        register("FORMAT", (args, ctx) -> format(args));
        register("REGEXEXTRACT", (args, ctx) -> regexExtract(args, false));
        register("REGEXEXTRACTALL", (args, ctx) -> regexExtract(args, true));
        register("REGEXMATCH", (args, ctx) -> regexMatch(args));
        register("REGEXREPLACE", (args, ctx) -> regexReplace(args));
        register("TEXT", (args, ctx) -> textFormat(args));
        register("TODATE", (args, ctx) -> parseDate(args.get(0)));
    }

    private void registerNumericFunctions() {
        register("ABS", (args, ctx) -> mapNumber(args, 0, x -> FormulaValue.decimal(x.abs())));
        register("ACOS", (args, ctx) -> mapMath(args, 0, Math::acos));
        register("ACOSH", (args, ctx) -> mapMath(args, 0, x -> Math.log(x + Math.sqrt(x * x - 1))));
        register("ASIN", (args, ctx) -> mapMath(args, 0, Math::asin));
        register("ASINH", (args, ctx) -> mapMath(args, 0, x -> Math.log(x + Math.sqrt(x * x + 1))));
        register("ATAN", (args, ctx) -> mapMath(args, 0, Math::atan));
        register("ATAN2", (args, ctx) -> mapMath2(args, (x, y) -> Math.atan2(y, x)));
        register("ATANH", (args, ctx) -> mapMath(args, 0, x -> 0.5 * Math.log((1 + x) / (1 - x))));
        register("AVERAGE", (args, ctx) -> aggregate(args, Aggregate.AVERAGE));
        register("CEILING", (args, ctx) -> multipleRound(args, true));
        register("COS", (args, ctx) -> mapMath(args, 0, Math::cos));
        register("COSH", (args, ctx) -> mapMath(args, 0, Math::cosh));
        register("EXP", (args, ctx) -> mapMath(args, 0, Math::exp));
        register("FLOOR", (args, ctx) -> multipleRound(args, false));
        register("INT", (args, ctx) -> mapNumber(args, 0, x -> FormulaValue.decimal(x.setScale(0, RoundingMode.FLOOR))));
        register("LARGE", (args, ctx) -> kth(args, true));
        register("LN", (args, ctx) -> mapMath(args, 0, Math::log));
        register("LOG", (args, ctx) -> log(args));
        register("MAX", (args, ctx) -> aggregate(args, Aggregate.MAX));
        register("MEDIAN", (args, ctx) -> aggregate(args, Aggregate.MEDIAN));
        register("MIN", (args, ctx) -> aggregate(args, Aggregate.MIN));
        register("MOD", (args, ctx) -> binaryNumber(args, BigDecimal::remainder));
        register("PI", (args, ctx) -> FormulaValue.decimal(BigDecimal.valueOf(Math.PI)));
        register("POWER", (args, ctx) -> mapMath2(args, Math::pow));
        register("PRODUCT", (args, ctx) -> aggregate(args, Aggregate.PRODUCT));
        register("QUOTIENT", (args, ctx) -> binaryNumber(args, BigDecimal::divideToIntegralValue));
        register("RANK", (args, ctx) -> rank(args));
        register("ROUND", (args, ctx) -> round(args, RoundingMode.HALF_UP));
        register("ROUNDDOWN", (args, ctx) -> round(args, RoundingMode.DOWN));
        register("ROUNDUP", (args, ctx) -> round(args, RoundingMode.UP));
        register("SEQUENCE", (args, ctx) -> sequence(args));
        register("SIN", (args, ctx) -> mapMath(args, 0, Math::sin));
        register("SINH", (args, ctx) -> mapMath(args, 0, Math::sinh));
        register("SMALL", (args, ctx) -> kth(args, false));
        register("SQRT", (args, ctx) -> mapMath(args, 0, Math::sqrt));
        register("STDEV", (args, ctx) -> standardDeviation(args));
        register("SUM", (args, ctx) -> aggregate(args, Aggregate.SUM));
        register("TAN", (args, ctx) -> mapMath(args, 0, Math::tan));
        register("TANH", (args, ctx) -> mapMath(args, 0, Math::tanh));
        register("COUNTA", (args, ctx) -> FormulaValue.decimal(BigDecimal.valueOf(flatten(args).stream().filter(v -> !isBlank(v)).count())));
        register("COUNTIF", (args, ctx) -> FormulaValue.error("#VALUE!", "COUNTIF needs an expression context"));
        register("SUMIF", (args, ctx) -> FormulaValue.error("#VALUE!", "SUMIF needs an expression context"));
        register("RANDOMARRAY", (args, ctx) -> randomArray(args));
    }

    private void registerDateFunctions() {
        register("DATE", (args, ctx) -> date(args));
        register("DATEDIF", (args, ctx) -> dateDiff(args));
        register("DAY", (args, ctx) -> datePart(args.get(0), DatePart.DAY));
        register("DAYS", (args, ctx) -> dateDiffDays(args));
        register("EDATE", (args, ctx) -> shiftMonth(args, false));
        register("EOMONTH", (args, ctx) -> shiftMonth(args, true));
        register("HOUR", (args, ctx) -> timePart(args.get(0), DatePart.HOUR));
        register("MINUTE", (args, ctx) -> timePart(args.get(0), DatePart.MINUTE));
        register("MONTH", (args, ctx) -> datePart(args.get(0), DatePart.MONTH));
        register("NETWORKDAYS", (args, ctx) -> workdays(args, false));
        register("NOW", (args, ctx) -> FormulaValue.dateTime(LocalDateTime.ofInstant(ctx.clock().instant(), ctx.zoneId())));
        register("SECOND", (args, ctx) -> timePart(args.get(0), DatePart.SECOND));
        register("TODAY", (args, ctx) -> FormulaValue.dateTime(ctx.clock().instant().atZone(ctx.zoneId()).toLocalDate()));
        register("WEEKDAY", (args, ctx) -> weekday(args));
        register("WEEKNUM", (args, ctx) -> weeknum(args));
        register("WORKDAY", (args, ctx) -> workdays(args, true));
        register("YEAR", (args, ctx) -> datePart(args.get(0), DatePart.YEAR));
        register("DURATION", (args, ctx) -> duration(args));
    }

    private void registerListFunctions() {
        register("ARRAYJOIN", (args, ctx) -> FormulaValue.text(join(args.isEmpty() ? List.of() : asList(args.get(0)),
                args.size() > 1 ? toText(args.get(1)) : ",")));
        register("FIRST", (args, ctx) -> listItem(args, 0));
        register("LAST", (args, ctx) -> {
            List<FormulaValue> list = args.isEmpty() ? List.of() : asList(args.get(0));
            return listItem(args, list.size() - 1);
        });
        register("LIST", (args, ctx) -> FormulaValue.list(args));
        register("LISTCOMBINE", (args, ctx) -> FormulaValue.list(flatten(args)));
        register("NTH", (args, ctx) -> {
            if (args.size() < 2) return FormulaValue.error("#VALUE!", "NTH expects a list and position");
            return listItem(List.of(args.get(0), args.get(1)), intValue(args.get(1)) - 1);
        });
        register("UNIQUE", (args, ctx) -> FormulaValue.list(new ArrayList<>(new LinkedHashSet<>(flatten(args)))));
        register("SORT", (args, ctx) -> sort(args));
        register("SORTBY", (args, ctx) -> sort(args));
        register("FILTER", (args, ctx) -> FormulaValue.error("#VALUE!", "FILTER needs an expression context"));
        register("MAP", (args, ctx) -> FormulaValue.error("#VALUE!", "MAP needs an expression context"));
        register("LOOKUP", (args, ctx) -> lookupFunction(args));
        register("DISTANCE", (args, ctx) -> distance(args));
    }

    @FunctionalInterface
    private interface NumberMapper {
        FormulaValue apply(BigDecimal value);
    }

    @FunctionalInterface
    private interface DoubleMapper {
        double apply(double value);
    }

    @FunctionalInterface
    private interface DoubleBiMapper {
        double apply(double left, double right);
    }

    @FunctionalInterface
    private interface DecimalBiMapper {
        BigDecimal apply(BigDecimal left, BigDecimal right);
    }

    private enum Aggregate { SUM, AVERAGE, MIN, MAX, MEDIAN, PRODUCT }
    private enum DatePart { YEAR, MONTH, DAY, HOUR, MINUTE, SECOND }

    private static FormulaValue mapNumber(List<FormulaValue> args, int index, NumberMapper mapper) {
        FormulaValue number = numberAt(args, index);
        if (number.kind() == FormulaValue.Kind.ERROR) return number;
        try {
            return mapper.apply(number.asDecimal());
        } catch (RuntimeException ex) {
            return FormulaValue.error("#NUM!", "numeric function failed");
        }
    }

    private static FormulaValue mapMath(List<FormulaValue> args, int index, DoubleMapper mapper) {
        return mapNumber(args, index, number -> fromDouble(mapper.apply(number.doubleValue())));
    }

    private static FormulaValue mapMath2(List<FormulaValue> args, DoubleBiMapper mapper) {
        FormulaValue left = numberAt(args, 0);
        FormulaValue right = numberAt(args, 1);
        if (left.kind() == FormulaValue.Kind.ERROR) return left;
        if (right.kind() == FormulaValue.Kind.ERROR) return right;
        return fromDouble(mapper.apply(left.asDecimal().doubleValue(), right.asDecimal().doubleValue()));
    }

    private static FormulaValue fromDouble(double value) {
        return Double.isFinite(value) ? FormulaValue.decimal(BigDecimal.valueOf(value))
                : FormulaValue.error("#NUM!", "result is outside numeric range");
    }

    private static FormulaValue binaryNumber(List<FormulaValue> args, DecimalBiMapper mapper) {
        FormulaValue left = numberAt(args, 0);
        FormulaValue right = numberAt(args, 1);
        if (left.kind() == FormulaValue.Kind.ERROR) return left;
        if (right.kind() == FormulaValue.Kind.ERROR) return right;
        try {
            return FormulaValue.decimal(mapper.apply(left.asDecimal(), right.asDecimal()));
        } catch (ArithmeticException ex) {
            return FormulaValue.error("#DIV/0!", "invalid divisor");
        }
    }

    private static FormulaValue numberAt(List<FormulaValue> args, int index) {
        if (index < 0 || index >= args.size()) return FormulaValue.error("#VALUE!", "missing numeric argument");
        return parseNumber(args.get(index));
    }

    private static FormulaValue aggregate(List<FormulaValue> args, Aggregate aggregate) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (FormulaValue value : flatten(args)) {
            if (isBlank(value)) continue;
            FormulaValue number = parseNumber(value);
            if (number.kind() == FormulaValue.Kind.ERROR) return number;
            numbers.add(number.asDecimal());
        }
        if (numbers.isEmpty()) {
            return aggregate == Aggregate.SUM ? FormulaValue.decimal(BigDecimal.ZERO)
                    : FormulaValue.error("#DIV/0!", "empty numeric range");
        }
        return switch (aggregate) {
            case SUM -> FormulaValue.decimal(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            case PRODUCT -> FormulaValue.decimal(numbers.stream().reduce(BigDecimal.ONE, BigDecimal::multiply));
            case MIN -> FormulaValue.decimal(numbers.stream().min(BigDecimal::compareTo).orElseThrow());
            case MAX -> FormulaValue.decimal(numbers.stream().max(BigDecimal::compareTo).orElseThrow());
            case AVERAGE -> FormulaValue.decimal(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(numbers.size()), java.math.MathContext.DECIMAL128));
            case MEDIAN -> {
                numbers.sort(BigDecimal::compareTo);
                int middle = numbers.size() / 2;
                yield FormulaValue.decimal(numbers.size() % 2 == 1 ? numbers.get(middle)
                        : numbers.get(middle - 1).add(numbers.get(middle)).divide(BigDecimal.valueOf(2)));
            }
        };
    }

    private static FormulaValue multipleRound(List<FormulaValue> args, boolean ceiling) {
        FormulaValue value = numberAt(args, 0);
        FormulaValue factorValue = args.size() > 1 ? numberAt(args, 1) : FormulaValue.decimal(BigDecimal.ONE);
        if (value.kind() == FormulaValue.Kind.ERROR) return value;
        if (factorValue.kind() == FormulaValue.Kind.ERROR) return factorValue;
        BigDecimal factor = factorValue.asDecimal();
        if (factor.compareTo(BigDecimal.ZERO) == 0) return FormulaValue.error("#DIV/0!", "factor cannot be zero");
        BigDecimal quotient = value.asDecimal().divide(factor, 0, ceiling ? RoundingMode.CEILING : RoundingMode.FLOOR);
        return FormulaValue.decimal(quotient.multiply(factor));
    }

    private static FormulaValue round(List<FormulaValue> args, RoundingMode mode) {
        FormulaValue value = numberAt(args, 0);
        if (value.kind() == FormulaValue.Kind.ERROR) return value;
        int places = args.size() > 1 ? intValue(args.get(1)) : 0;
        if (Math.abs(places) > 1000) return FormulaValue.error("#NUM!", "decimal places out of range");
        return FormulaValue.decimal(value.asDecimal().setScale(places, mode));
    }

    private static FormulaValue kth(List<FormulaValue> args, boolean largest) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "rank and list are required");
        List<BigDecimal> values;
        try { values = numericList(args.get(0)); }
        catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "rank list must contain numbers"); }
        int k = intValue(args.get(1));
        if (k < 1 || k > values.size()) return FormulaValue.error("#NUM!", "rank is outside the list");
        values.sort(largest ? Comparator.reverseOrder() : Comparator.naturalOrder());
        return FormulaValue.decimal(values.get(k - 1));
    }

    private static List<BigDecimal> numericList(FormulaValue value) {
        List<BigDecimal> result = new ArrayList<>();
        for (FormulaValue item : asList(value)) {
            FormulaValue number = parseNumber(item);
            if (number.kind() == FormulaValue.Kind.ERROR) throw new IllegalArgumentException(number.errorMessage());
            result.add(number.asDecimal());
        }
        return result;
    }

    private static FormulaValue sequence(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "SEQUENCE needs start and end");
        BigDecimal start = numberAt(args, 0).asDecimal();
        BigDecimal end = numberAt(args, 1).asDecimal();
        BigDecimal step = args.size() > 2 ? numberAt(args, 2).asDecimal()
                : start.compareTo(end) <= 0 ? BigDecimal.ONE : BigDecimal.ONE.negate();
        if (step.signum() == 0) return FormulaValue.error("#DIV/0!", "step cannot be zero");
        List<FormulaValue> out = new ArrayList<>();
        BigDecimal value = start;
        while ((step.signum() > 0 && value.compareTo(end) <= 0)
                || (step.signum() < 0 && value.compareTo(end) >= 0)) {
            if (out.size() >= 10_000) return FormulaValue.error("#NUM!", "sequence exceeds 10000 values");
            out.add(FormulaValue.decimal(value));
            value = value.add(step);
        }
        return FormulaValue.list(out);
    }

    private static FormulaValue standardDeviation(List<FormulaValue> args) {
        List<BigDecimal> numbers;
        try { numbers = new ArrayList<>(numericList(FormulaValue.list(flatten(args)))); }
        catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "STDEV expects numeric values"); }
        if (numbers.size() < 2) return FormulaValue.error("#DIV/0!", "STDEV requires at least two values");
        double average = numbers.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double variance = numbers.stream().mapToDouble(v -> Math.pow(v.doubleValue() - average, 2)).sum() / (numbers.size() - 1);
        return fromDouble(Math.sqrt(variance));
    }

    private static FormulaValue rank(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "RANK expects value and list");
        FormulaValue target = parseNumber(args.get(0));
        if (target.kind() == FormulaValue.Kind.ERROR) return target;
        List<BigDecimal> values;
        try { values = numericList(args.get(1)); }
        catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "RANK expects numeric values"); }
        boolean ascending = args.size() > 2 && truthy(args.get(2));
        long rank = values.stream().filter(v -> ascending ? v.compareTo(target.asDecimal()) < 0 : v.compareTo(target.asDecimal()) > 0).count() + 1;
        return FormulaValue.decimal(BigDecimal.valueOf(rank));
    }

    private static FormulaValue log(List<FormulaValue> args) {
        FormulaValue value = numberAt(args, 0);
        FormulaValue base = args.size() > 1 ? numberAt(args, 1) : FormulaValue.decimal(BigDecimal.TEN);
        if (value.kind() == FormulaValue.Kind.ERROR) return value;
        if (base.kind() == FormulaValue.Kind.ERROR) return base;
        return fromDouble(Math.log(value.asDecimal().doubleValue()) / Math.log(base.asDecimal().doubleValue()));
    }

    private static FormulaValue randomArray(List<FormulaValue> args) {
        if (args.isEmpty()) return FormulaValue.error("#VALUE!", "RANDOMARRAY needs a count");
        int count = intValue(args.get(0));
        if (count < 0 || count > 10_000) return FormulaValue.error("#NUM!", "RANDOMARRAY count out of range");
        double min = args.size() > 1 ? numberAt(args, 1).asDecimal().doubleValue() : 0;
        double max = args.size() > 2 ? numberAt(args, 2).asDecimal().doubleValue() : 1;
        boolean integer = args.size() > 3 && truthy(args.get(3));
        boolean allowDuplicates = args.size() <= 4 || truthy(args.get(4));
        Random random = new Random();
        List<FormulaValue> values = new ArrayList<>();
        int attempts = 0;
        while (values.size() < count && attempts++ < count * 100 + 100) {
            double sample = min + random.nextDouble() * (max - min);
            FormulaValue value = integer ? FormulaValue.decimal(BigDecimal.valueOf((long) Math.floor(sample)))
                    : FormulaValue.decimal(BigDecimal.valueOf(sample));
            if (allowDuplicates || values.stream().noneMatch(existing -> valueEquals(existing, value))) values.add(value);
        }
        if (values.size() != count) return FormulaValue.error("#NUM!", "cannot generate requested unique values");
        return FormulaValue.list(values);
    }

    private static FormulaValue randomBetween(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "RANDOMBETWEEN needs bounds");
        int min = intValue(args.get(0));
        int max = intValue(args.get(1));
        if (max < min) return FormulaValue.error("#NUM!", "maximum is below minimum");
        return FormulaValue.decimal(BigDecimal.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextLong(min, (long) max + 1)));
    }

    private static FormulaValue randomItem(List<FormulaValue> args) {
        if (args.isEmpty()) return FormulaValue.error("#VALUE!", "RANDOMITEM needs a list");
        List<FormulaValue> values = asList(args.get(0));
        return values.isEmpty() ? FormulaValue.error("#N/A", "empty list")
                : values.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(values.size()));
    }

    private static FormulaValue switchValue(List<FormulaValue> args) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "SWITCH expects an expression and a case");
        FormulaValue expression = args.get(0);
        for (int i = 1; i + 1 < args.size(); i += 2) {
            if (valueEquals(expression, args.get(i))) return args.get(i + 1);
        }
        return args.size() % 2 == 0 ? args.get(args.size() - 1) : FormulaValue.error("#N/A", "no SWITCH case matched");
    }

    private static boolean containsAll(List<FormulaValue> args) {
        if (args.size() < 2) return false;
        List<FormulaValue> range = asList(args.get(0));
        return args.subList(1, args.size()).stream().allMatch(needle -> range.stream().anyMatch(value -> valueEquals(value, needle)));
    }

    private static boolean containsOnly(List<FormulaValue> args) {
        if (args.isEmpty()) return false;
        List<FormulaValue> range = asList(args.get(0));
        List<FormulaValue> wanted = args.subList(1, args.size());
        return range.size() == wanted.size() && wanted.stream().allMatch(needle -> range.stream().anyMatch(value -> valueEquals(value, needle)));
    }

    private static FormulaValue find(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "FIND expects search text and text");
        String search = toText(args.get(0));
        String text = toText(args.get(1));
        int start = args.size() > 2 ? Math.max(1, intValue(args.get(2))) - 1 : 0;
        int position = text.indexOf(search, start);
        return position < 0 ? FormulaValue.error("#VALUE!", "text not found") : FormulaValue.decimal(BigDecimal.valueOf(position + 1));
    }

    private static FormulaValue sliceText(List<FormulaValue> args, boolean left) {
        if (args.isEmpty()) return FormulaValue.error("#VALUE!", "text argument is required");
        String text = toText(args.get(0));
        int length = args.size() > 1 ? intValue(args.get(1)) : 1;
        if (length < 0) return FormulaValue.error("#VALUE!", "character count cannot be negative");
        int count = text.codePointCount(0, text.length());
        int take = Math.min(count, length);
        int start = left ? 0 : text.offsetByCodePoints(0, count - take);
        int end = left ? text.offsetByCodePoints(0, take) : text.length();
        return FormulaValue.text(text.substring(start, end));
    }

    private static FormulaValue mid(List<FormulaValue> args) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "MID expects text, start and length");
        String text = toText(args.get(0));
        int start = intValue(args.get(1)) - 1;
        int length = intValue(args.get(2));
        int count = text.codePointCount(0, text.length());
        if (start < 0 || length < 0) return FormulaValue.error("#VALUE!", "invalid MID range");
        int from = text.offsetByCodePoints(0, Math.min(start, count));
        int to = text.offsetByCodePoints(from, Math.min(length, count - Math.min(start, count)));
        return FormulaValue.text(text.substring(from, to));
    }

    private static FormulaValue replaceText(List<FormulaValue> args) {
        if (args.size() < 4) return FormulaValue.error("#VALUE!", "REPLACE expects 4 arguments");
        String text = toText(args.get(0));
        int start = intValue(args.get(1)) - 1;
        int length = intValue(args.get(2));
        int count = text.codePointCount(0, text.length());
        if (start < 0 || length < 0) return FormulaValue.error("#VALUE!", "invalid REPLACE range");
        int from = text.offsetByCodePoints(0, Math.min(start, count));
        int to = text.offsetByCodePoints(from, Math.min(length, count - Math.min(start, count)));
        return FormulaValue.text(text.substring(0, from) + toText(args.get(3)) + text.substring(to));
    }

    private static FormulaValue split(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "SPLIT expects text and separator");
        String separator = toText(args.get(1));
        if (separator.isEmpty()) return FormulaValue.error("#VALUE!", "separator cannot be empty");
        return FormulaValue.list(java.util.Arrays.stream(toText(args.get(0)).split(Pattern.quote(separator), -1))
                .map(FormulaValue::text).toList());
    }

    private static FormulaValue format(List<FormulaValue> args) {
        if (args.isEmpty()) return FormulaValue.error("#VALUE!", "FORMAT needs a template");
        String result = toText(args.get(0));
        for (int i = 1; i < args.size(); i++) result = result.replace("{" + i + "}", toText(args.get(i)));
        return FormulaValue.text(result);
    }

    private static FormulaValue regexExtract(List<FormulaValue> args, boolean all) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "regular expression arguments are missing");
        try {
            Pattern pattern = safePattern(toText(args.get(1)));
            Matcher matcher = pattern.matcher(toText(args.get(0)));
            if (all) {
                List<FormulaValue> matches = new ArrayList<>();
                while (matcher.find()) matches.add(FormulaValue.text(matcher.group()));
                return FormulaValue.list(matches);
            }
            return matcher.find() ? FormulaValue.text(matcher.group()) : FormulaValue.error("#N/A", "no regular expression match");
        } catch (RuntimeException ex) {
            return FormulaValue.error("#VALUE!", "invalid regular expression");
        }
    }

    private static FormulaValue regexMatch(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "regular expression arguments are missing");
        try { return FormulaValue.bool(safePattern(toText(args.get(1))).matcher(toText(args.get(0))).find()); }
        catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid regular expression"); }
    }

    private static FormulaValue regexReplace(List<FormulaValue> args) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "REGEXREPLACE expects 3 arguments");
        try { return FormulaValue.text(safePattern(toText(args.get(1))).matcher(toText(args.get(0))).replaceAll(toText(args.get(2)))); }
        catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid regular expression"); }
    }

    private static Pattern safePattern(String value) {
        if (value.length() > 256 || value.matches(".*\\([^)]*[+*][^)]*\\)[+*].*")) {
            throw new IllegalArgumentException("unsafe or oversized regular expression");
        }
        return Pattern.compile(value);
    }

    private static FormulaValue textFormat(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "TEXT expects value and format");
        String format = toText(args.get(1));
        FormulaValue value = args.get(0);
        if (value.kind() == FormulaValue.Kind.TEXT) {
            FormulaValue parsedDate = parseDate(value);
            if (parsedDate.kind() == FormulaValue.Kind.DATE_TIME) value = parsedDate;
        }
        if (value.kind() == FormulaValue.Kind.DATE_TIME) {
            LocalDateTime dateTime = toDateTime(value);
            if (format.equalsIgnoreCase("ddd")) {
                String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
                return FormulaValue.text(weekdays[dateTime.getDayOfWeek().getValue() - 1]);
            }
            String pattern = format.replace("YYYY", "uuuu").replace("YY", "yy").replace("DDDD", "EEEE")
                    .replace("DDD", "EEE").replace("DD", "dd").replace("D", "d").replace("MM", "MM").replace("M", "M")
                    .replace("hh", "HH").replace("mm", "mm").replace("ss", "ss");
            try { return FormulaValue.text(dateTime.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ROOT))); }
            catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid date format"); }
        }
        FormulaValue number = parseNumber(value);
        if (number.kind() == FormulaValue.Kind.ERROR) return number;
        try {
            String javaPattern = format.replace("#", "#").replace("0", "0");
            return FormulaValue.text(new java.text.DecimalFormat(javaPattern).format(number.asDecimal()));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid number format"); }
    }

    private static FormulaValue date(List<FormulaValue> args) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "DATE expects year, month and day");
        try { return FormulaValue.dateTime(LocalDate.of(intValue(args.get(0)), intValue(args.get(1)), intValue(args.get(2)))); }
        catch (RuntimeException ex) { return FormulaValue.error("#NUM!", "invalid date components"); }
    }

    private static FormulaValue parseDate(FormulaValue value) {
        if (value.kind() == FormulaValue.Kind.DATE_TIME) return value;
        String text = toText(value).trim();
        try { return FormulaValue.dateTime(LocalDateTime.parse(text)); } catch (RuntimeException ignored) { }
        for (String pattern : List.of("yyyy-M-d", "yyyy/M/d", "yyyy-MM-dd", "yyyy/MM/dd")) {
            try { return FormulaValue.dateTime(LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern(pattern))); }
            catch (RuntimeException ignored) { }
        }
        return FormulaValue.error("#VALUE!", "cannot convert value to date");
    }

    private static LocalDateTime toDateTime(FormulaValue value) {
        Object raw = value.rawValue();
        if (raw instanceof LocalDateTime dateTime) return dateTime;
        if (raw instanceof LocalDate date) return date.atStartOfDay();
        if (raw instanceof Instant instant) return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
        if (raw instanceof Duration duration) return LocalDateTime.of(1899, 12, 30, 0, 0).plus(duration);
        FormulaValue parsed = parseDate(value);
        if (parsed.kind() == FormulaValue.Kind.ERROR) throw new IllegalArgumentException(parsed.errorMessage());
        return toDateTime(parsed);
    }

    private static FormulaValue dateDiff(List<FormulaValue> args) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "DATEDIF expects start, end and unit");
        try {
            LocalDate start = toDateTime(parseDate(args.get(0))).toLocalDate();
            LocalDate end = toDateTime(parseDate(args.get(1))).toLocalDate();
            String unit = toText(args.get(2)).toUpperCase(Locale.ROOT);
            long result = switch (unit) {
                case "D" -> ChronoUnit.DAYS.between(start, end);
                case "M" -> ChronoUnit.MONTHS.between(start, end);
                case "Y" -> ChronoUnit.YEARS.between(start, end);
                default -> throw new IllegalArgumentException("unknown unit");
            };
            return FormulaValue.decimal(BigDecimal.valueOf(result));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid DATEDIF arguments"); }
    }

    private static FormulaValue dateDiffDays(List<FormulaValue> args) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "DAYS expects end and start");
        try {
            LocalDate end = toDateTime(parseDate(args.get(0))).toLocalDate();
            LocalDate start = toDateTime(parseDate(args.get(1))).toLocalDate();
            return FormulaValue.decimal(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end)));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid date arguments"); }
    }

    private static FormulaValue datePart(FormulaValue value, DatePart part) {
        try {
            LocalDate date = toDateTime(parseDate(value)).toLocalDate();
            int result = switch (part) { case YEAR -> date.getYear(); case MONTH -> date.getMonthValue(); case DAY -> date.getDayOfMonth(); default -> 0; };
            return FormulaValue.decimal(BigDecimal.valueOf(result));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid date"); }
    }

    private static FormulaValue timePart(FormulaValue value, DatePart part) {
        try {
            LocalTime time = LocalTime.parse(toText(value));
            int result = switch (part) { case HOUR -> time.getHour(); case MINUTE -> time.getMinute(); case SECOND -> time.getSecond(); default -> 0; };
            return FormulaValue.decimal(BigDecimal.valueOf(result));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid time"); }
    }

    private static FormulaValue shiftMonth(List<FormulaValue> args, boolean endOfMonth) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "date and month offset are required");
        try {
            LocalDate date = toDateTime(parseDate(args.get(0))).toLocalDate().plusMonths(intValue(args.get(1)));
            if (endOfMonth) date = date.with(TemporalAdjusters.lastDayOfMonth());
            return FormulaValue.dateTime(date);
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid month offset"); }
    }

    private static FormulaValue weekday(List<FormulaValue> args) {
        try {
            LocalDate date = toDateTime(parseDate(args.get(0))).toLocalDate();
            int type = args.size() > 1 ? intValue(args.get(1)) : 1;
            int mondayBased = date.getDayOfWeek().getValue();
            int result = type == 2 ? mondayBased : mondayBased % 7 + 1;
            return FormulaValue.decimal(BigDecimal.valueOf(result));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid date"); }
    }

    private static FormulaValue weeknum(List<FormulaValue> args) {
        try {
            LocalDate date = toDateTime(parseDate(args.get(0))).toLocalDate();
            int type = args.size() > 1 ? intValue(args.get(1)) : 1;
            java.time.temporal.WeekFields fields = type == 2 ? java.time.temporal.WeekFields.ISO
                    : java.time.temporal.WeekFields.SUNDAY_START;
            return FormulaValue.decimal(BigDecimal.valueOf(date.get(fields.weekOfYear())));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid date"); }
    }

    private static FormulaValue duration(List<FormulaValue> args) {
        try {
            long seconds = 0;
            long[] multipliers = {86400, 3600, 60, 1};
            for (int i = 0; i < Math.min(args.size(), multipliers.length); i++) seconds += numberAt(args, i).asDecimal().multiply(BigDecimal.valueOf(multipliers[i])).longValue();
            return FormulaValue.dateTime(Duration.ofSeconds(seconds));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid duration"); }
    }

    private static FormulaValue workdays(List<FormulaValue> args, boolean returnDate) {
        if (args.size() < 2) return FormulaValue.error("#VALUE!", "workday arguments are missing");
        try {
            LocalDate start = toDateTime(parseDate(args.get(0))).toLocalDate();
            Set<LocalDate> holidays = new LinkedHashSet<>();
            if (args.size() > 2) for (FormulaValue holiday : asList(args.get(2))) holidays.add(toDateTime(parseDate(holiday)).toLocalDate());
            if (!returnDate) {
                LocalDate end = toDateTime(parseDate(args.get(1))).toLocalDate();
                int direction = end.isBefore(start) ? -1 : 1;
                long count = 0;
                for (LocalDate date = start; direction > 0 ? !date.isAfter(end) : !date.isBefore(end); date = date.plusDays(direction)) {
                    if (isWorkday(date, holidays)) count += direction;
                }
                return FormulaValue.decimal(BigDecimal.valueOf(count));
            }
            int amount = intValue(args.get(1));
            LocalDate date = start;
            int remaining = Math.abs(amount);
            int direction = amount < 0 ? -1 : 1;
            while (remaining > 0) {
                date = date.plusDays(direction);
                if (isWorkday(date, holidays)) remaining--;
                if (Math.abs(ChronoUnit.DAYS.between(start, date)) > 100_000) return FormulaValue.error("#NUM!", "workday range exceeds limit");
            }
            return FormulaValue.dateTime(date);
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid workday arguments"); }
    }

    private static boolean isWorkday(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    private static FormulaValue listItem(List<FormulaValue> args, int index) {
        if (args.isEmpty()) return FormulaValue.error("#VALUE!", "list argument is required");
        List<FormulaValue> values = asList(args.get(0));
        return index < 0 || index >= values.size() ? FormulaValue.error("#N/A", "list position is outside the range") : values.get(index);
    }

    private static FormulaValue sort(List<FormulaValue> args) {
        if (args.isEmpty()) return FormulaValue.error("#VALUE!", "list argument is required");
        List<FormulaValue> values = new ArrayList<>(asList(args.get(0)));
        boolean ascending = args.size() < 2 || truthy(args.get(1));
        values.sort((left, right) -> {
            FormulaValue l = parseNumber(left), r = parseNumber(right);
            int compare = l.kind() == FormulaValue.Kind.ERROR || r.kind() == FormulaValue.Kind.ERROR
                    ? toText(left).compareTo(toText(right)) : l.asDecimal().compareTo(r.asDecimal());
            return ascending ? compare : -compare;
        });
        return FormulaValue.list(values);
    }

    private static FormulaValue lookupFunction(List<FormulaValue> args) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "LOOKUP expects search, match list and result list");
        List<FormulaValue> keys = asList(args.get(1));
        List<FormulaValue> values = asList(args.get(2));
        for (int i = 0; i < Math.min(keys.size(), values.size()); i++) if (valueEquals(args.get(0), keys.get(i))) return values.get(i);
        return FormulaValue.error("#N/A", "lookup value not found");
    }

    private static FormulaValue distance(List<FormulaValue> args) {
        if (args.size() < 2 || !(args.get(0).rawValue() instanceof Map<?, ?> first)
                || !(args.get(1).rawValue() instanceof Map<?, ?> second)) {
            return FormulaValue.error("#VALUE!", "DISTANCE expects two location values");
        }
        try {
            double lat1 = coordinate(first, "latitude", "lat");
            double lon1 = coordinate(first, "longitude", "lng", "lon");
            double lat2 = coordinate(second, "latitude", "lat");
            double lon2 = coordinate(second, "longitude", "lng", "lon");
            double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
            double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
            return FormulaValue.decimal(BigDecimal.valueOf(6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))));
        } catch (RuntimeException ex) { return FormulaValue.error("#VALUE!", "invalid location coordinates"); }
    }

    private static List<FormulaValue> flatten(List<FormulaValue> values) {
        List<FormulaValue> out = new ArrayList<>();
        for (FormulaValue value : values) {
            if (value.kind() == FormulaValue.Kind.LIST) out.addAll(castList(value.rawValue()));
            else out.add(value);
        }
        return out;
    }

    private static List<FormulaValue> asList(FormulaValue value) {
        return value.kind() == FormulaValue.Kind.LIST ? castList(value.rawValue()) : List.of(value);
    }

    private static int intValue(FormulaValue value) {
        FormulaValue number = parseNumber(value);
        if (number.kind() == FormulaValue.Kind.ERROR) throw new IllegalArgumentException(number.errorMessage());
        return number.asDecimal().intValue();
    }

    private static double coordinate(Map<?, ?> location, String... names) {
        for (String name : names) {
            Object value = location.get(name);
            if (value != null) return Double.parseDouble(String.valueOf(value));
        }
        throw new IllegalArgumentException("missing coordinate");
    }

    public FormulaFunction resolve(String name) {
        return functions.get(normalize(name));
    }

    public boolean supports(String name) {
        return resolve(name) != null;
    }

    public List<String> names() {
        return functions.keySet().stream().sorted().toList();
    }

    private void register(String name, FormulaFunction function) {
        functions.put(normalize(name), function);
    }

    private static FormulaValue substitute(List<FormulaValue> args, FormulaEvaluationContext context) {
        if (args.size() < 3) return FormulaValue.error("#VALUE!", "SUBSTITUTE needs at least 3 arguments");
        String source = toText(args.get(0));
        String oldValue = toText(args.get(1));
        String replacement = toText(args.get(2));
        if (oldValue.isEmpty()) return FormulaValue.text(source);
        if (args.size() < 4) return FormulaValue.text(source.replace(oldValue, replacement));
        int occurrence;
        try {
            occurrence = parseNumber(args.get(3)).asDecimal().intValueExact();
        } catch (RuntimeException ex) {
            return FormulaValue.error("#VALUE!", "invalid SUBSTITUTE occurrence");
        }
        if (occurrence <= 0) return FormulaValue.error("#VALUE!", "occurrence must be positive");
        int from = 0;
        for (int i = 1; i < occurrence; i++) {
            int found = source.indexOf(oldValue, from);
            if (found < 0) return FormulaValue.text(source);
            from = found + oldValue.length();
        }
        int found = source.indexOf(oldValue, from);
        if (found < 0) return FormulaValue.text(source);
        return FormulaValue.text(source.substring(0, found) + replacement + source.substring(found + oldValue.length()));
    }

    private static boolean contains(List<FormulaValue> args) {
        if (args.size() < 2) return false;
        FormulaValue range = args.get(0);
        List<FormulaValue> values = range.kind() == FormulaValue.Kind.LIST
                ? castList(range.rawValue()) : List.of(range);
        for (int i = 1; i < args.size(); i++) {
            FormulaValue needle = args.get(i);
            for (FormulaValue value : values) {
                if (valueEquals(value, needle)) return true;
            }
        }
        return false;
    }

    private static boolean containsText(List<FormulaValue> args) {
        if (args.size() < 2) return false;
        String source = toText(args.get(0));
        for (int i = 1; i < args.size(); i++) {
            if (source.contains(toText(args.get(i)))) return true;
        }
        return false;
    }

    private static boolean valueEquals(FormulaValue left, FormulaValue right) {
        if (left.kind() == FormulaValue.Kind.DECIMAL && right.kind() == FormulaValue.Kind.DECIMAL) {
            return left.asDecimal().compareTo(right.asDecimal()) == 0;
        }
        return toText(left).equalsIgnoreCase(toText(right));
    }

    static boolean truthy(FormulaValue value) {
        return switch (value.kind()) {
            case BLANK -> false;
            case BOOLEAN -> Boolean.TRUE.equals(value.rawValue());
            case DECIMAL -> ((BigDecimal) value.rawValue()).compareTo(BigDecimal.ZERO) != 0;
            case TEXT -> !((String) value.rawValue()).isEmpty();
            case ERROR -> false;
            default -> value.rawValue() != null;
        };
    }

    static boolean isBlank(FormulaValue value) {
        return value.kind() == FormulaValue.Kind.BLANK
                || (value.kind() == FormulaValue.Kind.TEXT && ((String) value.rawValue()).isEmpty());
    }

    static String toText(FormulaValue value) {
        if (value.kind() == FormulaValue.Kind.BLANK) return "";
        if (value.kind() == FormulaValue.Kind.ERROR) return value.errorCode();
        if (value.kind() == FormulaValue.Kind.DECIMAL) return ((BigDecimal) value.rawValue()).stripTrailingZeros().toPlainString();
        if (value.kind() == FormulaValue.Kind.LIST) return join(castList(value.rawValue()), ",");
        return String.valueOf(value.rawValue());
    }

    static FormulaValue parseNumber(FormulaValue value) {
        if (value.kind() == FormulaValue.Kind.DECIMAL) return value;
        String text = toText(value).replace(",", "").replace("$", "").trim();
        try {
            return FormulaValue.decimal(new BigDecimal(text));
        } catch (RuntimeException ex) {
            return FormulaValue.error("#VALUE!", "cannot convert text to a number");
        }
    }

    private static String join(List<FormulaValue> values, String separator) {
        List<String> text = new ArrayList<>(values.size());
        for (FormulaValue value : values) text.add(toText(value));
        return String.join(separator, text);
    }

    @SuppressWarnings("unchecked")
    static List<FormulaValue> castList(Object value) {
        return (List<FormulaValue>) value;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toUpperCase(Locale.ROOT);
    }
}
