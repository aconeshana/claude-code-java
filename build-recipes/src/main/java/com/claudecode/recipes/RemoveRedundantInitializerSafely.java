package com.claudecode.recipes;

import java.util.ArrayList;
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
import org.openrewrite.java.tree.TypeTree;

/**
 * Removes pure literal initializers (such as {@code = null}, {@code = false}, {@code = 0}) from
 * local variable declarations when every reachable execution path definitely overwrites the
 * variable before any read in accordance with JLS Chapter 16 definite assignment rules.
 */
public final class RemoveRedundantInitializerSafely extends Recipe {

    private enum BranchOutcome {
        OVERWRITES,
        EXITS,
        UNSAFE
    }

    @Override
    public String getDisplayName() {
        return "Remove redundant local variable initializers safely";
    }

    @Override
    public String getDescription() {
        return "Strips pure literal initializers on local variables when subsequent statements "
                + "provably overwrite the variable on every path before any read (including "
                + "exception edges to catch and finally blocks).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                if (getCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                    return visited;
                }

                List<Statement> statements = visited.getStatements();
                if (statements.size() < 2) {
                    return visited;
                }

                List<Statement> rewritten = null;
                for (int i = 0; i < statements.size() - 1; i++) {
                    Statement stmt = statements.get(i);
                    if (stmt instanceof J.VariableDeclarations decls) {
                        J.VariableDeclarations stripped =
                                tryStripInitializer(decls, statements.subList(i + 1, statements.size()));
                        if (stripped != null) {
                            if (rewritten == null) {
                                rewritten = new ArrayList<>(statements);
                            }
                            rewritten.set(i, stripped);
                        }
                    }
                }

                return rewritten != null ? visited.withStatements(rewritten) : visited;
            }
        };
    }

    private static J.VariableDeclarations tryStripInitializer(
            J.VariableDeclarations decls, List<Statement> remaining) {
        if (decls.getVariables().size() != 1
                || decls.hasModifier(J.Modifier.Type.Final)
                || decls.getTypeExpression() == null
                || isVarType(decls.getTypeExpression())) {
            return null;
        }

        J.VariableDeclarations.NamedVariable namedVar = decls.getVariables().getFirst();
        Expression initializer = namedVar.getInitializer();
        if (!namedVar.getDimensionsAfterName().isEmpty()
                || initializer == null
                || !isPureLiteral(initializer)) {
            return null;
        }

        JavaType.Variable targetVar = namedVar.getVariableType();
        String varName = namedVar.getSimpleName();
        if (targetVar == null
                || targetVar.getOwner() instanceof JavaType.FullyQualified
                || "_".equals(varName)) {
            return null;
        }

        if (!hasReadReference(remaining, targetVar, varName)) {
            return null;
        }

        if (!isDefinitelyOverwrittenBeforeRead(remaining, targetVar, varName)) {
            return null;
        }

        return decls.withVariables(List.of(namedVar.withInitializer(null)));
    }

    private static boolean isVarType(TypeTree typeExpression) {
        return typeExpression instanceof J.Identifier id && "var".equals(id.getSimpleName());
    }

    private static boolean isPureLiteral(Expression expression) {
        Expression unwrapped = expression.unwrap();
        if (unwrapped instanceof J.Literal) {
            return true;
        }
        return unwrapped instanceof J.Unary unary
                && (unary.getOperator() == J.Unary.Type.Negative
                        || unary.getOperator() == J.Unary.Type.Positive)
                && unary.getExpression().unwrap() instanceof J.Literal;
    }

    private static boolean isDefinitelyOverwrittenBeforeRead(
            List<Statement> remaining, JavaType.Variable targetVar, String varName) {
        for (int i = 0; i < remaining.size(); i++) {
            Statement stmt = remaining.get(i);
            if (!referencesVariable(stmt, targetVar, varName)) {
                if (unconditionallyExits(stmt)) {
                    return false;
                }
                continue;
            }

            List<Statement> afterStmt = remaining.subList(i + 1, remaining.size());
            if (isDirectOverwrite(stmt, targetVar, varName)) {
                return true;
            }
            if (stmt instanceof J.If iff && iff.getElsePart() != null) {
                if (referencesVariable(iff.getIfCondition(), targetVar, varName)) {
                    return false;
                }
                BranchOutcome thenOut = analyzeBranch(iff.getThenPart(), targetVar, varName);
                BranchOutcome elseOut =
                        analyzeBranch(iff.getElsePart().getBody(), targetVar, varName);
                return thenOut != BranchOutcome.UNSAFE
                        && elseOut != BranchOutcome.UNSAFE
                        && (thenOut == BranchOutcome.OVERWRITES
                                || elseOut == BranchOutcome.OVERWRITES);
            }
            if (stmt instanceof J.Try tryStmt) {
                boolean usedAfterTry = referencesVariableInList(afterStmt, targetVar, varName);
                return isSafeTryOverwrite(tryStmt, usedAfterTry, targetVar, varName);
            }
            return false;
        }
        return false;
    }

    private static BranchOutcome analyzeBranch(
            Statement branch, JavaType.Variable targetVar, String varName) {
        List<Statement> stmts =
                branch instanceof J.Block block ? block.getStatements() : List.of(branch);
        for (Statement stmt : stmts) {
            if (!referencesVariable(stmt, targetVar, varName)) {
                if (unconditionallyExits(stmt)) {
                    return BranchOutcome.EXITS;
                }
                continue;
            }

            if (isDirectOverwrite(stmt, targetVar, varName)) {
                return BranchOutcome.OVERWRITES;
            }
            if (stmt instanceof J.If iff
                    && iff.getElsePart() != null
                    && !referencesVariable(iff.getIfCondition(), targetVar, varName)) {
                BranchOutcome thenOut = analyzeBranch(iff.getThenPart(), targetVar, varName);
                BranchOutcome elseOut =
                        analyzeBranch(iff.getElsePart().getBody(), targetVar, varName);
                if (thenOut == BranchOutcome.UNSAFE || elseOut == BranchOutcome.UNSAFE) {
                    return BranchOutcome.UNSAFE;
                }
                return (thenOut == BranchOutcome.OVERWRITES || elseOut == BranchOutcome.OVERWRITES)
                        ? BranchOutcome.OVERWRITES
                        : BranchOutcome.EXITS;
            }
            if (stmt instanceof J.Try nestedTry
                    && isSafeTryOverwrite(nestedTry, true, targetVar, varName)) {
                return BranchOutcome.OVERWRITES;
            }
            return BranchOutcome.UNSAFE;
        }
        return BranchOutcome.UNSAFE;
    }

    private static boolean isSafeTryOverwrite(
            J.Try tryStmt,
            boolean mustBeAssignedAfterTry,
            JavaType.Variable targetVar,
            String varName) {
        if (tryStmt.getResources() != null) {
            for (J.Try.Resource resource : tryStmt.getResources()) {
                if (referencesVariable(resource, targetVar, varName)) {
                    return false;
                }
            }
        }
        if (tryStmt.getFinally() != null
                && referencesVariable(tryStmt.getFinally(), targetVar, varName)) {
            return false;
        }
        if (analyzeBranch(tryStmt.getBody(), targetVar, varName) != BranchOutcome.OVERWRITES) {
            return false;
        }
        for (J.Try.Catch catchClause : tryStmt.getCatches()) {
            if (referencesVariable(catchClause.getParameter(), targetVar, varName)) {
                return false;
            }
            if (!mustBeAssignedAfterTry
                    && !referencesVariable(catchClause.getBody(), targetVar, varName)) {
                continue;
            }
            if (analyzeBranch(catchClause.getBody(), targetVar, varName) == BranchOutcome.UNSAFE) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDirectOverwrite(
            Statement stmt, JavaType.Variable targetVar, String varName) {
        if (!(stmt instanceof J.Assignment assignment)) {
            return false;
        }
        Expression lhs = assignment.getVariable().unwrap();
        return lhs instanceof J.Identifier id
                && varName.equals(id.getSimpleName())
                && targetVar.equals(id.getFieldType())
                && !referencesVariable(assignment.getAssignment(), targetVar, varName);
    }

    private static boolean unconditionallyExits(Statement stmt) {
        if (stmt instanceof J.Return || stmt instanceof J.Throw) {
            return true;
        }
        if (stmt instanceof J.Block block) {
            for (Statement inner : block.getStatements()) {
                if (unconditionallyExits(inner)) {
                    return true;
                }
            }
            return false;
        }
        if (stmt instanceof J.If iff && iff.getElsePart() != null) {
            return unconditionallyExits(iff.getThenPart())
                    && unconditionallyExits(iff.getElsePart().getBody());
        }
        return false;
    }

    private static boolean referencesVariableInList(
            List<Statement> statements, JavaType.Variable targetVar, String varName) {
        for (Statement stmt : statements) {
            if (referencesVariable(stmt, targetVar, varName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesVariable(J subtree, JavaType.Variable targetVar, String varName) {
        AtomicBoolean found = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean flag) {
                if (flag.get()) {
                    return identifier;
                }
                if (isFieldSelectName(getCursor(), identifier)) {
                    return identifier;
                }
                if (varName.equals(identifier.getSimpleName())
                        && (identifier.getFieldType() == null
                                || targetVar.equals(identifier.getFieldType()))) {
                    flag.set(true);
                }
                return identifier;
            }
        }.visit(subtree, found);
        return found.get();
    }

    private static boolean hasReadReference(
            List<Statement> statements, JavaType.Variable targetVar, String varName) {
        AtomicBoolean found = new AtomicBoolean(false);
        JavaIsoVisitor<AtomicBoolean> visitor = new JavaIsoVisitor<>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean flag) {
                if (flag.get() || isFieldSelectName(getCursor(), identifier)) {
                    return identifier;
                }
                if (varName.equals(identifier.getSimpleName())
                        && targetVar.equals(identifier.getFieldType())
                        && !isPureWriteTarget(getCursor(), identifier)) {
                    flag.set(true);
                }
                return identifier;
            }
        };
        for (Statement stmt : statements) {
            visitor.visit(stmt, found);
            if (found.get()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFieldSelectName(Cursor cursor, J.Identifier identifier) {
        Cursor parent = cursor.getParentTreeCursor();
        return parent.getValue() instanceof J.FieldAccess fa && fa.getName() == identifier;
    }

    private static boolean isPureWriteTarget(Cursor cursor, J.Identifier identifier) {
        Expression top = identifier;
        Cursor parent = cursor.getParentTreeCursor();
        while (parent.getValue() instanceof J.Parentheses<?> parens) {
            top = parens;
            parent = parent.getParentTreeCursor();
        }
        if (parent.getValue() instanceof J.Assignment assign && assign.getVariable() == top) {
            return true;
        }
        return parent.getValue() instanceof J.VariableDeclarations.NamedVariable nv
                && nv.getName() == identifier;
    }
}
