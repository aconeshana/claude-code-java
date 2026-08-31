package com.claudecode.recipes;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/** Replaces unused switch type-pattern variables with {@code _}. */
public final class UseUnnamedTypePatterns extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use unnamed switch type patterns";
    }

    @Override
    public String getDescription() {
        return "Replace any unused switch type-pattern variable with Java's unnamed pattern `_`, "
            + "while leaving referenced variables and non-pattern case labels unchanged.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Case visitCase(J.Case case_, ExecutionContext ctx) {
                J.Case visited = super.visitCase(case_, ctx);
                List<J> labels = visited.getCaseLabels();
                if (labels.size() != 1
                        || !(labels.getFirst() instanceof J.VariableDeclarations declaration)
                        || declaration.getTypeExpression() == null
                        || declaration.getVariables().size() != 1) {
                    return visited;
                }
                J.VariableDeclarations.NamedVariable pattern = declaration.getVariables().getFirst();
                if ("_".equals(pattern.getSimpleName())
                        || pattern.getInitializer() != null
                        || !isTypePattern(pattern.getName())
                        || isReferenced(visited, pattern.getName())) {
                    return visited;
                }
                JavaType.Variable unnamedType = pattern.getVariableType().withName("_");
                J.Identifier unnamedName = pattern.getName()
                    .withSimpleName("_")
                    .withFieldType(unnamedType);
                J.VariableDeclarations unnamed = declaration.withVariables(List.of(
                    pattern.withVariableType(unnamedType).withName(unnamedName)));
                return visited.withCaseLabels(List.of(unnamed));
            }

            private boolean isTypePattern(J.Identifier identifier) {
                JavaType.Variable variable = identifier.getFieldType();
                return variable != null && !variable.hasFlags(Flag.Static);
            }

            private boolean isReferenced(J.Case case_, J.Identifier pattern) {
                AtomicBoolean referenced = new AtomicBoolean();
                JavaIsoVisitor<AtomicBoolean> finder = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean found) {
                        if (sameVariable(pattern, identifier)) found.set(true);
                        return found.get() ? identifier : super.visitIdentifier(identifier, found);
                    }
                };
                if (case_.getGuard() != null) finder.visit(case_.getGuard(), referenced);
                if (!referenced.get() && case_.getBody() != null) finder.visit(case_.getBody(), referenced);
                if (!referenced.get()) {
                    for (J statement : case_.getStatements()) {
                        finder.visit(statement, referenced);
                        if (referenced.get()) break;
                    }
                }
                return referenced.get();
            }

            private boolean sameVariable(J.Identifier declaration, J.Identifier candidate) {
                if (!declaration.getSimpleName().equals(candidate.getSimpleName())) return false;
                JavaType.Variable declaredType = declaration.getFieldType();
                JavaType.Variable candidateType = candidate.getFieldType();
                return declaredType != null && candidateType != null
                    && declaredType.equals(candidateType);
            }
        };
    }
}
