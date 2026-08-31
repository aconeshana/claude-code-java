package com.claudecode.recipes;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Replaces String reference equality used as the null branch of a value comparison with Apache
 * Commons Lang's null-safe value equality.
 */
public final class UseNullSafeStringEquality extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use null-safe String value equality";
    }

    @Override
    public String getDescription() {
        return "Replace `left == right` or `left != right` with `Strings.CS.equals` when an "
            + "enclosing null guard proves the comparison is expressing null-safe value equality.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitBinary(J.Binary binary, ExecutionContext ctx) {
                J.Binary visited = (J.Binary) super.visitBinary(binary, ctx);
                if ((visited.getOperator() != J.Binary.Type.Equal
                        && visited.getOperator() != J.Binary.Type.NotEqual)
                        || !TypeUtils.isOfClassType(visited.getLeft().getType(), "java.lang.String")
                        || !TypeUtils.isOfClassType(visited.getRight().getType(), "java.lang.String")
                        || !isNullGuardedReturn(visited.getLeft(), visited.getRight())) {
                    return visited;
                }

                maybeAddImport("org.apache.commons.lang3.Strings");
                String template = visited.getOperator() == J.Binary.Type.NotEqual
                    ? "!Strings.CS.equals(#{any(java.lang.String)}, #{any(java.lang.String)})"
                    : "Strings.CS.equals(#{any(java.lang.String)}, #{any(java.lang.String)})";
                return JavaTemplate.builder(template)
                    .imports("org.apache.commons.lang3.Strings")
                    .build()
                    .apply(updateCursor(visited), visited.getCoordinates().replace(),
                        visited.getLeft(), visited.getRight());
            }

            private boolean isNullGuardedReturn(Expression left, Expression right) {
                Cursor parent = getCursor().getParentTreeCursor();
                if (!(parent.getValue() instanceof J.Return)
                        || !(parent.getParentTreeCursor().getValue() instanceof J.If nullGuard)
                        || nullGuard.getElsePart() != null
                        || !(nullGuard.getIfCondition().getTree() instanceof J.Binary condition)
                        || condition.getOperator() != J.Binary.Type.Or
                        || nullGuard.getThenPart() != parent.getValue()) {
                    return false;
                }
                return checksNull(condition.getLeft(), left)
                        && checksNull(condition.getRight(), right)
                    || checksNull(condition.getLeft(), right)
                        && checksNull(condition.getRight(), left);
            }

            private boolean checksNull(Expression expression, Expression expected) {
                if (!(expression instanceof J.Binary check)
                        || check.getOperator() != J.Binary.Type.Equal) {
                    return false;
                }
                return sameVariable(check.getLeft(), expected) && isNull(check.getRight())
                    || isNull(check.getLeft()) && sameVariable(check.getRight(), expected);
            }

            private boolean sameVariable(Expression expression, Expression expected) {
                if (!(expression instanceof J.Identifier actual)
                        || !(expected instanceof J.Identifier wanted)
                        || !actual.getSimpleName().equals(wanted.getSimpleName())) {
                    return false;
                }
                return wanted.getFieldType() == null
                    || wanted.getFieldType().equals(actual.getFieldType());
            }

            private boolean isNull(Expression expression) {
                return expression instanceof J.Literal literal && literal.getValue() == null;
            }
        };
    }
}
