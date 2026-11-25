package com.claudecode.recipes;

import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.staticanalysis.RemoveRedundantTypeCast;

/**
 * Runs OpenRewrite's {@link RemoveRedundantTypeCast} except in compilation units that cast the
 * result of a signature-polymorphic {@link java.lang.invoke.MethodHandle} or
 * {@link java.lang.invoke.VarHandle} invocation.
 *
 * <p>Signature-polymorphic calls such as {@code (int) handle.invokeExact(...)} derive their
 * bytecode return descriptor from the call-site cast; stripping the cast breaks compilation or
 * triggers {@code WrongMethodTypeException} at runtime.
 */
public final class RemoveRedundantTypeCastSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Remove redundant type casts safely";
    }

    @Override
    public String getDescription() {
        return "Removes redundant type casts while preserving signature-polymorphic MethodHandle/VarHandle casts.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = new RemoveRedundantTypeCast().getVisitor();
        return new TreeVisitor<>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx, Cursor parent) {
                if (tree instanceof J.CompilationUnit compilationUnit
                        && containsPolymorphicSignatureCast(compilationUnit)) {
                    return compilationUnit;
                }
                return delegate.visit(tree, ctx, parent);
            }
        };
    }

    private static boolean containsPolymorphicSignatureCast(J.CompilationUnit cu) {
        AtomicBoolean found = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.TypeCast visitTypeCast(J.TypeCast typeCast, AtomicBoolean flag) {
                if (isPolymorphicInvocation(typeCast.getExpression())) {
                    flag.set(true);
                    return typeCast;
                }
                return super.visitTypeCast(typeCast, flag);
            }
        }.visit(cu, found);
        return found.get();
    }

    private static boolean isPolymorphicInvocation(Expression expr) {
        Expression unwrapped = expr.unwrap();
        if (!(unwrapped instanceof J.MethodInvocation invocation)) {
            return false;
        }
        String name = invocation.getSimpleName();
        if ("invokeExact".equals(name) || "invoke".equals(name)) {
            return true;
        }
        if (invocation.getMethodType() == null) {
            return false;
        }
        return TypeUtils.isOfClassType(
                invocation.getMethodType().getDeclaringType(), "java.lang.invoke.MethodHandle")
            || TypeUtils.isOfClassType(
                invocation.getMethodType().getDeclaringType(), "java.lang.invoke.VarHandle");
    }
}
