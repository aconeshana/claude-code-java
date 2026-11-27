package com.claudecode.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Replaces {@code sb.append(str.repeat(count))} on {@link StringBuilder} and {@link StringBuffer}
 * with Java 21+ {@code sb.repeat(...)} when {@code str} is guaranteed non-null at compile time.
 *
 * <p>Restricting to compile-time non-null receivers (string literals and string concatenations)
 * avoids the {@code null} semantic difference between {@code str.repeat(n)} (throws
 * {@link NullPointerException}) and {@code sb.repeat(null, n)} (appends {@code "null"} {@code n}
 * times). Single-character printable BMP string literals are further simplified to {@code char}
 * literals so the {@code repeat(int codePoint, int count)} overload is used directly.
 */
public final class UseStringBuilderRepeatSafely extends Recipe {

    private static final String STRING_BUILDER = "java.lang.StringBuilder";
    private static final String STRING_BUFFER = "java.lang.StringBuffer";

    @Override
    public String getDisplayName() {
        return "Use StringBuilder.repeat() for guaranteed non-null String.repeat()";
    }

    @Override
    public String getDescription() {
        return "Replaces StringBuilder/StringBuffer.append(str.repeat(count)) with repeat(str, count) "
            + "(or repeat(char, count) for single-character literals) when str is provably non-null.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            new UsesMethod<>("java.lang.String repeat(int)"),
            new JavaIsoVisitor<>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(
                        J.MethodInvocation method, ExecutionContext ctx) {
                    J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
                    RepeatMatch match = matchAppendRepeat(visited);
                    if (match == null) {
                        return visited;
                    }

                    J.MethodInvocation replaced;
                    if (match.charLiteralSource() != null) {
                        replaced = JavaTemplate.builder(
                                "#{any(" + match.receiverTypeFqn() + ")}.repeat("
                                    + match.charLiteralSource() + ", #{any(int)})")
                            .build()
                            .apply(
                                updateCursor(visited),
                                visited.getCoordinates().replace(),
                                match.receiver(),
                                match.countExpr());
                    } else {
                        replaced = JavaTemplate.builder(
                                "#{any(" + match.receiverTypeFqn() + ")}.repeat("
                                    + "#{any(java.lang.CharSequence)}, #{any(int)})")
                            .build()
                            .apply(
                                updateCursor(visited),
                                visited.getCoordinates().replace(),
                                match.receiver(),
                                match.repeatedExpr(),
                                match.countExpr());
                    }

                    return visited
                        .withName(replaced.getName().withPrefix(visited.getName().getPrefix()))
                        .getPadding()
                        .withArguments(replaced.getPadding().getArguments())
                        .withMethodType(replaced.getMethodType());
                }
            });
    }

    private static RepeatMatch matchAppendRepeat(J.MethodInvocation outer) {
        if (!"append".equals(outer.getSimpleName())
                || outer.getSelect() == null
                || outer.getArguments().size() != 1) {
            return null;
        }
        String receiverTypeFqn = builderTypeFqn(outer.getSelect().getType());
        if (receiverTypeFqn == null) {
            return null;
        }
        JavaType.Method outerMethod = outer.getMethodType();
        if (outerMethod == null || outerMethod.getParameterTypes().size() != 1) {
            return null;
        }

        Expression unwrappedArg = unwrapParens(outer.getArguments().getFirst());
        if (!(unwrappedArg instanceof J.MethodInvocation inner)
                || !"repeat".equals(inner.getSimpleName())
                || inner.getSelect() == null
                || inner.getArguments().size() != 1) {
            return null;
        }
        JavaType.Method innerMethod = inner.getMethodType();
        if (innerMethod == null
                || !TypeUtils.isOfClassType(innerMethod.getDeclaringType(), "java.lang.String")) {
            return null;
        }

        Expression repeatedExpr = unwrapParens(inner.getSelect());
        if (!isProvablyNonNullString(repeatedExpr)) {
            return null;
        }

        String charLiteralSource = singleCharLiteralSource(repeatedExpr);
        Expression countExpr = unwrapParens(inner.getArguments().getFirst());
        return new RepeatMatch(
            outer.getSelect(),
            receiverTypeFqn,
            repeatedExpr,
            charLiteralSource,
            countExpr);
    }

    private static String builderTypeFqn(JavaType type) {
        if (TypeUtils.isOfClassType(type, STRING_BUILDER)) {
            return STRING_BUILDER;
        }
        if (TypeUtils.isOfClassType(type, STRING_BUFFER)) {
            return STRING_BUFFER;
        }
        return null;
    }

    private static boolean isProvablyNonNullString(Expression expr) {
        if (expr instanceof J.Literal literal) {
            return literal.getValue() instanceof String;
        }
        if (expr instanceof J.Binary binary && binary.getOperator() == J.Binary.Type.Addition) {
            return TypeUtils.isString(binary.getType());
        }
        return false;
    }

    private static String singleCharLiteralSource(Expression expr) {
        if (!(expr instanceof J.Literal literal) || !(literal.getValue() instanceof String str)) {
            return null;
        }
        if (str.length() != 1) {
            return null;
        }
        char c = str.charAt(0);
        if (Character.isSurrogate(c)) {
            return null;
        }
        return switch (c) {
            case '\'' -> "'\\''";
            case '\\' -> "'\\\\'";
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            case '\b' -> "'\\b'";
            case '\f' -> "'\\f'";
            default -> {
                if (c < 0x20 || Character.isISOControl(c)) {
                    yield null;
                }
                yield "'" + c + "'";
            }
        };
    }

    private static Expression unwrapParens(Expression expr) {
        Expression cur = expr;
        while (cur instanceof J.Parentheses<?> parens && parens.getTree() instanceof Expression inner) {
            cur = inner;
        }
        return cur;
    }

    private record RepeatMatch(
        Expression receiver,
        String receiverTypeFqn,
        Expression repeatedExpr,
        String charLiteralSource,
        Expression countExpr
    ) {}
}
