package com.polytable.formula;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FormulaValueTest {

    @Test
    void representsScalarAndBlankValuesWithExplicitKinds() {
        assertEquals(FormulaValue.Kind.BLANK, FormulaValue.blank().kind());
        assertEquals(Boolean.TRUE, FormulaValue.bool(true).rawValue());
        assertEquals(FormulaValue.Kind.BOOLEAN, FormulaValue.bool(true).kind());
        assertEquals(new BigDecimal("12.50"), FormulaValue.decimal(new BigDecimal("12.50")).asDecimal());
        assertEquals("文本", FormulaValue.text("文本").asText());
    }

    @Test
    void preservesErrorCodeAndMessageInsteadOfCoercingToBlank() {
        FormulaValue error = FormulaValue.error("#N/A", "No matching condition");

        assertEquals(FormulaValue.Kind.ERROR, error.kind());
        assertEquals("#N/A", error.errorCode());
        assertNotNull(error.errorMessage());
    }
}
