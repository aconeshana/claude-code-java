package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

/**
 * Migrates deprecated {@code com.fasterxml.jackson.databind.JsonNode#fields()} calls to
 * {@code JsonNode#properties()}.
 */
public final class UseJsonNodeProperties extends Recipe {

    private static final MethodMatcher JSON_NODE_FIELDS =
        new MethodMatcher("com.fasterxml.jackson.databind.JsonNode fields()", true);
    private static final MethodMatcher ITERATOR_FOR_EACH_REMAINING =
        new MethodMatcher("java.util.Iterator forEachRemaining(java.util.function.Consumer)", true);
    private static final MethodMatcher ITERATOR_HAS_NEXT =
        new MethodMatcher("java.util.Iterator hasNext()", true);
    private static final MethodMatcher ITERATOR_NEXT =
        new MethodMatcher("java.util.Iterator next()", true);

    @Override
    public String getDisplayName() {
        return "Use JsonNode.properties() instead of deprecated JsonNode.fields()";
    }

    @Override
    public String getDescription() {
        return "Replaces deprecated Jackson JsonNode.fields() calls with properties() "
            + "(using forEach, enhanced-for loops, or properties().iterator() as appropriate).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<>() {

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                List<Statement> statements = block.getStatements();
                List<Statement> rewritten = new ArrayList<>(statements.size());
                boolean changed = false;

                for (int index = 0; index < statements.size(); index++) {
                    Statement statement = statements.get(index);
                    if (index + 1 < statements.size()
                            && statement instanceof J.VariableDeclarations declaration
                            && statements.get(index + 1) instanceof J.WhileLoop whileLoop) {
                        J.ForEachLoop replacement = rewriteWhileLoop(
                            declaration, whileLoop,
                            statements.subList(index + 2, statements.size()));
                        if (replacement != null) {
                            rewritten.add(replacement);
                            index++;
                            changed = true;
                            continue;
                        }
                    }
                    rewritten.add(statement);
                }

                J.Block updated = changed ? block.withStatements(rewritten) : block;
                return super.visitBlock(updated, ctx);
            }

            @Override
            public J visitForLoop(J.ForLoop forLoop, ExecutionContext ctx) {
                J.ForEachLoop replacement = rewriteForLoop(forLoop);
                if (replacement != null) {
                    return super.visitForEachLoop(replacement, ctx);
                }
                return super.visitForLoop(forLoop, ctx);
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                if (ITERATOR_FOR_EACH_REMAINING.matches(method)
                        && method.getArguments().size() == 1
                        && method.getSelect() instanceof J.MethodInvocation fieldsCall
                        && JSON_NODE_FIELDS.matches(fieldsCall)
                        && fieldsCall.getSelect() != null) {
                    Expression visitedSelect = (Expression) visit(fieldsCall.getSelect(), ctx);
                    Expression visitedArg = (Expression) visit(method.getArguments().getFirst(), ctx);
                    J.MethodInvocation replacement = JavaTemplate.builder(
                            "#{any(com.fasterxml.jackson.databind.JsonNode)}.properties().forEach(#{any()})")
                        .contextSensitive()
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(getCursor(), method.getCoordinates().replace(), visitedSelect, visitedArg);
                    return replacement.withPrefix(method.getPrefix());
                }

                J.MethodInvocation visited =
                    (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                if (JSON_NODE_FIELDS.matches(visited) && visited.getSelect() != null) {
                    J.MethodInvocation replacement = JavaTemplate.builder(
                            "#{any(com.fasterxml.jackson.databind.JsonNode)}.properties().iterator()")
                        .contextSensitive()
                        .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                        .build()
                        .apply(updateCursor(visited), visited.getCoordinates().replace(),
                            visited.getSelect());
                    return replacement.withPrefix(visited.getPrefix());
                }
                return visited;
            }

            private J.ForEachLoop rewriteWhileLoop(
                    J.VariableDeclarations declaration,
                    J.WhileLoop whileLoop,
                    List<Statement> followingStatements) {
                if (declaration.getVariables().size() != 1
                        || !whileLoop.getPrefix().getComments().isEmpty()) {
                    return null;
                }
                J.VariableDeclarations.NamedVariable iteratorVar = declaration.getVariables().getFirst();
                Expression nodeExpr = extractJsonNodeSelect(iteratorVar.getInitializer());
                if (nodeExpr == null
                        || !isHasNextCall(whileLoop.getCondition().getTree(), iteratorVar)
                        || referencesVariable(followingStatements, iteratorVar)) {
                    return null;
                }
                return buildForEachLoop(
                    declaration.getPrefix(), whileLoop, whileLoop.getBody(), iteratorVar, nodeExpr);
            }

            private J.ForEachLoop rewriteForLoop(J.ForLoop forLoop) {
                J.ForLoop.Control control = forLoop.getControl();
                if (control.getInit().size() != 1
                        || !(control.getInit().getFirst() instanceof J.VariableDeclarations declaration)
                        || declaration.getVariables().size() != 1
                        || !isEmptyUpdates(control.getUpdate())) {
                    return null;
                }
                J.VariableDeclarations.NamedVariable iteratorVar = declaration.getVariables().getFirst();
                Expression nodeExpr = extractJsonNodeSelect(iteratorVar.getInitializer());
                if (nodeExpr == null || !isHasNextCall(control.getCondition(), iteratorVar)) {
                    return null;
                }
                return buildForEachLoop(
                    forLoop.getPrefix(), forLoop, forLoop.getBody(), iteratorVar, nodeExpr);
            }

            private J.ForEachLoop buildForEachLoop(
                    Space prefix,
                    Statement loopStatement,
                    Statement loopBody,
                    J.VariableDeclarations.NamedVariable iteratorVar,
                    Expression nodeExpr) {
                if (!(loopBody instanceof J.Block bodyBlock) || bodyBlock.getStatements().isEmpty()) {
                    return null;
                }
                if (!(bodyBlock.getStatements().getFirst() instanceof J.VariableDeclarations entryDecl)
                        || entryDecl.getVariables().size() != 1
                        || !entryDecl.getPrefix().getComments().isEmpty()) {
                    return null;
                }
                J.VariableDeclarations.NamedVariable entryVar = entryDecl.getVariables().getFirst();
                if (!isNextCall(entryVar.getInitializer(), iteratorVar)) {
                    return null;
                }
                List<Statement> remaining = new ArrayList<>(
                    bodyBlock.getStatements().subList(1, bodyBlock.getStatements().size()));
                if (referencesVariable(remaining, iteratorVar)) {
                    return null;
                }

                String loopVarHeader = formatLoopVariableHeader(entryDecl, entryVar);
                Cursor loopCursor = new Cursor(getCursor(), loopStatement);
                J.ForEachLoop generated = JavaTemplate.builder(
                        "for (" + loopVarHeader
                            + " : #{any(com.fasterxml.jackson.databind.JsonNode)}.properties()) {}")
                    .contextSensitive()
                    .javaParser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
                    .build()
                    .apply(loopCursor, loopStatement.getCoordinates().replace(), nodeExpr);
                maybeRemoveImport("java.util.Iterator");
                return generated
                    .withBody(bodyBlock.withStatements(remaining))
                    .withPrefix(prefix);
            }

            private String formatLoopVariableHeader(
                    J.VariableDeclarations entryDecl,
                    J.VariableDeclarations.NamedVariable entryVar) {
                StringBuilder header = new StringBuilder();
                for (J.Modifier modifier : entryDecl.getModifiers()) {
                    header.append(modifier.getKeyword()).append(' ');
                }
                String typeText = entryDecl.getTypeExpression() != null
                    ? entryDecl.getTypeExpression().printTrimmed(getCursor())
                    : "var";
                header.append(typeText).append(' ').append(entryVar.getSimpleName());
                return header.toString();
            }

            private Expression extractJsonNodeSelect(Expression initializer) {
                if (initializer instanceof J.MethodInvocation invocation
                        && JSON_NODE_FIELDS.matches(invocation)) {
                    return invocation.getSelect();
                }
                return null;
            }

            private boolean isHasNextCall(
                    Expression expression,
                    J.VariableDeclarations.NamedVariable iteratorVar) {
                return expression instanceof J.MethodInvocation invocation
                    && ITERATOR_HAS_NEXT.matches(invocation)
                    && isVariable(invocation.getSelect(), iteratorVar);
            }

            private boolean isNextCall(
                    Expression expression,
                    J.VariableDeclarations.NamedVariable iteratorVar) {
                return expression instanceof J.MethodInvocation invocation
                    && ITERATOR_NEXT.matches(invocation)
                    && isVariable(invocation.getSelect(), iteratorVar);
            }

            private boolean isEmptyUpdates(List<Statement> updates) {
                return updates.isEmpty()
                    || (updates.size() == 1 && updates.getFirst() instanceof J.Empty);
            }

            private boolean referencesVariable(
                    List<Statement> statements,
                    J.VariableDeclarations.NamedVariable variable) {
                AtomicBoolean found = new AtomicBoolean();
                JavaIsoVisitor<AtomicBoolean> finder = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean referenced) {
                        if (isVariable(identifier, variable)) {
                            referenced.set(true);
                        }
                        return identifier;
                    }
                };
                for (Statement statement : statements) {
                    finder.visit(statement, found, new Cursor(getCursor(), statement));
                    if (found.get()) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isVariable(
                    Expression expression,
                    J.VariableDeclarations.NamedVariable variable) {
                if (!(expression instanceof J.Identifier identifier)
                        || !variable.getSimpleName().equals(identifier.getSimpleName())) {
                    return false;
                }
                return variable.getVariableType() == null
                    || variable.getVariableType().equals(identifier.getFieldType());
            }
        };
    }
}
