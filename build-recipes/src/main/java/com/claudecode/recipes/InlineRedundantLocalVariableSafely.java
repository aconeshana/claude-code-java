package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavadocVisitor;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Javadoc;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Inlines redundant local alias variables ({@code T alias = source;}) when {@code alias} has the
 * exact same type as {@code source}, {@code alias} is never mutated, and {@code source} is either
 * an effectively-final local/parameter or a non-static, non-volatile {@code final} instance field
 * of the enclosing class.
 */
public final class InlineRedundantLocalVariableSafely extends Recipe {

    @Override
    public String getDisplayName() {
        return "Inline redundant local alias variables safely";
    }

    @Override
    public String getDescription() {
        return "Inlines trivial local alias declarations (T alias = source;) when both variables "
                + "have identical static types, neither is mutated, and no concurrency snapshot, "
                + "type conversion, or name shadowing is involved.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            private final Set<String> removedDeclarationTypes = new HashSet<>();

            @Override
            public J.CompilationUnit visitCompilationUnit(
                    J.CompilationUnit cu, ExecutionContext ctx) {
                removedDeclarationTypes.clear();
                J.CompilationUnit visited = super.visitCompilationUnit(cu, ctx);
                if (removedDeclarationTypes.isEmpty() || visited.getImports().isEmpty()) {
                    return visited;
                }
                return pruneUnusedTypeImports(visited, removedDeclarationTypes);
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = super.visitBlock(block, ctx);
                if (getCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                    return visited;
                }

                J.MethodDeclaration enclosingMethod =
                        getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (enclosingMethod == null) {
                    return visited;
                }
                J.ClassDeclaration enclosingClass =
                        getCursor().firstEnclosing(J.ClassDeclaration.class);

                List<Statement> current = visited.getStatements();
                if (current.size() < 2) {
                    return visited;
                }

                boolean anyChanged = false;
                // Process from bottom to top so chained or adjacent aliases resolve cleanly.
                for (int i = current.size() - 2; i >= 0; i--) {
                    if (!(current.get(i) instanceof J.VariableDeclarations decls)) {
                        continue;
                    }
                    List<Statement> following = current.subList(i + 1, current.size());
                    J.Identifier sourceId =
                            eligibleAliasSource(
                                    decls, following, enclosingMethod, enclosingClass, getCursor());
                    if (sourceId == null) {
                        continue;
                    }

                    J.VariableDeclarations.NamedVariable aliasNamed =
                            decls.getVariables().getFirst();
                    JavaType.Variable aliasVar = aliasNamed.getVariableType();
                    String aliasName = aliasNamed.getSimpleName();
                    collectReferencedTypeNames(
                            decls.getTypeExpression().getType(), removedDeclarationTypes);

                    List<Statement> updated = new ArrayList<>(current.size() - 1);
                    updated.addAll(current.subList(0, i));
                    for (int k = 0; k < following.size(); k++) {
                        Statement stmt =
                                replaceAliasReferences(following.get(k), aliasVar, aliasName, sourceId);
                        if (k == 0) {
                            stmt = mergeLeadingFormatting(decls.getPrefix(), stmt);
                        }
                        updated.add(stmt);
                    }
                    current = updated;
                    anyChanged = true;
                }

                return anyChanged ? visited.withStatements(current) : visited;
            }
        };
    }

    private static void collectReferencedTypeNames(JavaType type, Set<String> sink) {
        if (type == null) {
            return;
        }
        if (type instanceof JavaType.Array array) {
            collectReferencedTypeNames(array.getElemType(), sink);
            return;
        }
        if (type instanceof JavaType.Parameterized parameterized) {
            sink.add(parameterized.getType().getFullyQualifiedName());
            for (JavaType arg : parameterized.getTypeParameters()) {
                collectReferencedTypeNames(arg, sink);
            }
            return;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq != null) {
            sink.add(fq.getFullyQualifiedName());
        }
    }

    private static J.CompilationUnit pruneUnusedTypeImports(
            J.CompilationUnit cu, Set<String> candidateFqns) {
        Set<String> usedSimpleNames = new HashSet<>();
        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.Import visitImport(J.Import imp, Set<String> names) {
                return imp;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Set<String> names) {
                names.add(identifier.getSimpleName());
                return super.visitIdentifier(identifier, names);
            }

            @Override
            protected JavadocVisitor<Set<String>> getJavadocVisitor() {
                return new JavadocVisitor<>(this) {
                    @Override
                    public Javadoc visitReference(Javadoc.Reference reference, Set<String> names) {
                        if (reference.getTree() != null) {
                            visit(reference.getTree(), names);
                        }
                        return super.visitReference(reference, names);
                    }
                };
            }
        }.visit(cu, usedSimpleNames);

        List<J.Import> original = cu.getImports();
        Space firstPrefix = original.getFirst().getPrefix();
        List<J.Import> kept = new ArrayList<>(original.size());
        for (J.Import imp : original) {
            if (!imp.isStatic()
                    && candidateFqns.contains(imp.getTypeName())
                    && !usedSimpleNames.contains(imp.getQualid().getSimpleName())) {
                continue;
            }
            kept.add(imp);
        }
        if (kept.size() == original.size()) {
            return cu;
        }
        if (!kept.isEmpty() && kept.getFirst() != original.getFirst()) {
            kept.set(0, kept.getFirst().withPrefix(firstPrefix));
        }
        return cu.withImports(kept);
    }

    private static J.Identifier eligibleAliasSource(
            J.VariableDeclarations decls,
            List<Statement> following,
            J.MethodDeclaration enclosingMethod,
            J.ClassDeclaration enclosingClass,
            Cursor blockCursor) {
        if (decls.getVariables().size() != 1
                || !decls.getLeadingAnnotations().isEmpty()
                || decls.getTypeExpression() == null
                || decls.getTypeExpression() instanceof J.AnnotatedType
                || (decls.getTypeExpression() instanceof J.Identifier typeId
                        && !typeId.getAnnotations().isEmpty())
                || hasInnerComments(decls, blockCursor)) {
            return null;
        }

        J.VariableDeclarations.NamedVariable namedVar = decls.getVariables().getFirst();
        if (!namedVar.getDimensionsAfterName().isEmpty()
                || !(namedVar.getInitializer() instanceof J.Identifier sourceId)) {
            return null;
        }

        String aliasName = namedVar.getSimpleName();
        String sourceName = sourceId.getSimpleName();
        if ("_".equals(aliasName)
                || "this".equals(sourceName)
                || "super".equals(sourceName)
                || aliasName.equals(sourceName)
                // Preserve deliberate role-naming of single-character variables (e.g. `char quote = c;`).
                || (sourceName.length() == 1 && aliasName.length() > 1)) {
            return null;
        }

        JavaType.Variable aliasVar = namedVar.getVariableType();
        JavaType.Variable sourceVar = sourceId.getFieldType();
        if (aliasVar == null
                || sourceVar == null
                || aliasVar.getOwner() instanceof JavaType.FullyQualified
                || aliasVar.getType() == null
                || sourceVar.getType() == null
                || aliasVar.getType() instanceof JavaType.Unknown
                || sourceVar.getType() instanceof JavaType.Unknown
                || !TypeUtils.isOfType(aliasVar.getType(), sourceVar.getType())) {
            return null;
        }

        boolean sourceIsField = sourceVar.getOwner() instanceof JavaType.FullyQualified;
        if (sourceVar.getOwner() instanceof JavaType.Method) {
            if (!isEffectivelyFinalLocal(enclosingMethod, sourceVar, sourceName)) {
                return null;
            }
        } else if (sourceIsField) {
            if (!isSafeInstanceFinalField(sourceVar, enclosingMethod, enclosingClass, blockCursor)) {
                return null;
            }
        } else {
            return null;
        }

        if (!areAliasUsagesSafe(following, aliasVar, aliasName, sourceName, sourceIsField)) {
            return null;
        }

        return sourceId;
    }

    private static boolean isEffectivelyFinalLocal(
            J.MethodDeclaration enclosingMethod, JavaType.Variable sourceVar, String sourceName) {
        AtomicBoolean mutatedOrUninit = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(
                    J.VariableDeclarations.NamedVariable variable, AtomicBoolean bad) {
                if (sourceVar.equals(variable.getVariableType())) {
                    Cursor declCursor = getCursor().getParentTreeCursor();
                    Cursor ownerCursor = declCursor.getParentTreeCursor();
                    if (ownerCursor.getValue() instanceof J.Block
                            && variable.getInitializer() == null) {
                        bad.set(true);
                    }
                }
                return super.visitVariable(variable, bad);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, AtomicBoolean bad) {
                if (matchesIdentifier(assignment.getVariable().unwrap(), sourceVar, sourceName)) {
                    bad.set(true);
                }
                return super.visitAssignment(assignment, bad);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(
                    J.AssignmentOperation assignOp, AtomicBoolean bad) {
                if (matchesIdentifier(assignOp.getVariable().unwrap(), sourceVar, sourceName)) {
                    bad.set(true);
                }
                return super.visitAssignmentOperation(assignOp, bad);
            }

            @Override
            public J.Unary visitUnary(J.Unary unary, AtomicBoolean bad) {
                if (unary.getOperator().isModifying()
                        && matchesIdentifier(unary.getExpression().unwrap(), sourceVar, sourceName)) {
                    bad.set(true);
                }
                return super.visitUnary(unary, bad);
            }
        }.visit(enclosingMethod, mutatedOrUninit);
        return !mutatedOrUninit.get();
    }

    private static boolean isSafeInstanceFinalField(
            JavaType.Variable sourceVar,
            J.MethodDeclaration enclosingMethod,
            J.ClassDeclaration enclosingClass,
            Cursor blockCursor) {
        if (!sourceVar.hasFlags(Flag.Final)
                || sourceVar.hasFlags(Flag.Static)
                || sourceVar.hasFlags(Flag.Volatile)
                || enclosingMethod.isConstructor()
                || enclosingClass == null
                || enclosingClass.getType() == null) {
            return false;
        }
        // Ensure we are not inside an anonymous class whose `this` differs from `enclosingClass`.
        for (Cursor c = blockCursor; c != null; c = c.getParent()) {
            Object val = c.getValue();
            if (val == enclosingClass) {
                break;
            }
            if (val instanceof J.NewClass nc && nc.getBody() != null) {
                return false;
            }
        }
        return TypeUtils.isOfType(enclosingClass.getType(), sourceVar.getOwner());
    }

    private static boolean areAliasUsagesSafe(
            List<Statement> following,
            JavaType.Variable aliasVar,
            String aliasName,
            String sourceName,
            boolean sourceIsField) {
        AtomicBoolean unsafe = new AtomicBoolean(false);
        AtomicInteger readCount = new AtomicInteger(0);

        JavaIsoVisitor<Void> scanner = new JavaIsoVisitor<>() {
            private int nestedTypeDepth = 0;
            private int shadowingHierarchyDepth = 0;

            @Override
            public J.ClassDeclaration visitClassDeclaration(
                    J.ClassDeclaration classDecl, Void unused) {
                if (sourceName.equals(classDecl.getSimpleName())) {
                    unsafe.set(true);
                }
                boolean shadows = hierarchyDefinesField(classDecl.getType(), sourceName);
                nestedTypeDepth++;
                if (shadows) {
                    shadowingHierarchyDepth++;
                }
                try {
                    return super.visitClassDeclaration(classDecl, unused);
                } finally {
                    if (shadows) {
                        shadowingHierarchyDepth--;
                    }
                    nestedTypeDepth--;
                }
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, Void unused) {
                if (newClass.getBody() == null) {
                    return super.visitNewClass(newClass, unused);
                }
                JavaType superType =
                        newClass.getClazz() != null ? newClass.getClazz().getType() : newClass.getType();
                boolean shadows = hierarchyDefinesField(superType, sourceName);
                nestedTypeDepth++;
                if (shadows) {
                    shadowingHierarchyDepth++;
                }
                try {
                    return super.visitNewClass(newClass, unused);
                } finally {
                    if (shadows) {
                        shadowingHierarchyDepth--;
                    }
                    nestedTypeDepth--;
                }
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(
                    J.VariableDeclarations.NamedVariable variable, Void unused) {
                if (sourceName.equals(variable.getSimpleName())
                        || aliasName.equals(variable.getSimpleName())) {
                    unsafe.set(true);
                }
                return super.visitVariable(variable, unused);
            }

            @Override
            public J.InstanceOf visitInstanceOf(J.InstanceOf instanceOf, Void unused) {
                if (instanceOf.getPattern() instanceof J.Identifier patternId
                        && (sourceName.equals(patternId.getSimpleName())
                                || aliasName.equals(patternId.getSimpleName()))) {
                    unsafe.set(true);
                }
                return super.visitInstanceOf(instanceOf, unused);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment assignment, Void unused) {
                if (matchesIdentifier(assignment.getVariable().unwrap(), aliasVar, aliasName)) {
                    unsafe.set(true);
                }
                return super.visitAssignment(assignment, unused);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(
                    J.AssignmentOperation assignOp, Void unused) {
                if (matchesIdentifier(assignOp.getVariable().unwrap(), aliasVar, aliasName)) {
                    unsafe.set(true);
                }
                return super.visitAssignmentOperation(assignOp, unused);
            }

            @Override
            public J.Unary visitUnary(J.Unary unary, Void unused) {
                if (unary.getOperator().isModifying()
                        && matchesIdentifier(unary.getExpression().unwrap(), aliasVar, aliasName)) {
                    unsafe.set(true);
                }
                return super.visitUnary(unary, unused);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Void unused) {
                if (unsafe.get()) {
                    return identifier;
                }
                if (isFieldSelectName(getCursor(), identifier)) {
                    return identifier;
                }
                if (aliasName.equals(identifier.getSimpleName())) {
                    if (!aliasVar.equals(identifier.getFieldType())) {
                        unsafe.set(true);
                        return identifier;
                    }
                    if ((sourceIsField && nestedTypeDepth > 0) || shadowingHierarchyDepth > 0) {
                        unsafe.set(true);
                        return identifier;
                    }
                    readCount.incrementAndGet();
                }
                return identifier;
            }
        };

        for (Statement stmt : following) {
            scanner.visit(stmt, null);
            if (unsafe.get()) {
                return false;
            }
        }
        return readCount.get() >= 1;
    }

    private static Statement replaceAliasReferences(
            Statement stmt,
            JavaType.Variable aliasVar,
            String aliasName,
            J.Identifier sourceId) {
        return (Statement) new JavaIsoVisitor<Void>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Void unused) {
                if (isFieldSelectName(getCursor(), identifier)) {
                    return identifier;
                }
                if (aliasName.equals(identifier.getSimpleName())
                        && aliasVar.equals(identifier.getFieldType())) {
                    return identifier
                            .withSimpleName(sourceId.getSimpleName())
                            .withType(sourceId.getType())
                            .withFieldType(sourceId.getFieldType());
                }
                return identifier;
            }
        }.visitNonNull(stmt, null);
    }

    private static Statement mergeLeadingFormatting(Space removedPrefix, Statement nextStmt) {
        Space nextPrefix = nextStmt.getPrefix();
        List<Comment> combinedComments =
                ListUtils.concatAll(removedPrefix.getComments(), nextPrefix.getComments());
        if (!removedPrefix.getComments().isEmpty()) {
            return nextStmt.withPrefix(removedPrefix.withComments(combinedComments));
        }
        // Preserve intentional blank-line paragraph separation above the removed declaration.
        if (countNewlines(removedPrefix.getWhitespace()) > countNewlines(nextPrefix.getWhitespace())
                && nextPrefix.getComments().isEmpty()) {
            return nextStmt.withPrefix(removedPrefix);
        }
        return nextStmt;
    }

    private static int countNewlines(String whitespace) {
        int count = 0;
        for (int i = 0; i < whitespace.length(); i++) {
            if (whitespace.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static boolean hasInnerComments(J.VariableDeclarations decls, Cursor parentCursor) {
        AtomicBoolean found = new AtomicBoolean(false);
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public Space visitSpace(Space space, Space.Location loc, AtomicBoolean flag) {
                if (space != decls.getPrefix() && !space.getComments().isEmpty()) {
                    flag.set(true);
                }
                return space;
            }
        }.visit(decls, found, new Cursor(parentCursor, decls));
        return found.get();
    }

    private static boolean matchesIdentifier(
            Expression expr, JavaType.Variable targetVar, String targetName) {
        return expr instanceof J.Identifier id
                && targetName.equals(id.getSimpleName())
                && (id.getFieldType() == null || targetVar.equals(id.getFieldType()));
    }

    private static boolean hierarchyDefinesField(JavaType type, String fieldName) {
        if (type == null || type instanceof JavaType.Unknown) {
            return true;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null) {
            return false;
        }
        for (JavaType.Variable member : fq.getMembers()) {
            if (fieldName.equals(member.getName()) && !member.hasFlags(Flag.Private)) {
                return true;
            }
        }
        if (fq.getSupertype() != null && hierarchyDefinesField(fq.getSupertype(), fieldName)) {
            return true;
        }
        for (JavaType.FullyQualified iface : fq.getInterfaces()) {
            if (hierarchyDefinesField(iface, fieldName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFieldSelectName(Cursor cursor, J.Identifier identifier) {
        Cursor parent = cursor.getParentTreeCursor();
        return parent.getValue() instanceof J.FieldAccess fa && fa.getName() == identifier;
    }
}
