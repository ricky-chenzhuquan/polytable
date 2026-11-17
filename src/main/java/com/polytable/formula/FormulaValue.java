package com.polytable.formula;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class FormulaValue {

    public enum Kind {
        BLANK, BOOLEAN, DECIMAL, TEXT, DATE_TIME, LIST, RECORD_REFERENCE, ERROR
    }

    private final Kind kind;
    private final Object rawValue;
    private final String errorCode;
    private final String errorMessage;

    private FormulaValue(Kind kind, Object rawValue, String errorCode, String errorMessage) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.rawValue = rawValue;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static FormulaValue blank() {
        return new FormulaValue(Kind.BLANK, null, null, null);
    }

    public static FormulaValue bool(boolean value) {
        return new FormulaValue(Kind.BOOLEAN, value, null, null);
    }

    public static FormulaValue decimal(BigDecimal value) {
        return new FormulaValue(Kind.DECIMAL, Objects.requireNonNull(value, "value"), null, null);
    }

    public static FormulaValue text(String value) {
        return new FormulaValue(Kind.TEXT, Objects.requireNonNull(value, "value"), null, null);
    }

    public static FormulaValue dateTime(Object value) {
        return new FormulaValue(Kind.DATE_TIME, Objects.requireNonNull(value, "value"), null, null);
    }

    public static FormulaValue list(List<FormulaValue> values) {
        return new FormulaValue(Kind.LIST, List.copyOf(values), null, null);
    }

    public static FormulaValue recordReference(Object reference) {
        return new FormulaValue(Kind.RECORD_REFERENCE, Objects.requireNonNull(reference, "reference"), null, null);
    }

    public static FormulaValue error(String code, String message) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("error code cannot be blank");
        return new FormulaValue(Kind.ERROR, null, code, Objects.requireNonNullElse(message, ""));
    }

    public Kind kind() {
        return kind;
    }

    public Object rawValue() {
        return rawValue;
    }

    public BigDecimal asDecimal() {
        if (kind != Kind.DECIMAL) throw new IllegalStateException("value is not decimal: " + kind);
        return (BigDecimal) rawValue;
    }

    public String asText() {
        if (kind != Kind.TEXT) throw new IllegalStateException("value is not text: " + kind);
        return (String) rawValue;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FormulaValue that)) return false;
        return kind == that.kind && Objects.equals(rawValue, that.rawValue)
                && Objects.equals(errorCode, that.errorCode) && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, rawValue, errorCode, errorMessage);
    }

    @Override
    public String toString() {
        return kind == Kind.ERROR ? errorCode + ": " + errorMessage : kind + "(" + rawValue + ")";
    }
}
