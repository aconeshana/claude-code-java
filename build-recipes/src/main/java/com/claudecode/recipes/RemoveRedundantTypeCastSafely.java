package com.claudecode.recipes;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.staticanalysis.RemoveRedundantTypeCast;

/**
 * Runs OpenRewrite's {@link RemoveRedundantTypeCast} except in compilation units that cast the
 * result of a signature-polymorphic {@link java.lang.invoke.MethodHandle} or
 * {@link java.lang.invoke.VarHandle} invocation, and additionally strips redundant casts around
 * free generic-return calls (e.g. {@code (ObjectNode) raw.deepCopy()}) whose type parameter is
 * inferred directly from the enclosing {@code return}, assignment, or variable declaration target.
 */
public final class RemoveRedundantTypeCastSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Remove redundant type casts safely";
    }

    @Override
    public String getDescription() {
        return "Removes redundant type casts (including target-inferred generic return casts)"
                + " while preserving signature-polymorphic MethodHandle/VarHandle casts.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = new RemoveRedundantTypeCast().getVisitor();
        JavaVisitor<ExecutionContext> genericTargetCastCleaner = createGenericTargetCastCleaner();
        return new TreeVisitor<>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx, Cursor parent) {
                if (tree instanceof J.CompilationUnit compilationUnit
                        && containsPolymorphicSignatureCast(compilationUnit)) {
                    return compilationUnit;
                }
                Tree afterDelegate = delegate.visit(tree, ctx, parent);
                return genericTargetCastCleaner.visit(afterDelegate, ctx, parent);
            }
        };
    }

    private static JavaVisitor<ExecutionContext> createGenericTargetCastCleaner() {
        return new JavaVisitor<>() {
            @Override
            public J visitTypeCast(J.TypeCast typeCast, ExecutionContext ctx) {
                J visited = super.visitTypeCast(typeCast, ctx);
                if (!(visited instanceof J.TypeCast cast)) {
                    return visited;
                }
                if (isNestedInTypeCast(getCursor())) {
                    return cast;
                }
                Expression unwrapped = cast.getExpression().unwrap();
                if (!(unwrapped instanceof J.MethodInvocation invocation)
                        || invocation.getTypeParameters() != null
                        || isPolymorphicInvocation(invocation)
                        || isRawReceiver(invocation)) {
                    return cast;
                }

                JavaType castType = cast.getType();
                JavaType targetType = targetTypeFor(getCursor(), cast);
                if (castType == null
                        || targetType == null
                        || castType instanceof JavaType.Primitive
                        || targetType instanceof JavaType.Primitive
                        || !TypeUtils.isOfType(castType, targetType)) {
                    return cast;
                }

                JavaType.GenericTypeVariable returnTypeVar =
                        freeGenericReturnTypeVariable(invocation.getMethodType());
                if (returnTypeVar == null || !satisfiesBounds(targetType, returnTypeVar)) {
                    return cast;
                }

                return cast.getExpression().withPrefix(cast.getPrefix());
            }
        };
    }

    private static boolean isNestedInTypeCast(Cursor cursor) {
        Cursor parent = cursor.getParentTreeCursor();
        while (parent.getValue() instanceof J.Parentheses
                || parent.getValue() instanceof J.ControlParentheses) {
            parent = parent.getParentTreeCursor();
        }
        return parent.getValue() instanceof J.TypeCast;
    }

    private static JavaType targetTypeFor(Cursor cursor, J.TypeCast cast) {
        Cursor parent = cursor.getParentTreeCursor();
        while (parent.getValue() instanceof J.Parentheses) {
            parent = parent.getParentTreeCursor();
        }
        Object parentValue = parent.getValue();
        if (parentValue instanceof J.Return returnStmt
                && returnStmt.getExpression() != null
                && returnStmt.getExpression().unwrap() == cast) {
            Cursor enclosing = parent.dropParentUntil(
                    v -> v instanceof J.MethodDeclaration
                            || v instanceof J.Lambda
                            || v instanceof J.ClassDeclaration
                            || v instanceof J.CompilationUnit);
            if (enclosing.getValue() instanceof J.MethodDeclaration method
                    && method.getMethodType() != null) {
                return method.getMethodType().getReturnType();
            }
            return null;
        }
        if (parentValue instanceof J.VariableDeclarations.NamedVariable namedVar
                && namedVar.getInitializer() != null
                && namedVar.getInitializer().unwrap() == cast) {
            Object grandParent = parent.getParentTreeCursor().getValue();
            if (grandParent instanceof J.VariableDeclarations decls
                    && decls.getTypeExpression() != null
                    && !isVarKeyword(decls)) {
                return namedVar.getType();
            }
            return null;
        }
        if (parentValue instanceof J.Assignment assignment
                && assignment.getAssignment().unwrap() == cast) {
            return assignment.getVariable().getType();
        }
        return null;
    }

    private static boolean isVarKeyword(J.VariableDeclarations decls) {
        return decls.getTypeExpression() instanceof J.Identifier id
                && "var".equals(id.getSimpleName());
    }

    private static boolean isRawReceiver(J.MethodInvocation invocation) {
        if (invocation.getSelect() == null) {
            return false;
        }
        JavaType selectType = invocation.getSelect().getType();
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(selectType);
        return fq != null
                && !fq.getTypeParameters().isEmpty()
                && TypeUtils.asParameterized(selectType) == null;
    }

    private static JavaType.GenericTypeVariable freeGenericReturnTypeVariable(
            JavaType.Method invoked) {
        if (invoked == null || invoked.getDeclaringType() == null) {
            return null;
        }
        JavaType.Method declared = findDeclaredMethod(invoked.getDeclaringType(), invoked);
        if (declared == null) {
            return null;
        }
        if (!(declared.getReturnType() instanceof JavaType.GenericTypeVariable returnTv)) {
            return null;
        }
        String tvName = returnTv.getName();
        if (!declared.getDeclaredFormalTypeNames().contains(tvName)) {
            return null;
        }
        for (JavaType paramType : declared.getParameterTypes()) {
            if (mentionsTypeVariable(paramType, tvName)) {
                return null;
            }
        }
        return returnTv;
    }

    private static JavaType.Method findDeclaredMethod(
            JavaType.FullyQualified owner, JavaType.Method invoked) {
        if (owner == null) {
            return null;
        }
        List<JavaType> invokedParams = invoked.getParameterTypes();
        for (JavaType.Method m : owner.getMethods()) {
            if (m.getName().equals(invoked.getName())
                    && matchesOverloadSignature(m.getParameterTypes(), invokedParams)) {
                return m;
            }
        }
        JavaType.Method fromSuper = findDeclaredMethod(owner.getSupertype(), invoked);
        if (fromSuper != null) {
            return fromSuper;
        }
        for (JavaType.FullyQualified iface : owner.getInterfaces()) {
            JavaType.Method fromIface = findDeclaredMethod(iface, invoked);
            if (fromIface != null) {
                return fromIface;
            }
        }
        return null;
    }

    private static boolean matchesOverloadSignature(
            List<JavaType> declaredParams, List<JavaType> invokedParams) {
        if (declaredParams.size() != invokedParams.size()) {
            return false;
        }
        for (int i = 0; i < declaredParams.size(); i++) {
            if (!matchesErasedParameter(declaredParams.get(i), invokedParams.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesErasedParameter(JavaType declaredParam, JavaType invokedParam) {
        if (declaredParam instanceof JavaType.GenericTypeVariable) {
            return true;
        }
        if (declaredParam instanceof JavaType.Array decArr
                && invokedParam instanceof JavaType.Array invArr) {
            return matchesErasedParameter(decArr.getElemType(), invArr.getElemType());
        }
        if (declaredParam instanceof JavaType.Primitive decPrim) {
            return decPrim.equals(invokedParam);
        }
        JavaType.FullyQualified decFq = TypeUtils.asFullyQualified(declaredParam);
        JavaType.FullyQualified invFq = TypeUtils.asFullyQualified(invokedParam);
        return decFq != null
                && invFq != null
                && decFq.getFullyQualifiedName().equals(invFq.getFullyQualifiedName());
    }

    private static boolean mentionsTypeVariable(JavaType type, String tvName) {
        if (type == null) {
            return false;
        }
        if (type instanceof JavaType.GenericTypeVariable tv) {
            return tvName.equals(tv.getName());
        }
        if (type instanceof JavaType.Array arr) {
            return mentionsTypeVariable(arr.getElemType(), tvName);
        }
        if (type instanceof JavaType.Parameterized p) {
            for (JavaType arg : p.getTypeParameters()) {
                if (mentionsTypeVariable(arg, tvName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean satisfiesBounds(JavaType targetType, JavaType.GenericTypeVariable tv) {
        List<JavaType> bounds = tv.getBounds();
        if (bounds.isEmpty()) {
            return true;
        }
        for (JavaType bound : bounds) {
            if (!TypeUtils.isAssignableTo(bound, targetType)) {
                return false;
            }
        }
        return true;
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
