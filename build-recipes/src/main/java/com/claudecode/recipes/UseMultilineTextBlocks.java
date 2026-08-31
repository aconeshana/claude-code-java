package com.claudecode.recipes;

import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.migrate.lang.UseTextBlocks;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Converts concatenated string literals to text blocks only when the resulting value contains a
 * newline. The official recipe's broader conversion of long single-line values is intentionally
 * disabled to avoid rewriting wrapped prompts and snapshots that are not logically multiline.
 */
public final class UseMultilineTextBlocks extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use text blocks for multiline strings";
    }

    @Override
    public String getDescription() {
        return "Replace concatenated string literals whose value contains newlines with text blocks.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = new UseTextBlocks(false, false).getVisitor();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx, Cursor parent) {
                if (tree instanceof J.CompilationUnit compilationUnit
                        && containsWhitespaceSensitiveReplaceKey(compilationUnit)) {
                    return compilationUnit;
                }
                return delegate.visit(tree, ctx, parent);
            }
        };
    }

    private boolean containsWhitespaceSensitiveReplaceKey(J.CompilationUnit compilationUnit) {
        AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(
                    J.MethodInvocation invocation, AtomicBoolean sensitive) {
                if ("replace".equals(invocation.getSimpleName())
                        && invocation.getMethodType() != null
                        && TypeUtils.isOfClassType(
                                invocation.getMethodType().getDeclaringType(), "java.lang.String")
                        && !invocation.getArguments().isEmpty()
                        && invocation.getArguments().getFirst() instanceof J.Binary) {
                    sensitive.set(true);
                    return invocation;
                }
                return super.visitMethodInvocation(invocation, sensitive);
            }
        }.visit(compilationUnit, found, new Cursor(null, compilationUnit));
        return found.get();
    }
}
