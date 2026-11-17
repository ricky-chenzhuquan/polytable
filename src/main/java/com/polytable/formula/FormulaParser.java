package com.polytable.formula;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.polytable.formula.FormulaLexer.Type;
import static com.polytable.formula.FormulaLexer.Token;

public final class FormulaParser {

    public FormulaAst.Expression parse(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("formula cannot be blank");
        Parser parser = new Parser(expression, new FormulaLexer(expression).tokenize());
        FormulaAst.Expression result = parser.parseExpression(0);
        parser.expect(Type.END, "end of formula");
        return result;
    }

    private static final class Parser {
        private final String source;
        private final List<Token> tokens;
        private int index;

        private Parser(String source, List<Token> tokens) {
            this.source = source;
            this.tokens = tokens;
        }

        private FormulaAst.Expression parseExpression(int minPrecedence) {
            FormulaAst.Expression left = parsePrefix();
            left = parsePostfix(left);
            while (peek().type() == Type.OPERATOR && precedence(peek().text()) >= minPrecedence) {
                Token operator = advance();
                int nextMin = precedence(operator.text()) + 1;
                FormulaAst.Expression right = parseExpression(nextMin);
                left = new FormulaAst.Binary(operator.text(), left, right);
            }
            return left;
        }

        private FormulaAst.Expression parsePrefix() {
            Token token = advance();
            return switch (token.type()) {
                case NUMBER -> new FormulaAst.Literal(FormulaValue.decimal(new BigDecimal(token.text())));
                case STRING -> new FormulaAst.Literal(FormulaValue.text(token.text()));
                case FIELD -> new FormulaAst.FieldReference(token.text());
                case IDENTIFIER -> parseIdentifier(token);
                case LEFT_PAREN -> {
                    FormulaAst.Expression nested = parseExpression(0);
                    expect(Type.RIGHT_PAREN, "')'");
                    yield nested;
                }
                case OPERATOR -> {
                    if (!token.text().equals("+") && !token.text().equals("-")) {
                        throw error("expected a value before '" + token.text() + "'", token);
                    }
                    yield new FormulaAst.Unary(token.text(), parseExpression(6));
                }
                default -> throw error("expected a value", token);
            };
        }

        private FormulaAst.Expression parseIdentifier(Token token) {
            String upper = token.text().toUpperCase(Locale.ROOT);
            // TRUE/FALSE are values when bare, but public formulas also expose them as zero-argument functions.
            if (peek().type() == Type.LEFT_PAREN) return new FormulaAst.Name(token.text());
            if (upper.equals("TRUE")) return new FormulaAst.Literal(FormulaValue.bool(true));
            if (upper.equals("FALSE")) return new FormulaAst.Literal(FormulaValue.bool(false));
            return new FormulaAst.Name(token.text());
        }

        private FormulaAst.Expression parsePostfix(FormulaAst.Expression expression) {
            FormulaAst.Expression value = expression;
            while (true) {
                if (match(Type.LEFT_PAREN)) {
                    value = new FormulaAst.Call(value, parseArguments());
                } else if (match(Type.DOT)) {
                    Token member = advance();
                    if (member.type() != Type.IDENTIFIER && member.type() != Type.FIELD) {
                        throw error("expected member name after '.'", member);
                    }
                    String memberName = member.text();
                    FormulaAst.Member access = new FormulaAst.Member(value, memberName);
                    value = match(Type.LEFT_PAREN)
                            ? new FormulaAst.Call(access, parseArguments())
                            : access;
                } else {
                    return value;
                }
            }
        }

        private List<FormulaAst.Expression> parseArguments() {
            List<FormulaAst.Expression> arguments = new ArrayList<>();
            if (match(Type.RIGHT_PAREN)) return arguments;
            do {
                arguments.add(parseExpression(0));
            } while (match(Type.COMMA));
            expect(Type.RIGHT_PAREN, "')'");
            return List.copyOf(arguments);
        }

        private boolean match(Type type) {
            if (peek().type() != type) return false;
            advance();
            return true;
        }

        private Token expect(Type type, String expected) {
            Token token = advance();
            if (token.type() != type) throw error("expected " + expected, token);
            return token;
        }

        private Token advance() {
            return tokens.get(index++);
        }

        private Token peek() {
            return tokens.get(index);
        }

        private IllegalArgumentException error(String message, Token token) {
            return new IllegalArgumentException(message + " at offset " + token.offset() + " in formula: " + source);
        }

        private static int precedence(String operator) {
            return switch (operator) {
                case "||" -> 1;
                case "&&" -> 2;
                case "=", "!=", ">", ">=", "<", "<=" -> 3;
                case "+", "-", "&" -> 4;
                case "*", "/", "%" -> 5;
                default -> -1;
            };
        }
    }
}
