package com.polytable.formula;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormulaParserTest {

    private final FormulaParser parser = new FormulaParser();

    @Test
    void parsesFunctionArgumentsAndArithmeticPrecedence() {
        FormulaAst.Expression expression = parser.parse("IF([销售额] >= 2, [销售额] * 3, 0)");

        FormulaAst.Call call = assertInstanceOf(FormulaAst.Call.class, expression);
        assertEquals("IF", ((FormulaAst.Name) call.callee()).identifier());
        assertEquals(3, call.arguments().size());
        FormulaAst.Binary condition = assertInstanceOf(FormulaAst.Binary.class, call.arguments().get(0));
        assertEquals(">=", condition.operator());
        FormulaAst.Binary branch = assertInstanceOf(FormulaAst.Binary.class, call.arguments().get(1));
        assertEquals("*", branch.operator());
        assertEquals(new BigDecimal("3"), ((FormulaAst.Literal) branch.right()).value().asDecimal());
    }

    @Test
    void parsesEscapedTextAndBooleanOperators() {
        FormulaAst.Expression expression = parser.parse("CONCATENATE(\"A\\\"B\", [文本]) & IF(TRUE && NOT(FALSE), \"!\", \"?\")");

        FormulaAst.Binary concat = assertInstanceOf(FormulaAst.Binary.class, expression);
        assertEquals("&", concat.operator());
        FormulaAst.Call call = assertInstanceOf(FormulaAst.Call.class, concat.left());
        FormulaAst.Literal text = assertInstanceOf(FormulaAst.Literal.class, call.arguments().get(0));
        assertEquals("A\"B", text.value().asText());
    }

    @Test
    void parsesBitableTableMethodsAndCurrentValueReferences() {
        FormulaAst.Expression expression = parser.parse(
                "销售总表.FILTER(CurrentValue.[销售额] >= [门槛]).[销售额].SUM()");

        FormulaAst.Call sum = assertInstanceOf(FormulaAst.Call.class, expression);
        FormulaAst.Member sumMethod = assertInstanceOf(FormulaAst.Member.class, sum.callee());
        assertEquals("SUM", sumMethod.member());
        FormulaAst.Member projected = assertInstanceOf(FormulaAst.Member.class, sumMethod.target());
        assertEquals("销售额", projected.member());
    }

    @Test
    void parsesFullWidthPunctuationFromOfficialLocalizedExamples() {
        FormulaAst.Expression expression = parser.parse("FORMAT(\"{1}同学\"，姓名）");
        FormulaAst.Call call = assertInstanceOf(FormulaAst.Call.class, expression);
        assertEquals(2, call.arguments().size());
    }

    @Test
    void rejectsUnclosedCallsAndUnknownCharacters() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("IF(TRUE, 1"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("1 @ 2"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("1 +"));
    }
}
