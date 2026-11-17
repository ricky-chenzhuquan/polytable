package com.polytable.formula;

import java.util.List;
import java.util.Objects;

public final class FormulaAst {

    private FormulaAst() {
    }

    public sealed interface Expression permits Literal, FieldReference, Name, Unary, Binary, Call, Member {
    }

    public record Literal(FormulaValue value) implements Expression {
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    public record FieldReference(String name) implements Expression {
        public FieldReference {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("field reference cannot be blank");
        }
    }

    public record Name(String identifier) implements Expression {
        public Name {
            if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("name cannot be blank");
        }
    }

    public record Unary(String operator, Expression operand) implements Expression {
        public Unary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    public record Binary(String operator, Expression left, Expression right) implements Expression {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    public record Call(Expression callee, List<Expression> arguments) implements Expression {
        public Call {
            Objects.requireNonNull(callee, "callee");
            arguments = List.copyOf(arguments);
        }
    }

    public record Member(Expression target, String member) implements Expression {
        public Member {
            Objects.requireNonNull(target, "target");
            if (member == null || member.isBlank()) throw new IllegalArgumentException("member cannot be blank");
        }
    }
}
