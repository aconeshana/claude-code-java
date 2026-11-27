package com.claudecode.recipes;

import java.util.List;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Replaces nested {@code Math.max(min, Math.min(max, x))} and
 * {@code Math.min(max, Math.max(min, x))} expressions with Java 21+ {@code Math.clamp(x, min, max)}
 * when both bounds are compile-time integer/long literals satisfying {@code min <= max}.
 *
 * <p>Restricting to literal {@code int}/{@code long} bounds with {@code min <= max} avoids two
 * semantic hazards of mechanical replacement:
 * <ul>
 *   <li>Dynamic bounds where {@code min > max} at runtime would throw
 *       {@link IllegalArgumentException} in {@code Math.clamp}, whereas nested {@code max}/{@code min}
 *       silently picks the outer bound.</li>
 *   <li>Floating-point {@code NaN} / {@code -0.0} edge cases in {@code double}/{@code float}.</li>
 * </ul>
 */
public final class UseMathClampSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use Math.clamp for literal-bounded int/long clamping";
    }

    @Override
    public String getDescription() {
        return "Replaces nested Math.max/Math.min calls with Math.clamp(value, min, max) when "
            + "both bounds are int or long literals with min <= max.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(
                    J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
                ClampMatch match = matchLiteralClamp(visited);
                if (match == null) {
                    return visited;
                }
                maybeRemoveImport("java.lang.Math.max");
                maybeRemoveImport("java.lang.Math.min");
                return JavaTemplate.builder("Math.clamp(#{any()}, #{any()}, #{any()})")
                    .build()
                    .apply(updateCursor(visited), visited.getCoordinates().replace(),
                        match.value(), match.minExpr(), match.maxExpr());
            }
        };
    }

    private static ClampMatch matchLiteralClamp(J.MethodInvocation outer) {
        String outerOp = mathMinOrMax(outer);
        if (outerOp == null) {
            return null;
        }
        JavaType outerType = outer.getType();
        boolean isInt = outerType == JavaType.Primitive.Int;
        boolean isLong = outerType == JavaType.Primitive.Long;
        if (!isInt && !isLong) {
            return null;
        }
        String innerExpected = "max".equals(outerOp) ? "min" : "max";
        List<Expression> outerArgs = outer.getArguments();
        if (outerArgs.size() != 2) {
            return null;
        }

        ClampMatch first = tryPair(
            outerOp, innerExpected, outerArgs.get(0), outerArgs.get(1), isInt, isLong);
        if (first != null) {
            return first;
        }
        return tryPair(
            outerOp, innerExpected, outerArgs.get(1), outerArgs.get(0), isInt, isLong);
    }

    private static ClampMatch tryPair(
            String outerOp,
            String innerExpected,
            Expression outerBoundCandidate,
            Expression innerCallCandidate,
            boolean isInt,
            boolean isLong) {
        Long outerBoundVal = extractIntegralLiteral(outerBoundCandidate, isInt, isLong);
        if (outerBoundVal == null) {
            return null;
        }
        Expression unwrappedInner = unwrapParens(innerCallCandidate);
        if (!(unwrappedInner instanceof J.MethodInvocation inner)
                || !innerExpected.equals(mathMinOrMax(inner))) {
            return null;
        }
        List<Expression> innerArgs = inner.getArguments();
        if (innerArgs.size() != 2) {
            return null;
        }

        ClampMatch leftBound = tryInnerPair(
            outerOp, outerBoundCandidate, outerBoundVal,
            innerArgs.get(0), innerArgs.get(1), isInt, isLong);
        if (leftBound != null) {
            return leftBound;
        }
        return tryInnerPair(
            outerOp, outerBoundCandidate, outerBoundVal,
            innerArgs.get(1), innerArgs.get(0), isInt, isLong);
    }

    private static ClampMatch tryInnerPair(
            String outerOp,
            Expression outerBoundExpr,
            long outerBoundVal,
            Expression innerBoundCandidate,
            Expression valueCandidate,
            boolean isInt,
            boolean isLong) {
        Long innerBoundVal = extractIntegralLiteral(innerBoundCandidate, isInt, isLong);
        if (innerBoundVal == null) {
            return null;
        }
        JavaType valueType = valueCandidate.getType();
        if (isInt && valueType != JavaType.Primitive.Int) {
            return null;
        }
        if (isLong && valueType != JavaType.Primitive.Long && valueType != JavaType.Primitive.Int) {
            return null;
        }

        Expression minExpr = "max".equals(outerOp) ? outerBoundExpr : innerBoundCandidate;
        Expression maxExpr = "max".equals(outerOp) ? innerBoundCandidate : outerBoundExpr;
        long minVal = "max".equals(outerOp) ? outerBoundVal : innerBoundVal;
        long maxVal = "max".equals(outerOp) ? innerBoundVal : outerBoundVal;
        if (minVal > maxVal) {
            return null;
        }
        return new ClampMatch(unwrapParens(valueCandidate), unwrapParens(minExpr), unwrapParens(maxExpr));
    }

    private static String mathMinOrMax(J.MethodInvocation call) {
        String name = call.getSimpleName();
        if (!"min".equals(name) && !"max".equals(name)) {
            return null;
        }
        JavaType.Method mt = call.getMethodType();
        if (mt == null || !TypeUtils.isOfClassType(mt.getDeclaringType(), "java.lang.Math")) {
            return null;
        }
        return name;
    }

    private static Long extractIntegralLiteral(Expression expr, boolean requireInt, boolean requireLong) {
        Expression target = unwrapParens(expr);
        boolean negated = false;
        if (target instanceof J.Unary unary) {
            if (unary.getOperator() == J.Unary.Type.Negative) {
                negated = true;
                target = unwrapParens(unary.getExpression());
            } else if (unary.getOperator() == J.Unary.Type.Positive) {
                target = unwrapParens(unary.getExpression());
            } else {
                return null;
            }
        }
        if (!(target instanceof J.Literal literal) || literal.getValue() == null) {
            return null;
        }
        if (requireInt && literal.getType() == JavaType.Primitive.Int
                && literal.getValue() instanceof Number num) {
            return negated ? -num.longValue() : num.longValue();
        }
        if (requireLong && literal.getType() == JavaType.Primitive.Long
                && literal.getValue() instanceof Number num) {
            return negated ? -num.longValue() : num.longValue();
        }
        return null;
    }

    private static Expression unwrapParens(Expression expr) {
        Expression cur = expr;
        while (cur instanceof J.Parentheses<?> parens && parens.getTree() instanceof Expression inner) {
            cur = inner;
        }
        return cur;
    }

    private record ClampMatch(Expression value, Expression minExpr, Expression maxExpr) {}
}
