package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openrewrite.ExecutionContext;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaPrinter;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/** Converts stateless, explicitly typed anonymous functional-interface implementations to lambdas. */
public final class ReplaceAnonymousClassWithLambdaSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Replace trivial anonymous classes with lambdas safely";
    }

    @Override
    public String getDescription() {
        return "Replaces an explicitly typed, stateless anonymous implementation of a functional interface "
            + "with an equivalent lambda while preserving ambiguous or identity-sensitive classes.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass visited = (J.NewClass) super.visitNewClass(newClass, ctx);
                J.MethodDeclaration implementation = eligibleImplementation(visited);
                if (implementation == null) return visited;

                List<String> parameters = parameterNames(implementation);
                if (parameters == null) return visited;

                String replacement = staticMethodReference(implementation, parameters);
                if (replacement == null) {
                    List<String> lambdaParameterNames = lambdaParameterNames(implementation, parameters);
                    String lambdaParameters = lambdaParameterNames.size() == 1
                        ? lambdaParameterNames.getFirst()
                        : "(" + String.join(", ", lambdaParameterNames) + ")";
                    JavaType.FullyQualified targetType =
                        TypeUtils.asFullyQualified(visited.getClazz().getType());
                    if (targetType == null) return visited;
                    String targetTypeSource = visited.getClazz().printTrimmed(getCursor());
                    String lambdaBody = lambdaBody(implementation, targetType, targetTypeSource);
                    if (lambdaBody == null) return visited;
                    replacement = lambdaParameters + " -> " + lambdaBody;
                }

                JavaTemplate template = JavaTemplate.builder(replacement)
                    .contextSensitive()
                    .build();
                return template.apply(updateCursor(visited), visited.getCoordinates().replace());
            }

            private J.MethodDeclaration eligibleImplementation(J.NewClass newClass) {
                if (newClass.getBody() == null || newClass.getClazz() == null
                        || newClass.getEnclosing() != null || !hasNoConstructorArguments(newClass)) {
                    return null;
                }
                JavaType.FullyQualified targetType =
                    TypeUtils.asFullyQualified(newClass.getClazz().getType());
                if (targetType == null
                        || targetType.getKind() != JavaType.FullyQualified.Kind.Interface
                        || !hasMatchingExplicitTargetType(newClass, targetType)
                        || abstractMethodCount(targetType) != 1
                        || newClass.getBody().getStatements().size() != 1
                        || !(newClass.getBody().getStatements().getFirst()
                            instanceof J.MethodDeclaration method)
                        || method.getBody() == null
                        || (method.getTypeParameters() != null && !method.getTypeParameters().isEmpty())
                        || !hasOnlyOverrideAnnotation(method)
                        || usesAnonymousIdentity(method.getBody())) {
                    return null;
                }
                return method;
            }

            private boolean hasNoConstructorArguments(J.NewClass newClass) {
                return newClass.getArguments().size() == 1
                    && newClass.getArguments().getFirst() instanceof J.Empty;
            }

            private boolean hasMatchingExplicitTargetType(J.NewClass newClass,
                                                          JavaType.FullyQualified targetType) {
                J.VariableDeclarations declarations = getCursor().firstEnclosing(J.VariableDeclarations.class);
                if (declarations == null || declarations.getTypeExpression() == null) return false;
                if (declarations.getTypeExpression() instanceof J.Identifier identifier
                        && "var".equals(identifier.getSimpleName())) {
                    return false;
                }
                boolean directInitializer = declarations.getVariables().stream()
                    .map(J.VariableDeclarations.NamedVariable::getInitializer)
                    .anyMatch(initializer -> initializer != null
                        && initializer.getId().equals(newClass.getId()));
                JavaType.FullyQualified declaredType =
                    TypeUtils.asFullyQualified(declarations.getType());
                return directInitializer && declaredType != null
                    && declaredType.getFullyQualifiedName().equals(targetType.getFullyQualifiedName());
            }

            private long abstractMethodCount(JavaType.FullyQualified targetType) {
                Set<String> signatures = new HashSet<>();
                targetType.getVisibleMethods().forEachRemaining(method -> {
                    if (method.hasFlags(Flag.Abstract) && !method.hasFlags(Flag.Static)) {
                        signatures.add(method.getName() + method.getParameterTypes());
                    }
                });
                return signatures.size();
            }

            private boolean hasOnlyOverrideAnnotation(J.MethodDeclaration method) {
                return method.getLeadingAnnotations().size() == 1
                    && "Override".equals(method.getLeadingAnnotations().getFirst().getSimpleName());
            }

            private List<String> parameterNames(J.MethodDeclaration method) {
                List<String> names = new ArrayList<>();
                for (Statement parameter : method.getParameters()) {
                    if (parameter instanceof J.Empty) continue;
                    if (!(parameter instanceof J.VariableDeclarations declarations)
                            || declarations.getVariables().size() != 1
                            || !declarations.getLeadingAnnotations().isEmpty()
                            || declarations.getVariables().getFirst().getName().getAnnotations().size() > 0) {
                        return null;
                    }
                    names.add(declarations.getVariables().getFirst().getSimpleName());
                }
                return names;
            }

            private boolean usesAnonymousIdentity(J.Block body) {
                boolean[] unsafe = {false};
                new JavaVisitor<Integer>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier, Integer unused) {
                        if ("this".equals(identifier.getSimpleName())
                                || "super".equals(identifier.getSimpleName())) {
                            unsafe[0] = true;
                        }
                        return super.visitIdentifier(identifier, unused);
                    }
                }.visit(body, 0);
                return unsafe[0];
            }

            private List<String> lambdaParameterNames(J.MethodDeclaration method,
                                                      List<String> parameters) {
                Set<String> used = new HashSet<>();
                new JavaVisitor<Integer>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier, Integer unused) {
                        if (parameters.contains(identifier.getSimpleName())) {
                            used.add(identifier.getSimpleName());
                        }
                        return super.visitIdentifier(identifier, unused);
                    }
                }.visit(method.getBody(), 0);
                return parameters.stream()
                    .map(parameter -> used.contains(parameter) ? parameter : "_")
                    .toList();
            }

            private String lambdaBody(J.MethodDeclaration method,
                                      JavaType.FullyQualified targetType,
                                      String targetTypeSource) {
                J.Block body = method.getBody();
                if (body == null) return null;
                if (body.getStatements().size() == 1
                        && body.getStatements().getFirst() instanceof J.Return returned
                        && returned.getExpression() != null) {
                    return printWithQualifiedNestedTypes(
                        returned.getExpression(), targetType, targetTypeSource);
                }
                return printWithQualifiedNestedTypes(body, targetType, targetTypeSource);
            }

            private String printWithQualifiedNestedTypes(J tree,
                                                         JavaType.FullyQualified targetType,
                                                         String targetTypeSource) {
                PrintOutputCapture<Integer> output = new PrintOutputCapture<>(0);
                new JavaPrinter<Integer>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier,
                                             PrintOutputCapture<Integer> capture) {
                        JavaType.FullyQualified identifierType =
                            TypeUtils.asFullyQualified(identifier.getType());
                        if (identifier.getFieldType() == null
                                && isNestedIn(identifierType, targetType)
                                && !isAlreadyQualified(identifier)) {
                            visitSpace(Space.EMPTY, Space.Location.ANNOTATIONS, capture);
                            visit(identifier.getAnnotations(), capture);
                            beforeSyntax(identifier, Space.Location.IDENTIFIER_PREFIX, capture);
                            capture.append(targetTypeSource).append(".")
                                .append(identifier.getSimpleName());
                            afterSyntax(identifier, capture);
                            return identifier;
                        }
                        return super.visitIdentifier(identifier, capture);
                    }

                    private boolean isAlreadyQualified(J.Identifier identifier) {
                        Object parent = getCursor().getParentTreeCursor().getValue();
                        return parent instanceof J.FieldAccess fieldAccess
                            && fieldAccess.getName().getId().equals(identifier.getId());
                    }
                }.visit(tree, output);
                return output.getOut().trim();
            }

            private boolean isNestedIn(JavaType.FullyQualified candidate,
                                       JavaType.FullyQualified targetType) {
                if (candidate == null) return false;
                JavaType.FullyQualified owner = candidate.getOwningClass();
                while (owner != null) {
                    if (owner.getFullyQualifiedName().equals(targetType.getFullyQualifiedName())) {
                        return true;
                    }
                    owner = owner.getOwningClass();
                }
                return false;
            }

            private String staticMethodReference(J.MethodDeclaration method,
                                                 List<String> parameters) {
                J.Block body = method.getBody();
                if (!parameters.isEmpty() || body == null || body.getStatements().size() != 1
                        || !(body.getStatements().getFirst() instanceof J.Return returned)
                        || !(returned.getExpression() instanceof J.MethodInvocation invocation)
                        || invocation.getSelect() == null || invocation.getMethodType() == null
                        || !invocation.getMethodType().hasFlags(Flag.Static)
                        || !(invocation.getArguments().size() == 1
                            && invocation.getArguments().getFirst() instanceof J.Empty)) {
                    return null;
                }
                return invocation.getSelect().printTrimmed(getCursor())
                    + "::" + invocation.getSimpleName();
            }
        };
    }
}
