package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Replaces an immediately null-checked {@link java.util.Optional#orElse(Object)} result with
 * {@code Optional.ifPresent(...)} when the body is safe to move into a consumer.
 */
public final class UseOptionalIfPresent extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use Optional.ifPresent after orElse(null)";
    }

    @Override
    public String getDescription() {
        return "Eliminate a temporary Optional value used only by an immediately following null-check block.";
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
                            && statement instanceof J.VariableDeclarations declaration
                            && statements.get(index + 1) instanceof J.If nullCheck) {
                        Statement replacement = replacementFor(
                                declaration, nullCheck,
                                statements.subList(index + 2, statements.size()));
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

            private Statement replacementFor(
                    J.VariableDeclarations declaration,
                    J.If nullCheck,
                    List<Statement> followingStatements) {
                if (declaration.getVariables().size() != 1 || nullCheck.getElsePart() != null) {
                    return null;
                }
                J.VariableDeclarations.NamedVariable variable = declaration.getVariables().getFirst();
                if (!(variable.getInitializer() instanceof J.MethodInvocation orElse)
                        || !"orElse".equals(orElse.getSimpleName())
                        || orElse.getMethodType() == null
                        || !TypeUtils.isOfClassType(
                                orElse.getMethodType().getDeclaringType(), "java.util.Optional")
                        || orElse.getSelect() == null
                        || orElse.getArguments().size() != 1
                        || !isNullLiteral(orElse.getArguments().getFirst())
                        || !checksNotNull(nullCheck, variable)
                        || !isConsumerSafe(nullCheck.getThenPart(), variable)
                        || referencesVariable(followingStatements, variable)) {
                    return null;
                }

                String lambdaParameter = uniqueLambdaParameter(variable.getSimpleName());
                Statement renamedBody = renameVariable(
                    nullCheck.getThenPart(), variable, lambdaParameter);
                renamedBody = renamedBody.withPrefix(Space.EMPTY);
                boolean expressionBody = renamedBody instanceof Expression;
                boolean compactBody = !expressionBody && !(renamedBody instanceof J.Block);
                String optional = orElse.getSelect().printTrimmed(getCursor())
                    .replaceAll("\\R\\h*", "\n    ");
                String code = compactBody
                    ? optional + ".ifPresent(" + lambdaParameter + " -> { #{any()}; });"
                    : optional + ".ifPresent(" + lambdaParameter + " -> #{any()});";
                Cursor nullCheckCursor = new Cursor(getCursor(), nullCheck);
                Statement replacement = JavaTemplate.builder(code)
                        .contextSensitive()
                        .build()
                        .apply(nullCheckCursor, nullCheck.getCoordinates().replace(),
                            renamedBody);
                if (compactBody) {
                    replacement = (Statement) new JavaIsoVisitor<Integer>() {
                        @Override
                        public J.Block visitBlock(J.Block block, Integer unused) {
                            J.Block visited = super.visitBlock(block, unused);
                            if (visited.getStatements().size() != 1) return visited;
                            List<Statement> compact = new ArrayList<>(visited.getStatements());
                            compact.set(0, compact.getFirst()
                                .withPrefix(Space.SINGLE_SPACE));
                            return visited.withStatements(compact).withEnd(Space.SINGLE_SPACE);
                        }
                    }.visit(replacement, 0, nullCheckCursor);
                }
                return replacement.withPrefix(declaration.getPrefix());
            }

            private String uniqueLambdaParameter(String variableName) {
                Set<String> names = new HashSet<>();
                J.MethodDeclaration enclosingMethod =
                    getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (enclosingMethod != null) {
                    new JavaIsoVisitor<Set<String>>() {
                        @Override
                        public J.Identifier visitIdentifier(J.Identifier identifier,
                                Set<String> identifiers) {
                            identifiers.add(identifier.getSimpleName());
                            return super.visitIdentifier(identifier, identifiers);
                        }
                    }.visit(enclosingMethod, names, new Cursor(getCursor(), enclosingMethod));
                }
                String base = variableName + "Value";
                String candidate = base;
                for (int suffix = 2; names.contains(candidate); suffix++) {
                    candidate = base + suffix;
                }
                return candidate;
            }

            private Statement renameVariable(Statement statement,
                    J.VariableDeclarations.NamedVariable variable,
                    String replacement) {
                return (Statement) new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Integer unused) {
                        J.Identifier visited = super.visitIdentifier(identifier, unused);
                        if (!isVariable(visited, variable)) return visited;
                        JavaType.Variable fieldType = visited.getFieldType();
                        return visited.withSimpleName(replacement)
                            .withFieldType(fieldType == null
                                ? null
                                : fieldType.withName(replacement));
                    }
                }.visit(statement, 0, new Cursor(getCursor(), statement));
            }

            private boolean checksNotNull(J.If nullCheck, J.VariableDeclarations.NamedVariable variable) {
                if (!(nullCheck.getIfCondition().getTree() instanceof J.Binary condition)
                        || condition.getOperator() != J.Binary.Type.NotEqual) {
                    return false;
                }
                return isVariable(condition.getLeft(), variable) && isNullLiteral(condition.getRight())
                        || isNullLiteral(condition.getLeft()) && isVariable(condition.getRight(), variable);
            }

            private boolean isConsumerSafe(
                    Statement body,
                    J.VariableDeclarations.NamedVariable variable) {
                AtomicBoolean safe = new AtomicBoolean(true);
                Set<JavaType.Variable> declaredInBody = new HashSet<>();
                Set<JavaType.Variable> capturedLocals = new HashSet<>();
                new JavaIsoVisitor<Set<JavaType.Variable>>() {
                    @Override
                    public J.VariableDeclarations.NamedVariable visitVariable(
                            J.VariableDeclarations.NamedVariable declared,
                            Set<JavaType.Variable> variables) {
                        if (declared.getVariableType() != null) {
                            variables.add(declared.getVariableType());
                        }
                        return super.visitVariable(declared, variables);
                    }
                }.visit(body, declaredInBody, new Cursor(getCursor(), body));
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean allowed) {
                        JavaType.Variable fieldType = identifier.getFieldType();
                        if (fieldType != null
                                && fieldType.getOwner() instanceof JavaType.Method
                                && !isVariable(identifier, variable)
                                && !declaredInBody.contains(fieldType)) {
                            capturedLocals.add(fieldType);
                        }
                        return identifier;
                    }

                    @Override
                    public J.Assignment visitAssignment(J.Assignment assignment, AtomicBoolean allowed) {
                        if (isVariable(assignment.getVariable(), variable)) allowed.set(false);
                        return super.visitAssignment(assignment, allowed);
                    }

                    @Override
                    public J.AssignmentOperation visitAssignmentOperation(
                            J.AssignmentOperation assignment, AtomicBoolean allowed) {
                        if (isVariable(assignment.getVariable(), variable)) allowed.set(false);
                        return super.visitAssignmentOperation(assignment, allowed);
                    }

                    @Override
                    public J.Return visitReturn(J.Return returnStatement, AtomicBoolean allowed) {
                        allowed.set(false);
                        return returnStatement;
                    }

                    @Override
                    public J.Break visitBreak(J.Break breakStatement, AtomicBoolean allowed) {
                        allowed.set(false);
                        return breakStatement;
                    }

                    @Override
                    public J.Continue visitContinue(J.Continue continueStatement, AtomicBoolean allowed) {
                        allowed.set(false);
                        return continueStatement;
                    }

                    @Override
                    public J.Yield visitYield(J.Yield yield, AtomicBoolean allowed) {
                        allowed.set(false);
                        return yield;
                    }

                    @Override
                    public J.Lambda visitLambda(J.Lambda lambda, AtomicBoolean allowed) {
                        return lambda;
                    }

                    @Override
                    public J.ClassDeclaration visitClassDeclaration(
                            J.ClassDeclaration classDeclaration, AtomicBoolean allowed) {
                        return classDeclaration;
                    }
                }.visit(body, safe, new Cursor(getCursor(), body));
                if (!safe.get() || capturedLocals.isEmpty()) return safe.get();
                J.MethodDeclaration enclosingMethod =
                    getCursor().firstEnclosing(J.MethodDeclaration.class);
                return enclosingMethod != null && capturedLocals.stream()
                    .noneMatch(captured -> isWritten(enclosingMethod, captured));
            }

            private boolean isWritten(J.MethodDeclaration method, JavaType.Variable variable) {
                AtomicBoolean written = new AtomicBoolean();
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.Assignment visitAssignment(
                            J.Assignment assignment, AtomicBoolean found) {
                        if (isVariable(assignment.getVariable(), variable)) found.set(true);
                        return super.visitAssignment(assignment, found);
                    }

                    @Override
                    public J.AssignmentOperation visitAssignmentOperation(
                            J.AssignmentOperation assignment, AtomicBoolean found) {
                        if (isVariable(assignment.getVariable(), variable)) found.set(true);
                        return super.visitAssignmentOperation(assignment, found);
                    }

                    @Override
                    public J.Unary visitUnary(J.Unary unary, AtomicBoolean found) {
                        if (unary.getOperator().isModifying()
                                && isVariable(unary.getExpression(), variable)) {
                            found.set(true);
                        }
                        return super.visitUnary(unary, found);
                    }
                }.visit(method, written, new Cursor(getCursor(), method));
                return written.get();
            }

            private boolean referencesVariable(
                    List<Statement> statements,
                    J.VariableDeclarations.NamedVariable variable) {
                AtomicBoolean found = new AtomicBoolean();
                JavaIsoVisitor<AtomicBoolean> finder = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean referenced) {
                        if (isVariable(identifier, variable)) referenced.set(true);
                        return identifier;
                    }
                };
                for (Statement statement : statements) {
                    finder.visit(statement, found, new Cursor(getCursor(), statement));
                    if (found.get()) return true;
                }
                return false;
            }

            private boolean isVariable(Expression expression, J.VariableDeclarations.NamedVariable variable) {
                if (!(expression instanceof J.Identifier identifier)
                        || !variable.getSimpleName().equals(identifier.getSimpleName())) {
                    return false;
                }
                return variable.getVariableType() == null
                        || variable.getVariableType().equals(identifier.getFieldType());
            }

            private boolean isVariable(Expression expression, JavaType.Variable variable) {
                return expression instanceof J.Identifier identifier
                        && variable.equals(identifier.getFieldType());
            }

            private boolean isNullLiteral(Expression expression) {
                return expression instanceof J.Literal literal && literal.getValue() == null;
            }

        };
    }
}
