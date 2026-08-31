package com.claudecode.recipes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.staticanalysis.ReplaceLambdaWithMethodReference;

/**
 * Runs OpenRewrite's method-reference cleanup except in a compilation unit containing a lambda
 * whose method receiver is obtained through an array access.
 *
 * <p>A bound method reference evaluates its receiver immediately, while the equivalent lambda
 * evaluates it when invoked. Rewriting {@code  -> holder[0].value} is therefore unsafe when
 * {@code holder[0]} is initialized after the lambda is created.
 */
public final class ReplaceLambdaWithMethodReferenceSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use method references without eager receiver evaluation";
    }

    @Override
    public String getDescription() {
        return "Use OpenRewrite's method-reference cleanup while preserving lazy array-access receivers.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = new ReplaceLambdaWithMethodReference().getVisitor();
        return new TreeVisitor<>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx, Cursor parent) {
                if (tree instanceof J.CompilationUnit compilationUnit
                    && containsArrayAccessReceiver(compilationUnit, ctx)) {
                    return compilationUnit;
                }
                Tree delegated = delegate.visit(tree, ctx, parent);
                if (delegated instanceof J.CompilationUnit compilationUnit) {
                    ResolveOverloadedReceiverMethods resolver =
                        new ResolveOverloadedReceiverMethods(ambiguousTypeNames(compilationUnit));
                    J.CompilationUnit resolved =
                        (J.CompilationUnit) resolver.visit(compilationUnit, ctx);
                    for (String type : resolver.getTypesToImport()) {
                        resolved = (J.CompilationUnit) new AddImport<ExecutionContext>(
                            type, null, false)
                            .visit(resolved, ctx);
                    }
                    return resolved;
                }
                return delegated;
            }
        };
    }

    /** Handles receiver-method lambdas that the upstream recipe skips solely due to overloads. */
    private static final class ResolveOverloadedReceiverMethods
            extends JavaVisitor<ExecutionContext> {

        private final Set<String> typesToImport = new HashSet<>();
        private final Set<String> ambiguousTypeNames;

        private ResolveOverloadedReceiverMethods(Set<String> ambiguousTypeNames) {
            this.ambiguousTypeNames = ambiguousTypeNames;
        }

        Set<String> getTypesToImport() {
            return typesToImport;
        }

        @Override
        public J visitLambda(J.Lambda lambda, ExecutionContext ctx) {
            J.Lambda visited = (J.Lambda) super.visitLambda(lambda, ctx);
            J body = unwrap(visited.getBody());
            if (!(body instanceof J.MethodInvocation invocation)
                    || !(invocation.getSelect() instanceof J.Identifier receiver)
                    || invocation.getArguments().size() != 1
                    || !(invocation.getArguments().getFirst() instanceof J.Empty)
                    || invocation.getMethodType() == null
                    || invocation.getMethodType().hasFlags(Flag.Static, Flag.Varargs)
                    || visited.getParameters().getParameters().size() != 1
                    || !(visited.getParameters().getParameters().getFirst()
                        instanceof J.VariableDeclarations parameter)
                    || parameter.getVariables().size() != 1
                    || receiver.getFieldType() != parameter.getVariables().getFirst().getVariableType()) {
                return visited;
            }

            JavaType.FullyQualified receiverType = TypeUtils.asFullyQualified(receiver.getType());
            if (receiverType == null || !hasUniqueApplicableMethod(receiverType,
                    invocation.getMethodType())) {
                return visited;
            }

            String fullyQualifiedName = receiverType.getFullyQualifiedName();
            boolean useSimpleName = !ambiguousTypeNames.contains(receiverType.getClassName());
            String sourceType = useSimpleName
                ? receiverType.getClassName()
                : fullyQualifiedName.replace('$', '.');
            J.MemberReference replacement = JavaTemplate.builder(
                    sourceType + "::" + invocation.getSimpleName())
                .contextSensitive()
                .build()
                .apply(updateCursor(visited), visited.getCoordinates().replace());
            replacement = replacement
                .withMethodType(invocation.getMethodType())
                .withType(visited.getType());
            if (useSimpleName) {
                typesToImport.add(fullyQualifiedName);
            }
            return replacement;
        }

        private static boolean hasUniqueApplicableMethod(JavaType.FullyQualified receiverType,
                JavaType.Method selected) {
            int applicable = 0;
            for (JavaType.Method candidate : receiverType.getMethods()) {
                if (!candidate.getName().equals(selected.getName()) || candidate.isConstructor()) continue;
                if (candidate.hasFlags(Flag.Static)) {
                    if (isApplicableStaticCandidate(candidate, receiverType)) {
                        applicable++;
                    }
                } else if (candidate.getParameterTypes().isEmpty()
                        || candidate.hasFlags(Flag.Varargs)) {
                    applicable++;
                }
                if (applicable > 1) return false;
            }
            return applicable == 1;
        }

        private static boolean isApplicableStaticCandidate(JavaType.Method candidate,
                JavaType.FullyQualified receiverType) {
            if (candidate.getParameterTypes().size() != 1) return false;
            JavaType parameterType = candidate.getParameterTypes().getFirst();
            if (!candidate.hasFlags(Flag.Varargs)) {
                return TypeUtils.isAssignableTo(parameterType, receiverType);
            }
            if (TypeUtils.isAssignableTo(parameterType, receiverType)) return true;
            return parameterType instanceof JavaType.Array array
                    && TypeUtils.isAssignableTo(array.getElemType(), receiverType);
        }

        private static J unwrap(J body) {
            if (body instanceof J.Block block && block.getStatements().size() == 1) {
                Statement statement = block.getStatements().getFirst();
                if (statement instanceof J.Return returnStatement) {
                    return returnStatement.getExpression();
                }
                return statement;
            }
            return body;
        }
    }

    private static Set<String> ambiguousTypeNames(J.CompilationUnit compilationUnit) {
        Map<String, Set<String>> typesBySimpleName = new HashMap<>();
        new JavaIsoVisitor<Map<String, Set<String>>>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier,
                    Map<String, Set<String>> types) {
                collect(identifier.getType(), types);
                return super.visitIdentifier(identifier, types);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess,
                    Map<String, Set<String>> types) {
                collect(fieldAccess.getType(), types);
                return super.visitFieldAccess(fieldAccess, types);
            }

            private void collect(JavaType type, Map<String, Set<String>> types) {
                JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
                if (fullyQualified != null) {
                    types.computeIfAbsent(fullyQualified.getClassName(), _ -> new HashSet<>())
                        .add(fullyQualified.getFullyQualifiedName());
                }
            }
        }.visit(compilationUnit, typesBySimpleName, new Cursor(null, compilationUnit));
        Set<String> ambiguous = new HashSet<>();
        typesBySimpleName.forEach((name, types) -> {
            if (types.size() > 1) ambiguous.add(name);
        });
        return ambiguous;
    }

    private boolean containsArrayAccessReceiver(J.CompilationUnit compilationUnit, ExecutionContext ctx) {
        AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Lambda visitLambda(J.Lambda lambda, AtomicBoolean unsafe) {
                J.Lambda visited = super.visitLambda(lambda, unsafe);
                J body = visited.getBody();
                if (body instanceof J.Block block && block.getStatements().size() == 1) {
                    Statement statement = block.getStatements().getFirst();
                    body = statement instanceof J.Return returnStatement
                            ? returnStatement.getExpression()
                            : statement;
                }
                if (body instanceof J.MethodInvocation invocation
                        && invocation.getSelect() instanceof J.ArrayAccess) {
                    unsafe.set(true);
                }
                return visited;
            }
        }.visit(compilationUnit, found, new Cursor(null, compilationUnit));
        return found.get();
    }
}
