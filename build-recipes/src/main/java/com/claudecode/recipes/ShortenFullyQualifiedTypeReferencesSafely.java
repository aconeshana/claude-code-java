package com.claudecode.recipes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Shortens fully qualified type references only when every simple type name in the compilation
 * unit resolves unambiguously.
 *
 * <p>The upstream recipe tracks one type per simple name. A later occurrence can overwrite an
 * earlier, different type, allowing an unsafe import when a top-level type and an inherited or
 * nested type share a simple name. This wrapper deliberately skips the whole compilation unit in
 * that uncommon case.
 */
public final class ShortenFullyQualifiedTypeReferencesSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Shorten fully qualified type references safely";
    }

    @Override
    public String getDescription() {
        return "Adds imports for fully qualified type references only when no simple type name is ambiguous.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate =
            new ShortenFullyQualifiedTypeReferences().getVisitor();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx, Cursor parent) {
                if (tree instanceof J.CompilationUnit compilationUnit
                        && containsAmbiguousTypeName(compilationUnit)) {
                    return compilationUnit;
                }
                return delegate.visit(tree, ctx, parent);
            }
        };
    }

    private boolean containsAmbiguousTypeName(J.CompilationUnit compilationUnit) {
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
        return typesBySimpleName.values().stream().anyMatch(types -> types.size() > 1);
    }
}
