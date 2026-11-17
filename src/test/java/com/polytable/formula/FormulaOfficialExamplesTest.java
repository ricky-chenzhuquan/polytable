package com.polytable.formula;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Focused expected-value checks copied from the frozen public catalog examples. */
class FormulaOfficialExamplesTest {

    private final FormulaParser parser = new FormulaParser();
    private final FormulaEvaluator evaluator = new FormulaEvaluator(new FormulaFunctionRegistry());
    private final FormulaEvaluationContext context = new FormulaEvaluationContext(Map.of(), Map.of(),
            ZoneId.of("Asia/Shanghai"), Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @Test
    void evaluatesOfficialBooleanAndBranchExamples() {
        expect("AND(1=1,1=2)", FormulaValue.bool(false));
        expect("CONTAIN(LIST(\"韩国\", \"日本\"), \"韩国\", \"日本\")", FormulaValue.bool(true));
        expect("FALSE()", FormulaValue.bool(false));
        expect("IF(1=2, \"相同\", \"不相同\")", FormulaValue.text("不相同"));
        expect("IFBLANK(\"\", \"未填写\")", FormulaValue.text("未填写"));
        expect("NOT(TRUE)", FormulaValue.bool(false));
        expect("OR(1=2, 1=1)", FormulaValue.bool(true));
        expect("TRUE()", FormulaValue.bool(true));
        expect("ISNUMBER(1)", FormulaValue.bool(true));
        expect("ISNUMBER(\"a\")", FormulaValue.bool(false));
        expect("SWITCH(2,1,\"周日\",2,\"周一\",3,\"周二\",\"无\")", FormulaValue.text("周一"));
    }

    @Test
    void evaluatesOfficialTextExamples() {
        expect("CHAR(10)", FormulaValue.text("\n"));
        expect("CONCATENATE(\"多维\", \"表格\")", FormulaValue.text("多维表格"));
        expect("CONTAINTEXT(\"多维表格\", \"表格\")", FormulaValue.bool(true));
        expect("FIND(\"维\", \"多维表格\")", decimal("2"));
        expect("LEFT(\"多维表格\", 2)", FormulaValue.text("多维"));
        expect("LEN(\"多维表格\")", decimal("4"));
        expect("LOWER(\"ABC\")", FormulaValue.text("abc"));
        expect("MID(\"多维表格\", 1, 2)", FormulaValue.text("多维"));
        expect("REPLACE(\"abcdefg\", 1, 6, \"xyz\")", FormulaValue.text("xyzg"));
        expect("RIGHT(\"多维表格\", 2)", FormulaValue.text("表格"));
        expect("SUBSTITUTE(\"abcdefg\", \"cde\", \"xyz\", 1)", FormulaValue.text("abxyzfg"));
        expect("TRIM(\" abc abc \")", FormulaValue.text("abc abc"));
        expect("UPPER(\"abc\")", FormulaValue.text("ABC"));
        expect("ARRAYJOIN(LIST(\"A\",\"B\",\"C\"),\"-\")", FormulaValue.text("A-B-C"));
    }

    @Test
    void evaluatesOfficialNumericExamples() {
        expect("ABS(-2)", decimal("2"));
        expect("AVERAGE(2, 100)", decimal("51"));
        expect("CEILING(3.21, 0.2)", decimal("3.4"));
        expect("FLOOR(3.14, 0.1)", decimal("3.1"));
        expect("INT(8.9)", decimal("8"));
        expect("LARGE(LIST(1,2,3), 2)", decimal("2"));
        expect("LOG(100, 10)", decimal("2"));
        expect("MAX(2,100)", decimal("100"));
        expect("MEDIAN(1,2,3,4)", decimal("2.5"));
        expect("MIN(2,100)", decimal("2"));
        expect("MOD(3,2)", decimal("1"));
        expect("POWER(5,2)", decimal("25"));
        expect("PRODUCT(2,3,4)", decimal("24"));
        expect("QUOTIENT(5,2)", decimal("2"));
        expect("ROUND(99.45,1)", decimal("99.5"));
        expect("ROUNDDOWN(99.45,1)", decimal("99.4"));
        expect("ROUNDUP(99.44,1)", decimal("99.5"));
        expect("SMALL(LIST(1,2,3),2)", decimal("2"));
        expect("SQRT(16)", decimal("4"));
        expect("SUM(1,2)", decimal("3"));
        expect("VALUE(\"$50\")", decimal("50"));
    }

    @Test
    void evaluatesOfficialDateExamplesAgainstFixedClock() {
        expect("DATE(2000,1,1)", FormulaValue.dateTime(LocalDate.of(2000, 1, 1)));
        expect("DATEDIF(\"2000-01-01\",\"2000-01-08\",\"D\")", decimal("7"));
        expect("DAY(\"2000-01-03\")", decimal("3"));
        expect("DAYS(\"2000-01-08\",\"2000-01-01\")", decimal("7"));
        expect("EDATE(\"2011/01/31\",1)", FormulaValue.dateTime(LocalDate.of(2011, 2, 28)));
        expect("EOMONTH(\"2000-1-1\",1)", FormulaValue.dateTime(LocalDate.of(2000, 2, 29)));
        expect("HOUR(\"11:40:59\")", decimal("11"));
        expect("MINUTE(\"11:40:59\")", decimal("40"));
        expect("MONTH(\"2000-12-01\")", decimal("12"));
        expect("NETWORKDAYS(\"2000-01-01\",\"2000-01-12\")", decimal("8"));
        expect("NOW()", FormulaValue.dateTime(LocalDateTime.of(2000, 1, 1, 8, 0)));
        expect("SECOND(\"11:40:59\")", decimal("59"));
        expect("TODAY()", FormulaValue.dateTime(LocalDate.of(2000, 1, 1)));
        expect("TODATE(\"2023-6-5\")", FormulaValue.dateTime(LocalDate.of(2023, 6, 5)));
        expect("WEEKDAY(\"2000-01-01\")", decimal("7"));
        expect("WEEKNUM(\"2000-01-01\")", decimal("1"));
        expect("WORKDAY(\"2000/01/01\",7)", FormulaValue.dateTime(LocalDate.of(2000, 1, 11)));
        expect("YEAR(\"2000-01-01\")", decimal("2000"));
        expect("NOW()+DURATION(0,12)", FormulaValue.dateTime(LocalDateTime.of(2000, 1, 1, 20, 0)));
        expect("TEXT(\"2021-01-01\",\"ddd\")", FormulaValue.text("周五"));
    }

    @Test
    void evaluatesOfficialErrorCollectionAndBindingExamples() {
        expect("IFERROR(1/0,\"错误\")", FormulaValue.text("错误"));
        expect("IFS(80>=80,\"优秀\",80>=60,\"及格\",TRUE,\"不及格\")", FormulaValue.text("优秀"));
        expect("ISBLANK(\"\")", FormulaValue.bool(true));
        expect("ISERROR(1/0)", FormulaValue.bool(true));
        expectWithContext("ISNULL([字段1])", FormulaValue.bool(true),
                new FormulaEvaluationContext(Map.of("字段1", FormulaValue.blank()), Map.of(),
                        ZoneId.of("Asia/Shanghai"), context.clock()));
        expect("LET(单价, 12, 数量, 3, 单价 * 数量)", decimal("36"));
        expect("MAP(LIST(1,2,3), CurrentValue * 2)",
                FormulaValue.list(List.of(decimal("2"), decimal("4"), decimal("6"))));
        expect("RANK(3, LIST(4,3,2,1), TRUE)", decimal("3"));
        expect("LIST(\"微观经济学\",\"高等数学\",\"大学英语\").CONTAINSALL(\"微观经济学\",\"高等数学\",\"大学英语\")",
                FormulaValue.bool(true));
        expect("LIST(\"A\",\"B\",\"C\").CONTAINSONLY(\"A\",\"B\",\"C\")", FormulaValue.bool(true));
        expect("COUNTA(LIST(\"a\",\"b\",\"c\"))", decimal("3"));
        expect("COUNTIF(LIST(1,2,3), CurrentValue > 1)", decimal("2"));
        expect("SUMIF(LIST(1,2,3), CurrentValue > 1)", decimal("5"));
        expect("FILTER(LIST(1,2,3), CurrentValue > 1)", FormulaValue.list(List.of(decimal("2"), decimal("3"))));
        expect("LOOKUP(\"b\", LIST(\"a\",\"b\"), LIST(1,2))", decimal("2"));
        expect("SORTBY(LIST(3,1,2), CurrentValue)", FormulaValue.list(List.of(decimal("1"), decimal("2"), decimal("3"))));
        expect("SEQUENCE(1,10,2)", FormulaValue.list(List.of(decimal("1"), decimal("3"), decimal("5"), decimal("7"), decimal("9"))));
        FormulaValue random = evaluate("RANDOMBETWEEN(1,10)");
        assertEquals(FormulaValue.Kind.DECIMAL, random.kind());
        assertEquals(true, random.asDecimal().compareTo(BigDecimal.ONE) >= 0 && random.asDecimal().compareTo(BigDecimal.TEN) <= 0);
        FormulaValue randomItem = evaluate("LIST(\"炸鸡\",\"牛肉面\",\"披萨\").RANDOMITEM()");
        assertEquals(true, List.of("炸鸡", "牛肉面", "披萨").contains(randomItem.asText()));
        FormulaValue randomArray = evaluate("RANDOMARRAY(3,1,10,TRUE,FALSE,FALSE)");
        List<FormulaValue> randomValues = FormulaFunctionRegistry.castList(randomArray.rawValue());
        assertEquals(3, randomValues.size());
        assertEquals(3, randomValues.stream().map(FormulaValue::rawValue).distinct().count());
        assertEquals(true, randomValues.stream().allMatch(value -> value.asDecimal().compareTo(BigDecimal.ONE) >= 0
                && value.asDecimal().compareTo(BigDecimal.TEN) <= 0));
    }

    @Test
    void evaluatesOfficialFormattingAndRegularExpressionExamples() {
        expect("HYPERLINK(\"https://example.com\",\"多维表格\")", FormulaValue.text("多维表格"));
        expect("SPLIT(\"a,b,c\",\",\")", FormulaValue.list(List.of(
                FormulaValue.text("a"), FormulaValue.text("b"), FormulaValue.text("c"))));
        expect("ENCODEURL(\"多维表格\")", FormulaValue.text("%E5%A4%9A%E7%BB%B4%E8%A1%A8%E6%A0%BC"));
        expect("FORMAT(\"{1}同学今年的{2}成绩是{3}分\",\"小明\",\"数学\",95)",
                FormulaValue.text("小明同学今年的数学成绩是95分"));
        expect("REGEXEXTRACT(\"小明的电话号码是 13812345678\",\"\\\\d{11}\")", FormulaValue.text("13812345678"));
        expect("REGEXEXTRACTALL(\"13812345678,13987654321\",\"\\\\d{11}\")",
                FormulaValue.list(List.of(FormulaValue.text("13812345678"), FormulaValue.text("13987654321"))));
        expect("REGEXMATCH(\"13812345678\",\"^\\\\d{11}$\")", FormulaValue.bool(true));
        expect("REGEXREPLACE(\"小明 13812345678\",\"\\\\d{11}\",\"***********\")",
                FormulaValue.text("小明 ***********"));
        expect("SORT(LIST(1,2,3), TRUE).FIRST()", decimal("1"));
    }

    @Test
    void evaluatesOfficialRemainingNumericAndLocationExamples() {
        expect("ACOSH(1)", decimal("0"));
        expect("ASINH(10)", decimal("2.99822295"));
        expect("ISODD(1)", FormulaValue.bool(true));
        expect("STDEV(1,2,3)", decimal("1"));
        expect("TANH(0)", decimal("0"));
        expect("LN(EXP(1))", decimal("1"));
        expect("EXP(1)", decimal("2.718281828"));
        expect("SIN(PI())", decimal("0"));
        expect("PI()", decimal("3.141592654"));
        expect("ACOS(-0.5)*180/PI()", decimal("120"));
        expect("ASIN(-0.5)*180/PI()", decimal("-30"));
        expect("ATAN(1)*180/PI()", decimal("45"));
        expect("ATAN2(-1,-1)*180/PI()", decimal("-135"));
        expect("ATANH(0.76159416)", decimal("1.00000001"));
        expect("COSH(4)", decimal("27.30823284"));
        expect("2.868*SINH(0.0342*1.03)", decimal("0.101049063"));
        expect("TAN(45*PI()/180)", decimal("1"));
        expect("ISODD(2)", FormulaValue.bool(false));
        expect("LIST(1,2,3,4,5).LISTCOMBINE(LIST(2,3,4))",
                FormulaValue.list(List.of(decimal("1"), decimal("2"), decimal("3"), decimal("4"), decimal("5"),
                        decimal("2"), decimal("3"), decimal("4"))));

        FormulaEvaluationContext locations = new FormulaEvaluationContext(Map.of(
                "origin", FormulaValue.recordReference(Map.of("latitude", 0D, "longitude", 0D)),
                "destination", FormulaValue.recordReference(Map.of("latitude", 0D, "longitude", 1D))), Map.of(),
                ZoneId.of("Asia/Shanghai"), context.clock());
        FormulaValue distance = evaluator.evaluate(parser.parse("DISTANCE([origin],[destination])"), locations);
        assertEquals(FormulaValue.Kind.DECIMAL, distance.kind());
        assertEquals(0, BigDecimal.valueOf(111.195).compareTo(distance.asDecimal().setScale(3, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void evaluatesOfficialListExamples() {
        expect("FIRST(LIST(1,3,5,7))", decimal("1"));
        expect("LAST(LIST(1,3,5,7))", decimal("7"));
        expect("LIST(1,2,3)", FormulaValue.list(List.of(decimal("1"), decimal("2"), decimal("3"))));
        expect("LIST(1,2,3,4,5).LISTCOMBINE(2,3,4)",
                FormulaValue.list(List.of(decimal("1"), decimal("2"), decimal("3"), decimal("4"), decimal("5"),
                        decimal("2"), decimal("3"), decimal("4"))));
        expect("NTH(LIST(1,3,5,7),2)", decimal("3"));
        expect("SORT(LIST(5,2,3,1,4), FALSE)",
                FormulaValue.list(List.of(decimal("5"), decimal("4"), decimal("3"), decimal("2"), decimal("1"))));
        expect("UNIQUE(1,2,2,4,5,5)",
                FormulaValue.list(List.of(decimal("1"), decimal("2"), decimal("4"), decimal("5"))));
    }

    private void expect(String expression, FormulaValue expected) {
        FormulaValue actual = evaluator.evaluate(parser.parse(expression), context);
        assertEquals(expected.kind(), actual.kind(), expression);
        if (expected.kind() == FormulaValue.Kind.DECIMAL) {
            assertEquals(0, expected.asDecimal().compareTo(actual.asDecimal().setScale(expected.asDecimal().scale(),
                    java.math.RoundingMode.HALF_UP)), expression);
        } else {
            assertEquals(expected, actual, expression);
        }
    }

    private FormulaValue evaluate(String expression) {
        return evaluator.evaluate(parser.parse(expression), context);
    }

    private void expectWithContext(String expression, FormulaValue expected, FormulaEvaluationContext evaluationContext) {
        FormulaValue actual = evaluator.evaluate(parser.parse(expression), evaluationContext);
        assertEquals(expected, actual, expression);
    }

    private static FormulaValue decimal(String value) {
        return FormulaValue.decimal(new BigDecimal(value));
    }
}
