package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.List;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Folds a presence branch that maps an {@link java.util.Optional} through a static {@code of}
 * factory into
 * {@code map(...).orElseGet(...)}.
 *
 * <p>The deliberately narrow static-factory shape avoids changing arbitrary branches whose mapped
 * expression may have side effects. The fallback remains lazy.
 */
public final class UseOptionalMapOrElseGet extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use Optional.map and orElseGet for static factories";
    }

    @Override
    public String getDescription() {
        return "Replace an Optional presence return branch through a static factory with one lazy expression.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                List<Statement> statements = visited.getStatements();
                List<Statement> rewritten = new ArrayList<>(statements.size());

                for (int index = 0; index < statements.size(); index++) {
                    Statement statement = statements.get(index);
                    if (index + 1 < statements.size()
                            && statement instanceof J.If presenceBranch
                            && presenceBranch.getElsePart() == null
                            && statements.get(index + 1) instanceof J.Return fallbackReturn) {
                        Statement replacement = replacementFor(presenceBranch, fallbackReturn);
                        if (replacement != null) {
                            rewritten.add(replacement);
                            index++;
                            continue;
                        }
                    }
                    rewritten.add(statement);
                }
                return visited.withStatements(rewritten);
            }

            private Statement replacementFor(J.If presenceBranch, J.Return fallbackReturn) {
                if (!(presenceBranch.getIfCondition().getTree() instanceof J.MethodInvocation isPresent)
                        || !"isPresent".equals(isPresent.getSimpleName())
                        || !hasNoArguments(isPresent)
                        || isPresent.getMethodType() == null
                        || !TypeUtils.isOfClassType(
                                isPresent.getMethodType().getDeclaringType(), "java.util.Optional")
                        || !(isPresent.getSelect() instanceof J.Identifier optional)) {
                    return null;
                }

                J.Return presentReturn = returnStatement(presenceBranch.getThenPart());
                if (presentReturn == null
                        || !(presentReturn.getExpression() instanceof J.MethodInvocation factoryCall)
                        || factoryCall.getMethodType() == null
                        || !factoryCall.getMethodType().hasFlags(Flag.Static)
                        || !"of".equals(factoryCall.getSimpleName())
                        || factoryCall.getSelect() == null
                        || factoryCall.getArguments().size() != 1
                        || !isOptionalGet(factoryCall.getArguments().getFirst(), optional.getSimpleName())
                        || fallbackReturn.getExpression() == null) {
                    return null;
                }

                String factory = factoryCall.getSelect().printTrimmed(getCursor())
                        + "::" + factoryCall.getSimpleName();
                String fallback = fallbackReturn.getExpression().printTrimmed(getCursor());
                String code = "return " + optional.getSimpleName() + ".map(" + factory
                        + ").orElseGet(() -> " + fallback + ");";
                Cursor branchCursor = new Cursor(getCursor(), presenceBranch);
                return JavaTemplate.builder(code)
                        .contextSensitive()
                        .build()
                        .apply(branchCursor, presenceBranch.getCoordinates().replace());
            }

            private J.Return returnStatement(Statement statement) {
                if (statement instanceof J.Return directReturn) {
                    return directReturn;
                }
                if (statement instanceof J.Block body && body.getStatements().size() == 1
                        && body.getStatements().getFirst() instanceof J.Return blockReturn) {
                    return blockReturn;
                }
                return null;
            }

            private boolean isOptionalGet(Expression expression, String optionalName) {
                return expression instanceof J.MethodInvocation get
                        && "get".equals(get.getSimpleName())
                        && hasNoArguments(get)
                        && get.getSelect() instanceof J.Identifier receiver
                        && optionalName.equals(receiver.getSimpleName());
            }

            private boolean hasNoArguments(J.MethodInvocation invocation) {
                return invocation.getArguments().isEmpty()
                        || invocation.getArguments().size() == 1
                        && invocation.getArguments().getFirst() instanceof J.Empty;
            }
        };
    }
}
