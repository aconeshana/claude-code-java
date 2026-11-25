package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Removes redundant {@code String.valueOf(x)} wrappers in SLF4J {@code Logger} formatting
 * arguments ({@code trace}/{@code debug}/{@code info}/{@code warn}/{@code error}).
 *
 * <p>Preserves calls that are semantically required:
 * <ul>
 *   <li>the format string argument itself (e.g. {@code log.debug(String.valueOf(x))});</li>
 *   <li>a trailing {@code Throwable} argument (e.g. {@code log.debug("cause={}", String.valueOf(t))}),
 *       where unwrapping would make SLF4J treat {@code t} as the logged stack trace instead of
 *       formatting it into the placeholder;</li>
 *   <li>{@code char[]} arguments, where {@code String.valueOf(char[])} constructs a string rather
 *       than formatting an array.</li>
 * </ul>
 */
public final class UnnecessaryStringValueOfInLogging extends Recipe {

    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "error");
    private static final MethodMatcher STRING_VALUE_OF = new MethodMatcher("java.lang.String valueOf(*)");

    @Override
    public String getDisplayName() {
        return "Remove unnecessary String.valueOf() in SLF4J logging arguments";
    }

    @Override
    public String getDescription() {
        return "Unwraps redundant String.valueOf(x) calls passed as SLF4J placeholder arguments.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(
                    J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
                if (!isSlf4jLogCall(mi)) {
                    return mi;
                }
                List<Expression> args = mi.getArguments();
                int firstPlaceholderArg = firstPlaceholderArgIndex(args);
                if (firstPlaceholderArg < 0 || firstPlaceholderArg >= args.size()) {
                    return mi;
                }
                List<Expression> updated = null;
                for (int i = firstPlaceholderArg; i < args.size(); i++) {
                    Expression arg = args.get(i);
                    Expression unwrapped = unwrapSafeStringValueOf(arg, i == args.size() - 1);
                    if (unwrapped != null) {
                        if (updated == null) {
                            updated = new ArrayList<>(args);
                        }
                        updated.set(i, unwrapped);
                    }
                }
                return updated == null ? mi : mi.withArguments(updated);
            }
        };
    }

    private static boolean isSlf4jLogCall(J.MethodInvocation mi) {
        if (!LOG_METHODS.contains(mi.getSimpleName())) {
            return false;
        }
        JavaType.Method mt = mi.getMethodType();
        return mt != null && TypeUtils.isOfClassType(mt.getDeclaringType(), "org.slf4j.Logger");
    }

    private static int firstPlaceholderArgIndex(List<Expression> args) {
        if (args.size() < 2) {
            return -1;
        }
        if (TypeUtils.isAssignableTo("org.slf4j.Marker", args.get(0).getType())) {
            return args.size() >= 3 && TypeUtils.isString(args.get(1).getType()) ? 2 : -1;
        }
        return TypeUtils.isString(args.get(0).getType()) ? 1 : -1;
    }

    private static Expression unwrapSafeStringValueOf(Expression arg, boolean isLastArg) {
        if (!(arg instanceof J.MethodInvocation call) || !STRING_VALUE_OF.matches(call)) {
            return null;
        }
        List<Expression> innerArgs = call.getArguments();
        if (innerArgs.size() != 1) {
            return null;
        }
        Expression inner = innerArgs.get(0);
        JavaType innerType = inner.getType();
        if (innerType instanceof JavaType.Array) {
            return null;
        }
        if (isLastArg && TypeUtils.isAssignableTo("java.lang.Throwable", innerType)) {
            return null;
        }
        return inner.withPrefix(call.getPrefix());
    }
}
