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
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * Folds safe {@link java.util.Optional#isPresent()} early-return branches followed by a fallback
 * {@code return} into a single functional {@code orElse}/{@code orElseGet} or
 * {@code map(...).orElse/orElseGet(...)} expression.
 *
 * <p>Covers three strictly verified shapes:
 * <ol>
 *   <li>Direct return: {@code if (opt.isPresent()) return opt.get(); return fallback;}</li>
 *   <li>Static {@code of} factory: {@code if (opt.isPresent()) return Foo.of(opt.get()); return fallback;}</li>
 *   <li>Guaranteed-non-null mapping (primitive return type, constructor call, or static {@code of}):
 *       {@code if (opt.isPresent()) return opt.get().path("k").asBoolean(false); return fallback;}</li>
 * </ol>
 *
 * <p>Skips rewrites whenever either branch can throw a checked exception, captures a reassigned
 * local variable, or (for mapped returns) can evaluate to {@code null} (which would change
 * semantics under {@code Optional.map}).
 */
public final class UseOptionalMapOrElseGet extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use Optional functional return expressions";
    }

    @Override
    public String getDescription() {
        return "Replace safe Optional.isPresent() return branches with Optional.map/orElse/orElseGet.";
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
                        || presentReturn.getExpression() == null
                        || fallbackReturn.getExpression() == null) {
                    return null;
                }

                Expression presentExpr = presentReturn.getExpression();
                Expression fallbackExpr = fallbackReturn.getExpression();
                Set<String> reassignedLocals = findReassignedLocalsInEnclosingMethod();

                if (throwsCheckedException(fallbackExpr) || capturesAny(fallbackExpr, reassignedLocals)) {
                    return null;
                }

                String optName = optional.getSimpleName();
                String fallbackSuffix = fallbackExpr instanceof J.Literal
                        ? ".orElse(" + fallbackExpr.printTrimmed(getCursor()) + ")"
                        : ".orElseGet(() -> " + fallbackExpr.printTrimmed(getCursor()) + ")";

                // 1. Direct return: if (opt.isPresent()) return opt.get(); return fallback;
                if (isOptionalGet(presentExpr, optName)) {
                    String code = "return " + optName + fallbackSuffix + ";";
                    return applyTemplate(presenceBranch, code);
                }

                if (throwsCheckedException(presentExpr) || capturesAny(presentExpr, reassignedLocals)) {
                    return null;
                }

                // 2. Static Foo.of(opt.get()) method-reference form
                if (presentExpr instanceof J.MethodInvocation factoryCall
                        && factoryCall.getMethodType() != null
                        && factoryCall.getMethodType().hasFlags(Flag.Static)
                        && "of".equals(factoryCall.getSimpleName())
                        && factoryCall.getSelect() != null
                        && factoryCall.getArguments().size() == 1
                        && isOptionalGet(factoryCall.getArguments().getFirst(), optName)) {
                    String factory = factoryCall.getSelect().printTrimmed(getCursor())
                            + "::" + factoryCall.getSimpleName();
                    String code = "return " + optName + ".map(" + factory + ")"
                            + ".orElseGet(() -> " + fallbackExpr.printTrimmed(getCursor()) + ");";
                    return applyTemplate(presenceBranch, code);
                }

                // 3. Guaranteed non-null mapped expression containing exactly one opt.get()
                if (isGuaranteedNonNull(presentExpr) && countOptionalGets(presentExpr, optName) == 1) {
                    String paramName = chooseLambdaParamName(presentExpr, optName);
                    Expression mapped = replaceOptionalGet(presentExpr, optName, paramName);
                    String code = "return " + optName + ".map(" + paramName + " -> "
                            + mapped.printTrimmed(getCursor()) + ")" + fallbackSuffix + ";";
                    return applyTemplate(presenceBranch, code);
                }

                return null;
            }

            private Statement applyTemplate(J.If presenceBranch, String code) {
                Cursor branchCursor = new Cursor(getCursor(), presenceBranch);
                return JavaTemplate.builder(code)
                        .contextSensitive()
                        .build()
                        .apply(branchCursor, presenceBranch.getCoordinates().replace());
            }

            private boolean isGuaranteedNonNull(Expression expression) {
                if (expression.getType() instanceof JavaType.Primitive primitive
                        && primitive != JavaType.Primitive.Void
                        && primitive != JavaType.Primitive.Null) {
                    return true;
                }
                if (expression instanceof J.NewClass) {
                    return true;
                }
                if (expression instanceof J.MethodInvocation mi
                        && mi.getMethodType() != null
                        && mi.getMethodType().hasFlags(Flag.Static)
                        && "of".equals(mi.getSimpleName())) {
                    return true;
                }
                return false;
            }

            private int countOptionalGets(Expression root, String optName) {
                AtomicInteger count = new AtomicInteger();
                new JavaIsoVisitor<AtomicInteger>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(
                            J.MethodInvocation method, AtomicInteger acc) {
                        if (isOptionalGet(method, optName)) {
                            acc.incrementAndGet();
                            return method;
                        }
                        return super.visitMethodInvocation(method, acc);
                    }
                }.visit(root, count);
                return count.get();
            }

            private Expression replaceOptionalGet(Expression root, String optName, String paramName) {
                return (Expression) new JavaVisitor<Integer>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, Integer p) {
                        if (isOptionalGet(method, optName)) {
                            return new J.Identifier(
                                    method.getId(),
                                    method.getPrefix(),
                                    Markers.EMPTY,
                                    List.of(),
                                    paramName,
                                    method.getType(),
                                    null);
                        }
                        return super.visitMethodInvocation(method, p);
                    }
                }.visitNonNull(root, 0);
            }

            private String chooseLambdaParamName(Expression presentExpr, String optName) {
                Set<String> used = new HashSet<>();
                used.add(optName);
                new JavaIsoVisitor<Set<String>>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier ident, Set<String> acc) {
                        acc.add(ident.getSimpleName());
                        return ident;
                    }
                }.visit(presentExpr, used);
                if (!used.contains("value")) return "value";
                String candidate = optName + "Value";
                if (!used.contains(candidate)) return candidate;
                int suffix = 1;
                while (used.contains("v" + suffix)) suffix++;
                return "v" + suffix;
            }

            private boolean throwsCheckedException(Expression expression) {
                AtomicBoolean found = new AtomicBoolean(false);
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(
                            J.MethodInvocation method, AtomicBoolean flag) {
                        if (hasCheckedThrows(method.getMethodType())) {
                            flag.set(true);
                        }
                        return super.visitMethodInvocation(method, flag);
                    }

                    @Override
                    public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean flag) {
                        if (hasCheckedThrows(newClass.getConstructorType())) {
                            flag.set(true);
                        }
                        return super.visitNewClass(newClass, flag);
                    }
                }.visit(expression, found);
                return found.get();
            }

            private boolean hasCheckedThrows(JavaType.Method methodType) {
                if (methodType == null || methodType.getThrownExceptions() == null) return false;
                for (JavaType thrown : methodType.getThrownExceptions()) {
                    if (!TypeUtils.isAssignableTo("java.lang.RuntimeException", thrown)
                            && !TypeUtils.isAssignableTo("java.lang.Error", thrown)) {
                        return true;
                    }
                }
                return false;
            }

            private Set<String> findReassignedLocalsInEnclosingMethod() {
                Set<String> reassigned = new HashSet<>();
                J.MethodDeclaration enclosing = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (enclosing == null) return reassigned;
                new JavaIsoVisitor<Set<String>>() {
                    @Override
                    public J.Assignment visitAssignment(J.Assignment assignment, Set<String> acc) {
                        if (assignment.getVariable() instanceof J.Identifier id) {
                            acc.add(id.getSimpleName());
                        }
                        return super.visitAssignment(assignment, acc);
                    }

                    @Override
                    public J.AssignmentOperation visitAssignmentOperation(
                            J.AssignmentOperation assignOp, Set<String> acc) {
                        if (assignOp.getVariable() instanceof J.Identifier id) {
                            acc.add(id.getSimpleName());
                        }
                        return super.visitAssignmentOperation(assignOp, acc);
                    }

                    @Override
                    public J.Unary visitUnary(J.Unary unary, Set<String> acc) {
                        if ((unary.getOperator() == J.Unary.Type.PreIncrement
                                || unary.getOperator() == J.Unary.Type.PostIncrement
                                || unary.getOperator() == J.Unary.Type.PreDecrement
                                || unary.getOperator() == J.Unary.Type.PostDecrement)
                                && unary.getExpression() instanceof J.Identifier id) {
                            acc.add(id.getSimpleName());
                        }
                        return super.visitUnary(unary, acc);
                    }
                }.visit(enclosing, reassigned);
                return reassigned;
            }

            private boolean capturesAny(Expression expression, Set<String> names) {
                if (names.isEmpty()) return false;
                AtomicBoolean found = new AtomicBoolean(false);
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier ident, AtomicBoolean flag) {
                        if (names.contains(ident.getSimpleName())) {
                            flag.set(true);
                        }
                        return ident;
                    }
                }.visit(expression, found);
                return found.get();
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
