package com.claudecode.recipes;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Protects nullable boxed booleans in control expressions while allowing direct unboxing after an
 * explicit null guard that exits the current path.
 */
public final class AvoidBoxedBooleanExpressionsSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Avoid unsafe boxed boolean expressions";
    }

    @Override
    public String getDescription() {
        return "Use Boolean.TRUE/FALSE.equals for nullable control expressions, but simplify them "
            + "when an earlier null guard proves the boxed Boolean is non-null.";
    }

    @Override
    public Set<String> getTags() {
        return Set.of("RSPEC-S5411");
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("java.lang.Boolean", true),
            new JavaVisitor<ExecutionContext>() {
                @Override
                public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                    J.MethodInvocation visited =
                        (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
                    if (isBooleanTrueEquals(visited)
                            && visited.getArguments().size() == 1
                            && isControlExpression(visited)
                            && isKnownNonNull(visited.getArguments().getFirst())) {
                        Expression argument = visited.getArguments().getFirst();
                        return JavaTemplate.apply("#{any(java.lang.Boolean)}", updateCursor(visited),
                            visited.getCoordinates().replace(), argument);
                    }
                    return visited;
                }

                @Override
                public Expression visitExpression(Expression expression, ExecutionContext ctx) {
                    Expression visited = (Expression) super.visitExpression(expression, ctx);
                    if (TypeUtils.isOfClassType(visited.getType(), "java.lang.Boolean")
                            && isControlExpression(expression)
                            && !isKnownNonNull(visited)) {
                        return JavaTemplate.apply("Boolean.TRUE.equals(#{any(java.lang.Boolean)})",
                            updateCursor(visited), visited.getCoordinates().replace(), visited);
                    }
                    return visited;
                }

                @Override
                public J visitUnary(J.Unary unary, ExecutionContext ctx) {
                    J.Unary visited = (J.Unary) super.visitUnary(unary, ctx);
                    if (visited.getOperator() == J.Unary.Type.Not
                            && TypeUtils.isOfClassType(
                                visited.getExpression().getType(), "java.lang.Boolean")
                            && isControlExpression(unary)
                            && !isKnownNonNull(visited.getExpression())) {
                        return JavaTemplate.apply("Boolean.FALSE.equals(#{any(java.lang.Boolean)})",
                            updateCursor(visited), visited.getCoordinates().replace(),
                            visited.getExpression());
                    }
                    return visited;
                }

                private boolean isControlExpression(Expression expression) {
                    Cursor parent = getCursor().getParentTreeCursor();
                    if (parent.getValue() instanceof J.ControlParentheses
                            && parent.getParentTreeCursor().getValue() instanceof J.If) {
                        return true;
                    }
                    return parent.getValue() instanceof J.Ternary ternary
                        && ternary.getCondition() == expression;
                }

                private boolean isKnownNonNull(Expression expression) {
                    if (!(expression instanceof J.Identifier identifier)) return false;
                    if (enclosingConditionGuaranteesNonNull(identifier)) return true;

                    Cursor child = getCursor();
                    Cursor blockCursor = child.getParentTreeCursor();
                    while (blockCursor != null && !(blockCursor.getValue() instanceof J.Block)) {
                        child = blockCursor;
                        blockCursor = blockCursor.getParentTreeCursor();
                    }
                    if (blockCursor == null || !(child.getValue() instanceof Statement current)) {
                        return false;
                    }

                    boolean guarded = false;
                    List<Statement> statements = ((J.Block) blockCursor.getValue()).getStatements();
                    for (Statement statement : statements) {
                        if (statement == current) break;
                        if (assigns(identifier, statement)) guarded = false;
                        if (isNullExitGuard(identifier, statement)) guarded = true;
                    }
                    return guarded;
                }

                private boolean enclosingConditionGuaranteesNonNull(J.Identifier identifier) {
                    Cursor ancestor = getCursor().getParent();
                    while (ancestor != null) {
                        Cursor parent = ancestor.getParent();
                        Cursor javaParent = nearestJavaParent(ancestor);
                        if (ancestor.getValue() instanceof J.Block
                                && javaParent != null
                                && javaParent.getValue() instanceof J.If enclosing
                                && guaranteesNonNull(
                                    enclosing.getIfCondition().getTree(), identifier)) {
                            return true;
                        }
                        ancestor = parent;
                    }
                    return false;
                }

                private Cursor nearestJavaParent(Cursor cursor) {
                    Cursor parent = cursor.getParent();
                    while (parent != null && !(parent.getValue() instanceof J)) {
                        parent = parent.getParent();
                    }
                    return parent;
                }

                private boolean guaranteesNonNull(
                        Expression condition, J.Identifier identifier) {
                    if (!(condition instanceof J.Binary binary)) return false;
                    if (binary.getOperator() == J.Binary.Type.And) {
                        return guaranteesNonNull(binary.getLeft(), identifier)
                            || guaranteesNonNull(binary.getRight(), identifier);
                    }
                    if (binary.getOperator() != J.Binary.Type.NotEqual) return false;
                    return isIdentifier(binary.getLeft(), identifier) && isNull(binary.getRight())
                        || isNull(binary.getLeft()) && isIdentifier(binary.getRight(), identifier);
                }

                private boolean isNullExitGuard(J.Identifier identifier, Statement statement) {
                    if (!(statement instanceof J.If nullCheck)
                            || nullCheck.getElsePart() != null
                            || !(nullCheck.getIfCondition().getTree() instanceof J.Binary condition)
                            || condition.getOperator() != J.Binary.Type.Equal
                            || !exitsPath(nullCheck.getThenPart())) {
                        return false;
                    }
                    return isIdentifier(condition.getLeft(), identifier) && isNull(condition.getRight())
                        || isNull(condition.getLeft()) && isIdentifier(condition.getRight(), identifier);
                }

                private boolean exitsPath(Statement statement) {
                    if (statement instanceof J.Continue
                            || statement instanceof J.Return
                            || statement instanceof J.Throw) {
                        return true;
                    }
                    return statement instanceof J.Block block
                        && block.getStatements().size() == 1
                        && exitsPath(block.getStatements().getFirst());
                }

                private boolean assigns(J.Identifier identifier, Statement statement) {
                    AtomicBoolean assigned = new AtomicBoolean();
                    new JavaVisitor<AtomicBoolean>() {
                        @Override
                        public J visitAssignment(J.Assignment assignment, AtomicBoolean found) {
                            if (isIdentifier(assignment.getVariable(), identifier)) found.set(true);
                            return super.visitAssignment(assignment, found);
                        }

                        @Override
                        public J visitAssignmentOperation(
                                J.AssignmentOperation assignment, AtomicBoolean found) {
                            if (isIdentifier(assignment.getVariable(), identifier)) found.set(true);
                            return super.visitAssignmentOperation(assignment, found);
                        }

                        @Override
                        public J visitVariableDeclarations(
                                J.VariableDeclarations declarations, AtomicBoolean found) {
                            if (declarations.getVariables().stream()
                                    .anyMatch(variable -> variable.getSimpleName()
                                        .equals(identifier.getSimpleName()))) {
                                found.set(true);
                            }
                            return super.visitVariableDeclarations(declarations, found);
                        }
                    }.visit(statement, assigned, new Cursor(null, statement));
                    return assigned.get();
                }

                private boolean isBooleanTrueEquals(J.MethodInvocation method) {
                    return "equals".equals(method.getSimpleName())
                        && method.getSelect() instanceof J.FieldAccess field
                        && field.getTarget() instanceof J.Identifier type
                        && "Boolean".equals(type.getSimpleName())
                        && "TRUE".equals(field.getSimpleName());
                }

                private boolean isIdentifier(Expression expression, J.Identifier expected) {
                    if (!(expression instanceof J.Identifier actual)
                            || !actual.getSimpleName().equals(expected.getSimpleName())) {
                        return false;
                    }
                    return expected.getFieldType() == null
                        || expected.getFieldType().equals(actual.getFieldType());
                }

                private boolean isNull(Expression expression) {
                    return expression instanceof J.Literal literal && literal.getValue() == null;
                }
            });
    }
}
