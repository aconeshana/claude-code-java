package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * Replaces {@code Iterator}-based {@code while (it.hasNext())} and {@code for (...; it.hasNext(); )}
 * loops over an {@link Iterable} with enhanced {@code for} loops, mirroring IntelliJ IDEA's
 * {@code WhileLoopReplaceableByForEach} inspection.
 */
public final class ReplaceWhileWithEnhancedForSafely extends Recipe {

    private static final MethodMatcher ITERABLE_ITERATOR =
        new MethodMatcher("java.lang.Iterable iterator()", true);
    private static final MethodMatcher ITERATOR_HAS_NEXT =
        new MethodMatcher("java.util.Iterator hasNext()", true);
    private static final MethodMatcher ITERATOR_NEXT =
        new MethodMatcher("java.util.Iterator next()", true);

    @Override
    public String getDisplayName() {
        return "Replace 'while' loop over Iterator with enhanced 'for' loop";
    }

    @Override
    public String getDescription() {
        return "Replaces Iterator-based while and for loops over an Iterable with an enhanced "
            + "for-each loop when the iterator is only consumed once per iteration via next() "
            + "and never escapes or calls remove().";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<>() {

            @Override
            public J visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block visited = (J.Block) super.visitBlock(block, ctx);
                List<Statement> statements = new ArrayList<>(visited.getStatements());
                if (statements.size() < 2) {
                    return visited;
                }

                boolean changed = false;
                // Walk backwards so that a reassigned iterator variable (e.g.
                // `it = col.iterator(); while (it.hasNext()) ...`) is rewritten first,
                // clearing trailing references and allowing an earlier declaration+loop to match.
                for (int index = statements.size() - 2; index >= 0; index--) {
                    Statement first = statements.get(index);
                    Statement second = statements.get(index + 1);
                    if (!(second instanceof J.WhileLoop whileLoop)) {
                        continue;
                    }
                    List<Statement> following = statements.subList(index + 2, statements.size());
                    J.ForEachLoop replacement = rewriteWhileLoop(first, whileLoop, following);
                    if (replacement != null) {
                        statements.set(index, replacement);
                        statements.remove(index + 1);
                        changed = true;
                    }
                }

                return changed ? visited.withStatements(statements) : visited;
            }

            @Override
            public J visitForLoop(J.ForLoop forLoop, ExecutionContext ctx) {
                J.ForLoop visited = (J.ForLoop) super.visitForLoop(forLoop, ctx);
                J.ForEachLoop replacement = rewriteForLoop(visited);
                return replacement != null ? replacement : visited;
            }

            private J.ForEachLoop rewriteWhileLoop(
                    Statement initStatement,
                    J.WhileLoop whileLoop,
                    List<Statement> followingStatements) {
                if (!whileLoop.getPrefix().getComments().isEmpty()) {
                    return null;
                }

                IteratorBinding binding = extractIteratorBinding(initStatement);
                if (binding == null
                        || !isHasNextCall(whileLoop.getCondition().getTree(), binding)
                        || referencesVariable(followingStatements, binding)) {
                    return null;
                }

                return buildForEachLoop(
                    initStatement.getPrefix(), whileLoop, whileLoop.getBody(), binding);
            }

            private J.ForEachLoop rewriteForLoop(J.ForLoop forLoop) {
                J.ForLoop.Control control = forLoop.getControl();
                if (control.getInit().size() != 1
                        || !isEmptyUpdates(control.getUpdate())) {
                    return null;
                }
                IteratorBinding binding = extractIteratorBinding(control.getInit().getFirst());
                if (binding == null || !isHasNextCall(control.getCondition(), binding)) {
                    return null;
                }
                return buildForEachLoop(
                    forLoop.getPrefix(), forLoop, forLoop.getBody(), binding);
            }

            private J.ForEachLoop buildForEachLoop(
                    Space loopPrefix,
                    Statement loopStatement,
                    Statement loopBody,
                    IteratorBinding binding) {
                // Case A: First statement in a block body is `T elem = it.next();`
                if (loopBody instanceof J.Block bodyBlock && !bodyBlock.getStatements().isEmpty()) {
                    Statement firstStmt = bodyBlock.getStatements().getFirst();
                    if (firstStmt instanceof J.VariableDeclarations elemDecl
                            && elemDecl.getVariables().size() == 1
                            && elemDecl.getPrefix().getComments().isEmpty()) {
                        J.VariableDeclarations.NamedVariable elemVar = elemDecl.getVariables().getFirst();
                        if (isNextCall(elemVar.getInitializer(), binding)) {
                            List<Statement> remaining = new ArrayList<>(
                                bodyBlock.getStatements().subList(1, bodyBlock.getStatements().size()));
                            if (!referencesVariable(remaining, binding)) {
                                J.VariableDeclarations loopVar = elemDecl
                                    .withPrefix(Space.EMPTY)
                                    .withVariables(List.of(elemVar.withInitializer(null)));
                                J.Block newBody = bodyBlock.withStatements(remaining);
                                return createForEachLoop(loopPrefix, loopVar, binding.iterable(), newBody);
                            }
                            return null;
                        }
                    }
                }

                // Case B: Inline `it.next()` expression evaluated unconditionally once per iteration
                J.MethodInvocation inlineNext = findSingleUnconditionalNextCall(loopBody, binding);
                if (inlineNext == null) {
                    return null;
                }

                JavaType elementType = inlineNext.getType();
                String varName = chooseLoopVariableName(binding.name(), elementType, loopBody);
                JavaType.Variable varFieldType = new JavaType.Variable(
                    null, 0, varName, null, elementType, List.of());

                J.Identifier varType = new J.Identifier(
                    Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(), "var", elementType, null);
                J.Identifier varDeclId = new J.Identifier(
                    Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, List.of(),
                    varName, elementType, varFieldType);
                J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                    Tree.randomId(), Space.EMPTY, Markers.EMPTY, varDeclId, List.of(), null, varFieldType);
                J.VariableDeclarations loopVar = new J.VariableDeclarations(
                    Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(), List.of(),
                    varType, null, List.of(JRightPadded.build(namedVar)));

                Statement newBody = replaceNextCall(loopBody, inlineNext, varName, elementType, varFieldType);
                maybeRemoveImport("java.util.Map");
                return createForEachLoop(loopPrefix, loopVar, binding.iterable(), newBody);
            }

            private J.ForEachLoop createForEachLoop(
                    Space loopPrefix,
                    J.VariableDeclarations loopVar,
                    Expression iterable,
                    Statement body) {
                maybeRemoveImport("java.util.Iterator");
                J.ForEachLoop.Control control = new J.ForEachLoop.Control(
                    Tree.randomId(),
                    Space.SINGLE_SPACE,
                    Markers.EMPTY,
                    JRightPadded.build((Statement) loopVar).withAfter(Space.SINGLE_SPACE),
                    JRightPadded.build(iterable.withPrefix(Space.SINGLE_SPACE)));
                Statement formattedBody = body.getPrefix().isEmpty()
                    ? body.withPrefix(Space.SINGLE_SPACE)
                    : body;
                return new J.ForEachLoop(
                    Tree.randomId(),
                    loopPrefix,
                    Markers.EMPTY,
                    control,
                    JRightPadded.build(formattedBody));
            }

            private IteratorBinding extractIteratorBinding(Statement statement) {
                if (statement instanceof J.VariableDeclarations decl
                        && decl.getVariables().size() == 1) {
                    J.VariableDeclarations.NamedVariable variable = decl.getVariables().getFirst();
                    Expression iterable = extractIterableSelect(variable.getInitializer());
                    if (iterable != null) {
                        return new IteratorBinding(
                            variable.getSimpleName(), variable.getVariableType(), iterable);
                    }
                } else if (statement instanceof J.Assignment assignment
                        && assignment.getVariable() instanceof J.Identifier id) {
                    Expression iterable = extractIterableSelect(assignment.getAssignment());
                    if (iterable != null) {
                        return new IteratorBinding(id.getSimpleName(), id.getFieldType(), iterable);
                    }
                }
                return null;
            }

            private Expression extractIterableSelect(Expression initializer) {
                if (initializer instanceof J.MethodInvocation invocation
                        && ITERABLE_ITERATOR.matches(invocation)
                        && invocation.getSelect() != null
                        && TypeUtils.isAssignableTo("java.lang.Iterable", invocation.getSelect().getType())) {
                    return invocation.getSelect();
                }
                return null;
            }

            private boolean isHasNextCall(Expression expression, IteratorBinding binding) {
                return expression instanceof J.MethodInvocation invocation
                    && ITERATOR_HAS_NEXT.matches(invocation)
                    && isBindingIdentifier(invocation.getSelect(), binding);
            }

            private boolean isNextCall(Expression expression, IteratorBinding binding) {
                return expression instanceof J.MethodInvocation invocation
                    && ITERATOR_NEXT.matches(invocation)
                    && isBindingIdentifier(invocation.getSelect(), binding);
            }

            private boolean isEmptyUpdates(List<Statement> updates) {
                return updates.isEmpty()
                    || (updates.size() == 1 && updates.getFirst() instanceof J.Empty);
            }

            /**
             * Finds a single {@code it.next()} invocation in {@code loopBody} that is guaranteed to
             * execute unconditionally on every iteration and is used as a value (not a bare
             * expression statement), with no other references to {@code it} anywhere in the body.
             */
            private J.MethodInvocation findSingleUnconditionalNextCall(
                    Statement loopBody, IteratorBinding binding) {
                AtomicInteger totalRefs = new AtomicInteger();
                List<J.MethodInvocation> nextCalls = new ArrayList<>();
                AtomicBoolean unsafeLocation = new AtomicBoolean();

                JavaIsoVisitor<Integer> analyzer = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                        if (isBindingIdentifier(identifier, binding)) {
                            totalRefs.incrementAndGet();
                            Cursor parentCursor = getCursor().getParentTreeCursor();
                            Object parent = parentCursor.getValue();
                            if (parent instanceof J.MethodInvocation mi
                                    && mi.getSelect() == identifier
                                    && ITERATOR_NEXT.matches(mi)) {
                                // Must be used as a value, not as a standalone expression statement
                                Object grandParent = parentCursor.getParentTreeCursor().getValue();
                                if (grandParent instanceof J.Block
                                        || grandParent instanceof J.WhileLoop
                                        || grandParent instanceof J.ForLoop) {
                                    unsafeLocation.set(true);
                                } else {
                                    nextCalls.add(mi);
                                    if (isInsideConditionalOrNestedScope(parentCursor, loopBody)) {
                                        unsafeLocation.set(true);
                                    }
                                }
                            } else {
                                unsafeLocation.set(true);
                            }
                        }
                        return identifier;
                    }
                };

                analyzer.visit(loopBody, 0, new Cursor(getCursor(), loopBody));

                if (unsafeLocation.get() || totalRefs.get() != 1 || nextCalls.size() != 1) {
                    return null;
                }

                // If inside a block, ensure no preceding statement can exit/branch before it.next()
                if (loopBody instanceof J.Block bodyBlock) {
                    J.MethodInvocation target = nextCalls.getFirst();
                    for (Statement stmt : bodyBlock.getStatements()) {
                        if (containsTree(stmt, target)) {
                            break;
                        }
                        if (mayBranchOrExit(stmt)) {
                            return null;
                        }
                    }
                }
                return nextCalls.getFirst();
            }

            private boolean isInsideConditionalOrNestedScope(
                    Cursor nextCallCursor, Statement loopBody) {
                Cursor cur = nextCallCursor.getParentTreeCursor();
                while (cur != null && !cur.isRoot()) {
                    Object val = cur.getValue();
                    if (val == loopBody) {
                        return false;
                    }
                    if (val instanceof J.If
                            || val instanceof J.Ternary
                            || val instanceof J.Switch
                            || val instanceof J.SwitchExpression
                            || val instanceof J.Try
                            || val instanceof J.WhileLoop
                            || val instanceof J.ForLoop
                            || val instanceof J.DoWhileLoop
                            || val instanceof J.ForEachLoop
                            || val instanceof J.Lambda
                            || val instanceof J.NewClass
                            || val instanceof J.ClassDeclaration) {
                        return true;
                    }
                    if (val instanceof J.Binary binary
                            && (binary.getOperator() == J.Binary.Type.And
                                || binary.getOperator() == J.Binary.Type.Or)) {
                        return true;
                    }
                    cur = cur.getParentTreeCursor();
                }
                return false;
            }

            private boolean containsTree(Statement statement, J target) {
                AtomicBoolean found = new AtomicBoolean();
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(
                            J.MethodInvocation mi, AtomicBoolean acc) {
                        if (mi == target) {
                            acc.set(true);
                            return mi;
                        }
                        return super.visitMethodInvocation(mi, acc);
                    }
                }.visit(statement, found, new Cursor(getCursor(), statement));
                return found.get();
            }

            private boolean mayBranchOrExit(Statement statement) {
                AtomicBoolean branches = new AtomicBoolean();
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.If visitIf(J.If iff, AtomicBoolean acc) {
                        acc.set(true);
                        return iff;
                    }

                    @Override
                    public J.Return visitReturn(J.Return ret, AtomicBoolean acc) {
                        acc.set(true);
                        return ret;
                    }

                    @Override
                    public J.Break visitBreak(J.Break brk, AtomicBoolean acc) {
                        acc.set(true);
                        return brk;
                    }

                    @Override
                    public J.Continue visitContinue(J.Continue cont, AtomicBoolean acc) {
                        acc.set(true);
                        return cont;
                    }

                    @Override
                    public J.Throw visitThrow(J.Throw thr, AtomicBoolean acc) {
                        acc.set(true);
                        return thr;
                    }

                    @Override
                    public J.Switch visitSwitch(J.Switch sw, AtomicBoolean acc) {
                        acc.set(true);
                        return sw;
                    }
                }.visit(statement, branches, new Cursor(getCursor(), statement));
                return branches.get();
            }

            private Statement replaceNextCall(
                    Statement loopBody,
                    J.MethodInvocation target,
                    String varName,
                    JavaType elementType,
                    JavaType.Variable varFieldType) {
                return (Statement) new JavaVisitor<Integer>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, Integer p) {
                        if (method == target) {
                            return new J.Identifier(
                                Tree.randomId(),
                                method.getPrefix(),
                                Markers.EMPTY,
                                List.of(),
                                varName,
                                elementType,
                                varFieldType);
                        }
                        return super.visitMethodInvocation(method, p);
                    }
                }.visit(loopBody, 0, new Cursor(getCursor(), loopBody));
            }

            private String chooseLoopVariableName(
                    String iteratorName, JavaType elementType, Statement loopBody) {
                Set<String> usedNames = collectVisibleVariableNames(loopBody);
                List<String> candidates = new ArrayList<>();

                String singular = singularize(iteratorName);
                if (singular != null) {
                    candidates.add(singular);
                }
                if (TypeUtils.isAssignableTo("java.util.Map$Entry", elementType)) {
                    candidates.add("entry");
                }
                candidates.add("element");
                candidates.add("item");

                for (String candidate : candidates) {
                    if (!usedNames.contains(candidate)) {
                        return candidate;
                    }
                }
                String base = candidates.getFirst();
                int suffix = 1;
                while (usedNames.contains(base + suffix)) {
                    suffix++;
                }
                return base + suffix;
            }

            private String singularize(String name) {
                if (name == null || name.isEmpty()) {
                    return null;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("it") || lower.equals("iter") || lower.equals("iterator")) {
                    return null;
                }
                if (name.endsWith("ies") && name.length() > 3) {
                    return name.substring(0, name.length() - 3) + "y";
                }
                if (name.endsWith("s") && name.length() > 2 && !name.endsWith("ss")) {
                    return name.substring(0, name.length() - 1);
                }
                return null;
            }

            private Set<String> collectVisibleVariableNames(Statement loopBody) {
                Set<String> names = new HashSet<>();
                // Collect names declared in enclosing scopes up to the method/constructor/class
                Cursor cur = getCursor();
                while (cur != null && !cur.isRoot()) {
                    Object val = cur.getValue();
                    if (val instanceof J.Block blk) {
                        for (Statement stmt : blk.getStatements()) {
                            if (stmt instanceof J.VariableDeclarations vd) {
                                for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                    names.add(nv.getSimpleName());
                                }
                            }
                        }
                    } else if (val instanceof J.MethodDeclaration md) {
                        for (Statement param : md.getParameters()) {
                            if (param instanceof J.VariableDeclarations vd) {
                                for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                    names.add(nv.getSimpleName());
                                }
                            }
                        }
                        break;
                    } else if (val instanceof J.ForLoop fl) {
                        for (Statement init : fl.getControl().getInit()) {
                            if (init instanceof J.VariableDeclarations vd) {
                                for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                    names.add(nv.getSimpleName());
                                }
                            }
                        }
                    } else if (val instanceof J.ForEachLoop fel) {
                        if (fel.getControl().getVariable() instanceof J.VariableDeclarations vd) {
                            for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                names.add(nv.getSimpleName());
                            }
                        }
                    } else if (val instanceof J.Try.Catch c) {
                        for (J.VariableDeclarations.NamedVariable nv :
                                c.getParameter().getTree().getVariables()) {
                            names.add(nv.getSimpleName());
                        }
                    } else if (val instanceof J.Lambda lambda) {
                        for (J param : lambda.getParameters().getParameters()) {
                            if (param instanceof J.VariableDeclarations vd) {
                                for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                                    names.add(nv.getSimpleName());
                                }
                            } else if (param instanceof J.Identifier id) {
                                names.add(id.getSimpleName());
                            }
                        }
                    } else if (val instanceof J.ClassDeclaration) {
                        break;
                    }
                    cur = cur.getParentTreeCursor();
                }

                // Collect all identifiers inside loopBody so we never shadow anything used there
                new JavaIsoVisitor<Set<String>>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier id, Set<String> acc) {
                        acc.add(id.getSimpleName());
                        return id;
                    }
                }.visit(loopBody, names, new Cursor(getCursor(), loopBody));
                return names;
            }

            private boolean referencesVariable(List<Statement> statements, IteratorBinding binding) {
                AtomicBoolean found = new AtomicBoolean();
                JavaIsoVisitor<AtomicBoolean> finder = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean referenced) {
                        if (isBindingIdentifier(identifier, binding)) {
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

            private boolean isBindingIdentifier(Expression expression, IteratorBinding binding) {
                if (!(expression instanceof J.Identifier identifier)
                        || !binding.name().equals(identifier.getSimpleName())) {
                    return false;
                }
                return binding.variableType() == null
                    || binding.variableType().equals(identifier.getFieldType());
            }
        };
    }

    private record IteratorBinding(String name, JavaType.Variable variableType, Expression iterable) {}
}
