package com.claudecode.recipes;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Removes redundant {@code .toString()} calls inside {@code String} concatenations when the other
 * operand is already a {@code String} and the receiver is provably non-null (so removing
 * {@code .toString()} preserves both concatenation typing and null-check semantics).
 */
public final class RemoveUnnecessaryToStringSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Remove unnecessary toString() in String concatenation";
    }

    @Override
    public String getDescription() {
        return "Simplifies redundant non-null receiver.toString() calls inside String + concatenations.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.Binary visitBinary(J.Binary binary, ExecutionContext ctx) {
                J.Binary visited = super.visitBinary(binary, ctx);
                if (visited.getOperator() != J.Binary.Type.Addition
                        || !TypeUtils.isString(visited.getType())) {
                    return visited;
                }

                Expression left = visited.getLeft();
                Expression right = visited.getRight();

                if (TypeUtils.isString(right.getType())) {
                    Expression unwrappedLeft = unwrapSafeToString(left, getCursor());
                    if (unwrappedLeft != null) {
                        visited = visited.withLeft(unwrappedLeft);
                        left = unwrappedLeft;
                    }
                }

                if (TypeUtils.isString(left.getType())) {
                    Expression unwrappedRight = unwrapSafeToString(right, getCursor());
                    if (unwrappedRight != null) {
                        visited = visited.withRight(unwrappedRight);
                    }
                }

                return visited;
            }
        };
    }

    private static Expression unwrapSafeToString(Expression expr, Cursor cursor) {
        if (!(expr instanceof J.MethodInvocation mi)
                || !"toString".equals(mi.getSimpleName())
                || mi.getSelect() == null
                || !hasNoArguments(mi)) {
            return null;
        }
        Expression receiver = mi.getSelect();
        JavaType receiverType = receiver.getType();
        if (receiverType == null || receiverType instanceof JavaType.Array) {
            return null;
        }
        if (!isProvablyNonNull(receiver, cursor)) {
            return null;
        }
        return receiver.withPrefix(mi.getPrefix());
    }

    private static boolean hasNoArguments(J.MethodInvocation mi) {
        List<Expression> args = mi.getArguments();
        return args.isEmpty() || (args.size() == 1 && args.getFirst() instanceof J.Empty);
    }

    private static boolean isProvablyNonNull(Expression receiver, Cursor cursor) {
        Expression unwrapped = receiver.unwrap();
        if (unwrapped instanceof J.NewClass || unwrapped instanceof J.NewArray) {
            return true;
        }
        if (unwrapped instanceof J.Literal literal && literal.getValue() != null) {
            return true;
        }
        if (unwrapped instanceof J.MethodInvocation mi) {
            return isNonNullFluentCall(mi, cursor);
        }
        if (unwrapped instanceof J.Identifier id) {
            if ("this".equals(id.getSimpleName())) {
                return true;
            }
            return isNonNullFieldOrLocal(id, cursor);
        }
        return false;
    }

    private static boolean isNonNullFluentCall(J.MethodInvocation mi, Cursor cursor) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null || mt.getDeclaringType() == null || mi.getSelect() == null) {
            return false;
        }
        boolean isBuilder = TypeUtils.isOfClassType(mt.getDeclaringType(), "java.lang.StringBuilder")
                || TypeUtils.isOfClassType(mt.getDeclaringType(), "java.lang.StringBuffer");
        return isBuilder
                && TypeUtils.isOfType(mt.getReturnType(), mt.getDeclaringType())
                && isProvablyNonNull(mi.getSelect(), cursor);
    }

    private static boolean isNonNullFieldOrLocal(J.Identifier id, Cursor cursor) {
        JavaType.Variable fieldType = id.getFieldType();
        if (fieldType == null) {
            return false;
        }
        // Check enclosing method/block local variables initialized to a non-null expression and never reassigned.
        J.MethodDeclaration enclosingMethod = cursor.firstEnclosing(J.MethodDeclaration.class);
        if (enclosingMethod != null && isNonNullLocalIn(enclosingMethod, fieldType)) {
            return true;
        }
        // Check enclosing class final fields initialized directly to a non-null expression.
        for (Cursor c = cursor; c != null; c = c.getParent()) {
            if (c.getValue() instanceof J.ClassDeclaration cls
                    && isNonNullFinalFieldIn(cls, fieldType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNonNullFinalFieldIn(
            J.ClassDeclaration cls, JavaType.Variable target) {
        for (Statement stmt : cls.getBody().getStatements()) {
            if (stmt instanceof J.VariableDeclarations decls
                    && decls.hasModifier(J.Modifier.Type.Final)) {
                for (J.VariableDeclarations.NamedVariable nv : decls.getVariables()) {
                    if (matchesVariable(nv, target)
                            && nv.getInitializer() != null
                            && isDirectlyNonNullInitializer(nv.getInitializer())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isNonNullLocalIn(
            J.MethodDeclaration method, JavaType.Variable target) {
        if (method.getBody() == null) {
            return false;
        }
        AtomicBoolean initializedNonNull = new AtomicBoolean(false);
        AtomicBoolean reassigned = new AtomicBoolean(false);
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(
                    J.VariableDeclarations.NamedVariable nv, Integer unused) {
                if (matchesVariable(nv, target)
                        && nv.getInitializer() != null
                        && isDirectlyNonNullInitializer(nv.getInitializer())) {
                    initializedNonNull.set(true);
                }
                return super.visitVariable(nv, unused);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Integer unused) {
                if (assignment.getVariable() instanceof J.Identifier assigned
                        && target.equals(assigned.getFieldType())) {
                    reassigned.set(true);
                }
                return super.visitAssignment(assignment, unused);
            }
        }.visit(method.getBody(), 0);
        return initializedNonNull.get() && !reassigned.get();
    }

    private static boolean matchesVariable(
            J.VariableDeclarations.NamedVariable nv, JavaType.Variable target) {
        return target.equals(nv.getVariableType());
    }

    private static boolean isDirectlyNonNullInitializer(Expression initializer) {
        Expression unwrapped = initializer.unwrap();
        return unwrapped instanceof J.NewClass
                || unwrapped instanceof J.NewArray
                || (unwrapped instanceof J.Literal literal && literal.getValue() != null);
    }
}
