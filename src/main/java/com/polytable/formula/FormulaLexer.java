package com.polytable.formula;

import java.util.ArrayList;
import java.util.List;

final class FormulaLexer {

    enum Type {
        NUMBER, STRING, IDENTIFIER, FIELD, OPERATOR, LEFT_PAREN, RIGHT_PAREN, COMMA, DOT, END
    }

    record Token(Type type, String text, int offset) {
    }

    private final String source;
    private int offset;

    FormulaLexer(String source) {
        this.source = source;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (offset < source.length()) {
            char current = source.charAt(offset);
            if (Character.isWhitespace(current)) {
                offset++;
            } else if (current == '"') {
                tokens.add(readString());
            } else if (current == '[') {
                tokens.add(readField());
            } else if (Character.isDigit(current) || (current == '.' && hasNextDigit())) {
                tokens.add(readNumber());
            } else if (isIdentifierStart(current)) {
                tokens.add(readIdentifier());
            } else {
                tokens.add(readPunctuationOrOperator());
            }
        }
        tokens.add(new Token(Type.END, "", offset));
        return List.copyOf(tokens);
    }

    private Token readString() {
        int start = offset++;
        StringBuilder value = new StringBuilder();
        while (offset < source.length()) {
            char current = source.charAt(offset++);
            if (current == '"') return new Token(Type.STRING, value.toString(), start);
            if (current == '\\') {
                if (offset >= source.length()) throw error("unfinished string escape", start);
                char escaped = source.charAt(offset++);
                value.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> escaped;
                });
            } else {
                value.append(current);
            }
        }
        throw error("unclosed string literal", start);
    }

    private Token readField() {
        int start = offset++;
        StringBuilder name = new StringBuilder();
        while (offset < source.length() && source.charAt(offset) != ']') {
            name.append(source.charAt(offset++));
        }
        if (offset >= source.length()) throw error("unclosed field reference", start);
        offset++;
        String field = name.toString().trim();
        if (field.isEmpty()) throw error("empty field reference", start);
        return new Token(Type.FIELD, field, start);
    }

    private Token readNumber() {
        int start = offset;
        boolean decimal = false;
        while (offset < source.length()) {
            char current = source.charAt(offset);
            if (Character.isDigit(current)) {
                offset++;
            } else if (current == '.' && !decimal && offset + 1 < source.length()
                    && Character.isDigit(source.charAt(offset + 1))) {
                decimal = true;
                offset++;
            } else {
                break;
            }
        }
        return new Token(Type.NUMBER, source.substring(start, offset), start);
    }

    private Token readIdentifier() {
        int start = offset++;
        while (offset < source.length() && isIdentifierPart(source.charAt(offset))) offset++;
        return new Token(Type.IDENTIFIER, source.substring(start, offset), start);
    }

    private Token readPunctuationOrOperator() {
        int start = offset;
        char current = source.charAt(offset++);
        switch (current) {
            case '(' -> { return new Token(Type.LEFT_PAREN, "(", start); }
            case '（' -> { return new Token(Type.LEFT_PAREN, "(", start); }
            case ')' -> { return new Token(Type.RIGHT_PAREN, ")", start); }
            case '）' -> { return new Token(Type.RIGHT_PAREN, ")", start); }
            case ',' -> { return new Token(Type.COMMA, ",", start); }
            case '，' -> { return new Token(Type.COMMA, ",", start); }
            case '.' -> { return new Token(Type.DOT, ".", start); }
            default -> { }
        }
        if (offset < source.length()) {
            String pair = "" + current + source.charAt(offset);
            if (pair.equals("&&") || pair.equals("||") || pair.equals(">=")
                    || pair.equals("<=") || pair.equals("!=")) {
                offset++;
                return new Token(Type.OPERATOR, pair, start);
            }
        }
        if ("+-*/%&><=".indexOf(current) >= 0) return new Token(Type.OPERATOR, String.valueOf(current), start);
        throw error("unsupported character '" + current + "'", start);
    }

    private boolean hasNextDigit() {
        return offset + 1 < source.length() && Character.isDigit(source.charAt(offset + 1));
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean isIdentifierPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }

    private static IllegalArgumentException error(String message, int at) {
        return new IllegalArgumentException(message + " at offset " + at);
    }
}
