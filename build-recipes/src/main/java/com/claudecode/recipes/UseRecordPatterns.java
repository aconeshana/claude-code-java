package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.java.tree.TypedTree;
import org.openrewrite.marker.Markers;

/** Replaces eligible type patterns with Java record patterns. */
public final class UseRecordPatterns extends Recipe {

    private static final int MAX_RECORD_COMPONENTS = 3;
    private static final int MAX_UNUSED_COMPONENTS = 1;
    private static final Set<String> DISALLOWED_COMPONENT_NAMES = Set.of(
        "_", "var", "yield", "record", "sealed", "permits", "this", "super", "true", "false", "null");

    @Override
    public String getDisplayName() {
        return "Use record patterns";
    }

    @Override
    public String getDescription() {
        return "Deconstruct small records directly in `if` `instanceof` branches (including `&&` chains) "
            + "when the record variable is used only through component accessors, at most one component "
            + "is unnamed (`_`), bound component names do not collide with local variables in scope, and "
            + "any shadowed enclosing fields can be safely qualified.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitIf(J.If iff, ExecutionContext ctx) {
                J.If visited = (J.If) super.visitIf(iff, ctx);
                List<Expression> conjuncts = flattenAndChain(visited.getIfCondition().getTree());
                if (conjuncts.isEmpty()) return visited;

                for (int i = 0; i < conjuncts.size(); i++) {
                    if (!(conjuncts.get(i) instanceof J.InstanceOf instanceOf)) continue;
                    J.If rewritten = tryRewriteInstanceOf(
                        visited, conjuncts, instanceOf, conjuncts.subList(i + 1, conjuncts.size()));
                    if (rewritten != null) {
                        return rewritten;
                    }
                }
                return visited;
            }

            private J.If tryRewriteInstanceOf(
                    J.If visited,
                    List<Expression> allConjuncts,
                    J.InstanceOf instanceOf,
                    List<Expression> trailingConjuncts) {
                if (!(instanceOf.getClazz() instanceof Expression clazzExpr)
                        || !(instanceOf.getClazz() instanceof TypedTree typeTree)
                        || !(instanceOf.getPattern() instanceof J.Identifier patternVariable)
                        || !(TypeUtils.asFullyQualified(typeTree.getType()) instanceof JavaType.Class recordType)
                        || recordType.getKind() != JavaType.FullyQualified.Kind.Record) {
                    return null;
                }

                // Preserve symmetry when two conjuncts match the same record type (e.g. `a instanceof R x && b instanceof R y`).
                if (hasDuplicateRecordTypeInChain(allConjuncts, instanceOf, recordType)) {
                    return null;
                }

                // Skip raw references to generic records: `x instanceof Box(var v)` requires type args.
                if (!recordType.getTypeParameters().isEmpty()
                        && !(typeTree.getType() instanceof JavaType.Parameterized)) {
                    return null;
                }

                List<JavaType.Variable> components = recordComponents(recordType);
                if (components.isEmpty() || components.size() > MAX_RECORD_COMPONENTS) {
                    return null;
                }

                AccessorUsage usage = analyzeUsage(
                    trailingConjuncts, visited.getThenPart(), patternVariable, components);
                if (!usage.safe() || usage.counts().isEmpty()) {
                    return null;
                }

                int unusedCount = components.size() - usage.counts().size();
                if (unusedCount > MAX_UNUSED_COMPONENTS) {
                    return null;
                }

                BindingPlan plan = resolveBindingPlan(
                    components,
                    usage.counts(),
                    instanceOf,
                    patternVariable,
                    allConjuncts,
                    trailingConjuncts,
                    visited.getThenPart());
                if (plan == null) {
                    return null;
                }

                J.InstanceOf rewrittenInstanceOf = buildRecordPattern(
                    instanceOf, clazzExpr, typeTree.getType(), components, plan.bindings());

                Expression rewrittenCondition = rewriteConditionTree(
                    visited.getIfCondition().getTree(),
                    instanceOf,
                    rewrittenInstanceOf,
                    trailingConjuncts,
                    patternVariable,
                    plan.bindings(),
                    plan.fieldQualifications());
                Statement rewrittenThen = (Statement) replaceAccessorsAndQualifyFields(
                    visited.getThenPart(), patternVariable, plan.bindings(), plan.fieldQualifications());

                return visited
                    .withIfCondition(visited.getIfCondition().withTree(rewrittenCondition))
                    .withThenPart(rewrittenThen);
            }

            private J.InstanceOf buildRecordPattern(
                    J.InstanceOf instanceOf,
                    Expression clazzExpr,
                    JavaType recordType,
                    List<JavaType.Variable> components,
                    Map<String, String> bindings) {
                List<JRightPadded<J>> nested = new ArrayList<>(components.size());
                for (int i = 0; i < components.size(); i++) {
                    JavaType.Variable component = components.get(i);
                    Space prefix = i == 0 ? Space.EMPTY : Space.SINGLE_SPACE;
                    String boundName = bindings.get(component.getName());
                    J element;
                    if (boundName != null) {
                        JavaType.Variable varType = new JavaType.Variable(
                            null, 0, boundName, null, component.getType(), List.of());
                        J.Identifier varKeyword = new J.Identifier(
                            Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(),
                            "var", component.getType(), null);
                        J.Identifier varName = new J.Identifier(
                            Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, List.of(),
                            boundName, component.getType(), varType);
                        J.VariableDeclarations.NamedVariable namedVar =
                            new J.VariableDeclarations.NamedVariable(
                                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                varName, List.of(), null, varType);
                        element = new J.VariableDeclarations(
                            Tree.randomId(), prefix, Markers.EMPTY, List.of(), List.of(),
                            varKeyword, null, List.of(),
                            List.of(JRightPadded.build(namedVar)));
                    } else {
                        element = new J.Identifier(
                            Tree.randomId(), prefix, Markers.EMPTY, List.of(),
                            "_", component.getType(), null);
                    }
                    nested.add(JRightPadded.build(element));
                }

                J.DeconstructionPattern deconstruction = new J.DeconstructionPattern(
                    Tree.randomId(),
                    clazzExpr.getPrefix(),
                    Markers.EMPTY,
                    clazzExpr.withPrefix(Space.EMPTY),
                    JContainer.build(Space.EMPTY, nested, Markers.EMPTY),
                    recordType);
                return instanceOf
                    .withClazz(deconstruction)
                    .withPattern(null);
            }

            private boolean hasDuplicateRecordTypeInChain(
                    List<Expression> conjuncts,
                    J.InstanceOf current,
                    JavaType.Class recordType) {
                String targetFqn = recordType.getFullyQualifiedName();
                for (Expression conjunct : conjuncts) {
                    if (conjunct == current || !(conjunct instanceof J.InstanceOf other)) continue;
                    if (other.getClazz() instanceof TypedTree otherTypeTree
                            && TypeUtils.asFullyQualified(otherTypeTree.getType()) instanceof JavaType.Class otherType
                            && targetFqn.equals(otherType.getFullyQualifiedName())) {
                        return true;
                    }
                }
                return false;
            }

            private List<Expression> flattenAndChain(Expression expression) {
                List<Expression> result = new ArrayList<>();
                collectAndConjuncts(expression, result);
                return result;
            }

            private void collectAndConjuncts(Expression expression, List<Expression> out) {
                if (expression instanceof J.Binary binary && binary.getOperator() == J.Binary.Type.And) {
                    collectAndConjuncts(binary.getLeft(), out);
                    collectAndConjuncts(binary.getRight(), out);
                } else {
                    out.add(expression);
                }
            }

            private List<JavaType.Variable> recordComponents(JavaType.Class recordType) {
                Map<String, JavaType.Variable> members = recordType.getMembers().stream()
                    .filter(member -> !member.hasFlags(Flag.Static))
                    .collect(Collectors.toMap(
                        JavaType.Variable::getName, Function.identity(), (left, right) -> left));
                return recordType.getMethods().stream()
                    .filter(JavaType.Method::isConstructor)
                    .map(JavaType.Method::getParameterNames)
                    .filter(names -> names.size() == members.size() && members.keySet().containsAll(names))
                    .findFirst()
                    .map(names -> names.stream().map(members::get).toList())
                    .orElseGet(List::of);
            }

            private AccessorUsage analyzeUsage(
                    List<Expression> trailingConjuncts,
                    Statement thenPart,
                    J.Identifier patternVariable,
                    List<JavaType.Variable> components) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                List<String> componentNames = components.stream().map(JavaType.Variable::getName).toList();
                boolean[] safe = {true};
                JavaIsoVisitor<Integer> analyzer = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Integer unused) {
                        J.Identifier visited = super.visitIdentifier(identifier, unused);
                        Object parent = getCursor().getParentTreeCursor().getValue();
                        if (isNonValueMemberName(parent, identifier)) {
                            return visited;
                        }
                        if (!sameVariable(patternVariable, visited)) return visited;
                        if (!(parent instanceof J.MethodInvocation invocation)
                                || invocation.getSelect() != identifier
                                || !componentNames.contains(invocation.getSimpleName())
                                || !hasNoArguments(invocation)) {
                            safe[0] = false;
                            return visited;
                        }
                        counts.merge(invocation.getSimpleName(), 1, Integer::sum);
                        return visited;
                    }
                };
                for (Expression conjunct : trailingConjuncts) {
                    analyzer.visit(conjunct, 0);
                }
                analyzer.visit(thenPart, 0);
                return new AccessorUsage(safe[0], counts);
            }

            private BindingPlan resolveBindingPlan(
                    List<JavaType.Variable> components,
                    Map<String, Integer> usedComponents,
                    J.InstanceOf targetInstanceOf,
                    J.Identifier patternVariable,
                    List<Expression> allConjuncts,
                    List<Expression> trailingConjuncts,
                    Statement thenPart) {
                Map<String, String> bindings = new LinkedHashMap<>();
                for (JavaType.Variable component : components) {
                    String name = component.getName();
                    if (!usedComponents.containsKey(name)) continue;
                    if (DISALLOWED_COMPONENT_NAMES.contains(name)) {
                        return null;
                    }
                    if (hasLocalOrParameterCollision(
                            name, getCursor(), targetInstanceOf, allConjuncts, trailingConjuncts, thenPart)) {
                        return null;
                    }
                    bindings.put(name, name);
                }

                Map<J.Identifier, FieldQualification> qualifications = planFieldQualifications(
                    bindings.keySet(), getCursor(), patternVariable, trailingConjuncts, thenPart);
                if (qualifications == null) {
                    return null;
                }
                return new BindingPlan(bindings, qualifications);
            }

            private boolean hasLocalOrParameterCollision(
                    String candidateName,
                    Cursor ifCursor,
                    J.InstanceOf targetInstanceOf,
                    List<Expression> allConjuncts,
                    List<Expression> trailingConjuncts,
                    Statement thenPart) {
                for (Expression conjunct : allConjuncts) {
                    if (conjunct == targetInstanceOf) continue;
                    if (declaresLocalOrPatternVariable(conjunct, candidateName, targetInstanceOf)) {
                        return true;
                    }
                }
                for (Expression conjunct : trailingConjuncts) {
                    if (declaresLocalOrPatternVariable(conjunct, candidateName, targetInstanceOf)) {
                        return true;
                    }
                }
                if (declaresLocalOrPatternVariable(thenPart, candidateName, targetInstanceOf)) {
                    return true;
                }

                Object child = ifCursor.getValue();
                Cursor cursor = ifCursor.getParentTreeCursor();
                while (cursor != null && !(cursor.getValue() instanceof JavaSourceFile)) {
                    Object node = cursor.getValue();
                    if (node instanceof J.MethodDeclaration method) {
                        for (Statement param : method.getParameters()) {
                            if (declaresVariableName(param, candidateName)) return true;
                        }
                        if (method.isConstructor()) {
                            J.ClassDeclaration enclosingRecord = cursor.firstEnclosing(J.ClassDeclaration.class);
                            if (enclosingRecord != null
                                    && enclosingRecord.getKind() == J.ClassDeclaration.Kind.Type.Record
                                    && enclosingRecord.getPrimaryConstructor() != null) {
                                for (Statement comp : enclosingRecord.getPrimaryConstructor()) {
                                    if (declaresVariableName(comp, candidateName)) return true;
                                }
                            }
                        }
                    } else if (node instanceof J.Lambda lambda) {
                        for (J param : lambda.getParameters().getParameters()) {
                            if (param instanceof J.Identifier ident && candidateName.equals(ident.getSimpleName())) {
                                return true;
                            }
                            if (declaresVariableName(param, candidateName)) return true;
                        }
                    } else if (node instanceof J.Try tryStmt) {
                        if (tryStmt.getResources() != null) {
                            for (J.Try.Resource res : tryStmt.getResources()) {
                                if (declaresVariableName(res.getVariableDeclarations(), candidateName)) return true;
                            }
                        }
                    } else if (node instanceof J.Try.Catch catchClause) {
                        if (declaresVariableName(catchClause.getParameter().getTree(), candidateName)) return true;
                    } else if (node instanceof J.ForLoop forLoop) {
                        for (Statement init : forLoop.getControl().getInit()) {
                            if (declaresVariableName(init, candidateName)) return true;
                        }
                    } else if (node instanceof J.ForEachLoop forEach) {
                        if (declaresVariableName(forEach.getControl().getVariable(), candidateName)) return true;
                    } else if (node instanceof J.WhileLoop whileLoop) {
                        if (declaresLocalOrPatternVariable(whileLoop.getCondition(), candidateName, targetInstanceOf)) {
                            return true;
                        }
                    } else if (node instanceof J.If outerIf) {
                        if (child == outerIf.getThenPart()) {
                            if (declaresLocalOrPatternVariable(
                                    outerIf.getIfCondition(), candidateName, targetInstanceOf)) {
                                return true;
                            }
                        } else if (outerIf.getElsePart() != null
                                && (child == outerIf.getElsePart() || child == outerIf.getElsePart().getBody())
                                && containsNegationOrDisjunction(outerIf.getIfCondition().getTree())
                                && declaresLocalOrPatternVariable(
                                    outerIf.getIfCondition(), candidateName, targetInstanceOf)) {
                            return true;
                        }
                    } else if (node instanceof J.Case caseNode) {
                        for (J label : caseNode.getCaseLabels()) {
                            if (declaresLocalOrPatternVariable(label, candidateName, targetInstanceOf)) return true;
                        }
                        if (caseNode.getGuard() != null
                                && declaresLocalOrPatternVariable(caseNode.getGuard(), candidateName, targetInstanceOf)) {
                            return true;
                        }
                        if (conflictsInPrecedingStatements(
                                caseNode.getStatements(), child, candidateName, targetInstanceOf)) {
                            return true;
                        }
                    } else if (node instanceof J.Block block) {
                        Object blockOwner = cursor.getParentTreeCursor().getValue();
                        if (!(blockOwner instanceof J.ClassDeclaration) && !(blockOwner instanceof J.NewClass)) {
                            if (conflictsInPrecedingStatements(
                                    block.getStatements(), child, candidateName, targetInstanceOf)) {
                                return true;
                            }
                        }
                    }
                    child = node;
                    cursor = cursor.getParentTreeCursor();
                }
                return false;
            }

            private boolean conflictsInPrecedingStatements(
                    List<Statement> statements,
                    Object stopAtStatement,
                    String candidateName,
                    J.InstanceOf targetInstanceOf) {
                for (Statement stmt : statements) {
                    if (stmt == stopAtStatement) break;
                    if (stmt instanceof J.VariableDeclarations vd && declaresVariableName(vd, candidateName)) {
                        return true;
                    }
                    // JLS 6.3.2.2: A plain `if (cond) thenStmt` without `else` and without negation, disjunction,
                    // or conditional expressions cannot introduce a flow-scoped pattern variable after the `if`.
                    if (stmt instanceof J.If prevIf
                            && prevIf.getElsePart() == null
                            && !containsNegationOrDisjunction(prevIf.getIfCondition().getTree())) {
                        continue;
                    }
                    if (declaresPatternVariable(stmt, candidateName, targetInstanceOf)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean containsNegationOrDisjunction(Expression expression) {
                boolean[] found = {false};
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.Unary visitUnary(J.Unary unary, Integer unused) {
                        if (unary.getOperator() == J.Unary.Type.Not) found[0] = true;
                        return super.visitUnary(unary, unused);
                    }

                    @Override
                    public J.Binary visitBinary(J.Binary binary, Integer unused) {
                        if (binary.getOperator() == J.Binary.Type.Or) found[0] = true;
                        return super.visitBinary(binary, unused);
                    }

                    @Override
                    public J.Ternary visitTernary(J.Ternary ternary, Integer unused) {
                        found[0] = true;
                        return super.visitTernary(ternary, unused);
                    }
                }.visit(expression, 0);
                return found[0];
            }

            private boolean declaresVariableName(J subtree, String candidateName) {
                if (subtree instanceof J.VariableDeclarations vd) {
                    for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                        if (candidateName.equals(var.getSimpleName())) return true;
                    }
                }
                return false;
            }

            private boolean declaresLocalOrPatternVariable(
                    J subtree, String candidateName, J.InstanceOf ignoredInstanceOf) {
                boolean[] found = {false};
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.VariableDeclarations.NamedVariable visitVariable(
                            J.VariableDeclarations.NamedVariable variable, Integer unused) {
                        if (candidateName.equals(variable.getSimpleName())) found[0] = true;
                        return super.visitVariable(variable, unused);
                    }

                    @Override
                    public J.InstanceOf visitInstanceOf(J.InstanceOf instanceOf, Integer unused) {
                        if (instanceOf != ignoredInstanceOf
                                && instanceOf.getPattern() instanceof J.Identifier patternId
                                && candidateName.equals(patternId.getSimpleName())) {
                            found[0] = true;
                        }
                        return super.visitInstanceOf(instanceOf, unused);
                    }

                    @Override
                    public J.Lambda visitLambda(J.Lambda lambda, Integer unused) {
                        for (J param : lambda.getParameters().getParameters()) {
                            if (param instanceof J.Identifier ident && candidateName.equals(ident.getSimpleName())) {
                                found[0] = true;
                            }
                        }
                        return super.visitLambda(lambda, unused);
                    }
                }.visit(subtree, 0);
                return found[0];
            }

            private boolean declaresPatternVariable(
                    J subtree, String candidateName, J.InstanceOf ignoredInstanceOf) {
                boolean[] found = {false};
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.InstanceOf visitInstanceOf(J.InstanceOf instanceOf, Integer unused) {
                        if (instanceOf != ignoredInstanceOf
                                && instanceOf.getPattern() instanceof J.Identifier patternId
                                && candidateName.equals(patternId.getSimpleName())) {
                            found[0] = true;
                        }
                        return super.visitInstanceOf(instanceOf, unused);
                    }

                    @Override
                    public J.DeconstructionPattern visitDeconstructionPattern(
                            J.DeconstructionPattern pattern, Integer unused) {
                        if (declaresLocalOrPatternVariable(pattern, candidateName, ignoredInstanceOf)) {
                            found[0] = true;
                        }
                        return super.visitDeconstructionPattern(pattern, unused);
                    }
                }.visit(subtree, 0);
                return found[0];
            }

            private Map<J.Identifier, FieldQualification> planFieldQualifications(
                    Set<String> candidateNames,
                    Cursor ifCursor,
                    J.Identifier patternVariable,
                    List<Expression> trailingConjuncts,
                    Statement thenPart) {
                Map<J.Identifier, FieldQualification> plan = new IdentityHashMap<>();
                boolean[] unsafe = {false};

                JavaIsoVisitor<Integer> planner = new JavaIsoVisitor<>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, Integer unused) {
                        if (unsafe[0]) return identifier;
                        if (!candidateNames.contains(identifier.getSimpleName())) {
                            return super.visitIdentifier(identifier, unused);
                        }
                        Object parent = getCursor().getParentTreeCursor().getValue();
                        if (isNonValueMemberName(parent, identifier) || sameVariable(patternVariable, identifier)) {
                            return identifier;
                        }

                        FieldQualification qualification = resolveQualificationForIdentifier(
                            identifier, getCursor(), candidateNames);
                        if (qualification == null) {
                            unsafe[0] = true;
                            return identifier;
                        }
                        plan.put(identifier, qualification);
                        return identifier;
                    }
                };

                for (Expression conjunct : trailingConjuncts) {
                    planner.visit(conjunct, 0, ifCursor);
                    if (unsafe[0]) return null;
                }
                planner.visit(thenPart, 0, ifCursor);
                return unsafe[0] ? null : plan;
            }

            private boolean isNonValueMemberName(Object parent, J.Identifier identifier) {
                if (parent instanceof J.MethodInvocation invocation && invocation.getName() == identifier) {
                    return true;
                }
                if (parent instanceof J.FieldAccess fieldAccess && fieldAccess.getName() == identifier) {
                    return true;
                }
                if (parent instanceof J.MemberReference memberRef && memberRef.getReference() == identifier) {
                    return true;
                }
                return parent instanceof J.Label label && label.getLabel() == identifier;
            }

            private FieldQualification resolveQualificationForIdentifier(
                    J.Identifier identifier,
                    Cursor identifierCursor,
                    Set<String> candidateNames) {
                JavaType.Variable fieldType = identifier.getFieldType();
                if (fieldType == null
                        || !(TypeUtils.asFullyQualified(fieldType.getOwner()) instanceof JavaType.FullyQualified fieldOwner)) {
                    return null;
                }

                boolean inStaticContext = false;
                boolean inConstructorPrologueOrThisSuperCall = false;
                J.ClassDeclaration enclosingClass = null;
                Object child = identifier;
                Cursor cursor = identifierCursor.getParentTreeCursor();
                while (cursor != null && !(cursor.getValue() instanceof JavaSourceFile)) {
                    Object node = cursor.getValue();
                    if (node instanceof J.NewClass newClass && newClass.getBody() != null && child == newClass.getBody()) {
                        return null;
                    }
                    if (node instanceof J.ClassDeclaration classDecl) {
                        enclosingClass = classDecl;
                        break;
                    }
                    if (node instanceof J.MethodDeclaration method) {
                        if (method.hasModifier(J.Modifier.Type.Static)) {
                            inStaticContext = true;
                        }
                        if (method.isConstructor() && method.getBody() != null
                                && isBeforeOrInsideConstructorDelegation(method.getBody(), child)) {
                            inConstructorPrologueOrThisSuperCall = true;
                        }
                    } else if (node instanceof J.Block block && block.isStatic()) {
                        inStaticContext = true;
                    } else if (node instanceof J.VariableDeclarations vd
                            && vd.hasModifier(J.Modifier.Type.Static)
                            && cursor.getParentTreeCursor().getValue() instanceof J.Block
                            && cursor.getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                        inStaticContext = true;
                    } else if (node instanceof J.MethodInvocation call
                            && call.getSelect() == null
                            && ("this".equals(call.getSimpleName()) || "super".equals(call.getSimpleName()))) {
                        inConstructorPrologueOrThisSuperCall = true;
                    }
                    child = node;
                    cursor = cursor.getParentTreeCursor();
                }

                if (enclosingClass == null || enclosingClass.getType() == null) {
                    return null;
                }

                if (fieldType.hasFlags(Flag.Static)) {
                    String classSimpleName = enclosingClass.getSimpleName();
                    if (!fieldOwner.getFullyQualifiedName().equals(enclosingClass.getType().getFullyQualifiedName())
                            || candidateNames.contains(classSimpleName)) {
                        return null;
                    }
                    return new FieldQualification(classSimpleName, enclosingClass.getType());
                }

                if (inStaticContext
                        || inConstructorPrologueOrThisSuperCall
                        || !TypeUtils.isAssignableTo(fieldOwner, enclosingClass.getType())) {
                    return null;
                }
                return new FieldQualification("this", enclosingClass.getType());
            }

            private boolean isBeforeOrInsideConstructorDelegation(J.Block constructorBody, Object subtreeInBody) {
                int delegationIndex = -1;
                int currentIndex = -1;
                List<Statement> stmts = constructorBody.getStatements();
                for (int i = 0; i < stmts.size(); i++) {
                    Statement s = stmts.get(i);
                    if (s == subtreeInBody) currentIndex = i;
                    if (s instanceof J.MethodInvocation call
                            && call.getSelect() == null
                            && ("this".equals(call.getSimpleName()) || "super".equals(call.getSimpleName()))) {
                        delegationIndex = i;
                    }
                }
                return delegationIndex >= 0 && (currentIndex < 0 || currentIndex <= delegationIndex);
            }

            private Expression rewriteConditionTree(
                    Expression condition,
                    J.InstanceOf originalInstanceOf,
                    Expression rewrittenInstanceOf,
                    List<Expression> trailingConjuncts,
                    J.Identifier patternVariable,
                    Map<String, String> bindings,
                    Map<J.Identifier, FieldQualification> fieldQualifications) {
                return (Expression) new JavaVisitor<Integer>() {
                    @Override
                    public J visitInstanceOf(J.InstanceOf instanceOf, Integer unused) {
                        if (instanceOf == originalInstanceOf) {
                            return rewrittenInstanceOf;
                        }
                        return super.visitInstanceOf(instanceOf, unused);
                    }

                    @Override
                    public J visitExpression(Expression expression, Integer unused) {
                        if (trailingConjuncts.contains(expression)) {
                            return replaceAccessorsAndQualifyFields(
                                expression, patternVariable, bindings, fieldQualifications);
                        }
                        return super.visitExpression(expression, unused);
                    }
                }.visitNonNull(condition, 0);
            }

            private J replaceAccessorsAndQualifyFields(
                    J subtree,
                    J.Identifier patternVariable,
                    Map<String, String> bindings,
                    Map<J.Identifier, FieldQualification> fieldQualifications) {
                return new JavaVisitor<Integer>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, Integer unused) {
                        J.MethodInvocation invocation = (J.MethodInvocation) super.visitMethodInvocation(method, unused);
                        String binding = accessorBinding(invocation, patternVariable, bindings);
                        if (binding == null) return invocation;
                        return new J.Identifier(
                            Tree.randomId(), invocation.getPrefix(), invocation.getMarkers(), List.of(),
                            binding, invocation.getType(), null);
                    }

                    @Override
                    public J visitIdentifier(J.Identifier ident, Integer unused) {
                        FieldQualification qualification = fieldQualifications.get(ident);
                        if (qualification == null) {
                            return super.visitIdentifier(ident, unused);
                        }
                        J.Identifier receiver = new J.Identifier(
                            Tree.randomId(),
                            Space.EMPTY,
                            Markers.EMPTY,
                            List.of(),
                            qualification.qualifier(),
                            qualification.qualifierType(),
                            null);
                        J.Identifier fieldName = ident
                            .withId(Tree.randomId())
                            .withPrefix(Space.EMPTY);
                        return new J.FieldAccess(
                            Tree.randomId(),
                            ident.getPrefix(),
                            ident.getMarkers(),
                            receiver,
                            JLeftPadded.build(fieldName),
                            ident.getType());
                    }
                }.visitNonNull(subtree, 0);
            }

            private String accessorBinding(
                    J.MethodInvocation invocation,
                    J.Identifier patternVariable,
                    Map<String, String> bindings) {
                if (!(invocation.getSelect() instanceof J.Identifier receiver)
                        || !sameVariable(patternVariable, receiver)
                        || !hasNoArguments(invocation)) {
                    return null;
                }
                return bindings.get(invocation.getSimpleName());
            }

            private boolean sameVariable(J.Identifier declaration, J.Identifier candidate) {
                if (!declaration.getSimpleName().equals(candidate.getSimpleName())) return false;
                JavaType.Variable declaredType = declaration.getFieldType();
                JavaType.Variable candidateType = candidate.getFieldType();
                if (declaredType != null && candidateType != null) {
                    return declaredType.equals(candidateType);
                }
                return true;
            }

            private boolean hasNoArguments(J.MethodInvocation invocation) {
                return invocation.getArguments().size() == 1
                    && invocation.getArguments().getFirst() instanceof J.Empty;
            }
        };
    }

    private record AccessorUsage(boolean safe, Map<String, Integer> counts) {
    }

    private record FieldQualification(String qualifier, JavaType qualifierType) {
    }

    private record BindingPlan(
        Map<String, String> bindings,
        Map<J.Identifier, FieldQualification> fieldQualifications
    ) {
    }
}
