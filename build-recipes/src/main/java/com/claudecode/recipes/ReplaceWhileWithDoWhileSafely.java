package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.SemanticallyEqual;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.marker.Markers;

/**
 * Merges statements immediately preceding a {@code while} loop with an identical loop body into a
 * {@code do-while} loop, mirroring IntelliJ IDEA's {@code WhileCanBeDoWhile} inspection.
 */
public final class ReplaceWhileWithDoWhileSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Replace 'while' with 'do-while' when preceded by an identical body";
    }

    @Override
    public String getDescription() {
        return "Merges statements immediately preceding a while loop with a semantically identical"
                + " loop body into a do-while loop when control flow and variable scopes stay"
                + " unchanged.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                List<Statement> statements = visited.getStatements();
                if (statements.size() < 2) {
                    return visited;
                }

                List<Statement> rewritten = new ArrayList<>(statements.size());
                boolean changed = false;

                for (Statement statement : statements) {
                    if (statement instanceof J.WhileLoop whileLoop) {
                        J.DoWhileLoop replacement = tryMerge(rewritten, whileLoop);
                        if (replacement != null) {
                            int bodySize = bodyStatementsOf(whileLoop).size();
                            rewritten.subList(rewritten.size() - bodySize, rewritten.size()).clear();
                            rewritten.add(replacement);
                            changed = true;
                            continue;
                        }
                    }
                    rewritten.add(statement);
                }

                return changed ? visited.withStatements(rewritten) : visited;
            }

            private J.DoWhileLoop tryMerge(List<Statement> preceding, J.WhileLoop whileLoop) {
                if (!whileLoop.getPrefix().getComments().isEmpty()) {
                    return null;
                }
                List<Statement> bodyStatements = bodyStatementsOf(whileLoop);
                if (bodyStatements.isEmpty()
                        || (bodyStatements.size() == 1 && bodyStatements.getFirst() instanceof J.Empty)
                        || preceding.size() < bodyStatements.size()) {
                    return null;
                }

                int start = preceding.size() - bodyStatements.size();
                List<Statement> prefixStatements = preceding.subList(start, preceding.size());

                for (int i = 0; i < bodyStatements.size(); i++) {
                    Statement prefixStmt = prefixStatements.get(i);
                    Statement bodyStmt = bodyStatements.get(i);
                    if (i > 0 && !prefixStmt.getPrefix().getComments().isEmpty()) {
                        return null;
                    }
                    if (prefixStmt instanceof J.VariableDeclarations
                            || bodyStmt instanceof J.VariableDeclarations) {
                        return null;
                    }
                    if (hasInnerComments(prefixStmt, i == 0)
                            || hasUnsafeControlFlow(prefixStmt)
                            || hasUnsafeControlFlow(bodyStmt)) {
                        return null;
                    }
                    if (!SemanticallyEqual.areEqual(prefixStmt, bodyStmt)) {
                        return null;
                    }
                }

                Space loopPrefix = prefixStatements.getFirst().getPrefix();
                J.Block doBody = buildDoBody(whileLoop.getBody(), loopPrefix);
                J.ControlParentheses<org.openrewrite.java.tree.Expression> condition =
                        whileLoop.getCondition().getPrefix().isEmpty()
                                ? whileLoop.getCondition().withPrefix(Space.SINGLE_SPACE)
                                : whileLoop.getCondition();

                return new J.DoWhileLoop(
                        Tree.randomId(),
                        loopPrefix,
                        Markers.EMPTY,
                        JRightPadded.build((Statement) doBody).withAfter(Space.SINGLE_SPACE),
                        JLeftPadded.build(condition));
            }

            private List<Statement> bodyStatementsOf(J.WhileLoop whileLoop) {
                if (whileLoop.getBody() instanceof J.Block bodyBlock) {
                    return bodyBlock.getStatements();
                }
                return List.of(whileLoop.getBody());
            }

            private J.Block buildDoBody(Statement body, Space loopPrefix) {
                if (body instanceof J.Block bodyBlock) {
                    return bodyBlock.getPrefix().isEmpty()
                            ? bodyBlock.withPrefix(Space.SINGLE_SPACE)
                            : bodyBlock;
                }
                String indent = loopPrefix.getIndent();
                Statement indented = body.withPrefix(Space.format("\n" + indent + "    "));
                return J.Block.createEmptyBlock()
                        .withPrefix(Space.SINGLE_SPACE)
                        .withStatements(List.of(indented))
                        .withEnd(Space.format("\n" + indent));
            }

            private boolean hasInnerComments(Statement statement, boolean allowLeadingPrefixComments) {
                AtomicBoolean found = new AtomicBoolean();
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public Space visitSpace(Space space, Space.Location loc, AtomicBoolean result) {
                        if (!space.getComments().isEmpty()) {
                            if (!(allowLeadingPrefixComments && space == statement.getPrefix())) {
                                result.set(true);
                            }
                        }
                        return space;
                    }
                }.visit(statement, found, new Cursor(getCursor(), statement));
                return found.get();
            }

            private boolean hasUnsafeControlFlow(Statement statement) {
                AtomicBoolean unsafe = new AtomicBoolean();
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.Break visitBreak(J.Break breakStatement, AtomicBoolean result) {
                        result.set(true);
                        return breakStatement;
                    }

                    @Override
                    public J.Continue visitContinue(J.Continue continueStatement, AtomicBoolean result) {
                        result.set(true);
                        return continueStatement;
                    }

                    @Override
                    public J.Yield visitYield(J.Yield yieldStatement, AtomicBoolean result) {
                        result.set(true);
                        return yieldStatement;
                    }

                    @Override
                    public J.ForLoop visitForLoop(J.ForLoop forLoop, AtomicBoolean result) {
                        return forLoop;
                    }

                    @Override
                    public J.ForEachLoop visitForEachLoop(J.ForEachLoop forEachLoop, AtomicBoolean result) {
                        return forEachLoop;
                    }

                    @Override
                    public J.WhileLoop visitWhileLoop(J.WhileLoop whileLoop, AtomicBoolean result) {
                        return whileLoop;
                    }

                    @Override
                    public J.DoWhileLoop visitDoWhileLoop(J.DoWhileLoop doWhileLoop, AtomicBoolean result) {
                        return doWhileLoop;
                    }

                    @Override
                    public J.Switch visitSwitch(J.Switch switchStatement, AtomicBoolean result) {
                        return switchStatement;
                    }

                    @Override
                    public J.Lambda visitLambda(J.Lambda lambda, AtomicBoolean result) {
                        return lambda;
                    }

                    @Override
                    public J.ClassDeclaration visitClassDeclaration(
                            J.ClassDeclaration classDeclaration, AtomicBoolean result) {
                        return classDeclaration;
                    }
                }.visit(statement, unsafe, new Cursor(getCursor(), statement));
                return unsafe.get();
            }
        };
    }
}
