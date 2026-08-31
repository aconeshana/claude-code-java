package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.VariableNameUtils;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.java.tree.TypedTree;

/** Replaces eligible type patterns with Java record patterns. */
public final class UseRecordPatterns extends Recipe {

    @Override
    public String getDisplayName() {
        return "Use record patterns";
    }

    @Override
    public String getDescription() {
        return "Deconstruct records directly in `instanceof` branches when the record variable is used "
            + "only by single-use component accessors in an immediate return.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitIf(J.If iff, ExecutionContext ctx) {
                J.If visited = (J.If) super.visitIf(iff, ctx);
                if (!(visited.getIfCondition().getTree() instanceof J.InstanceOf instanceOf)
                        || instanceOf.getClazz() == null
                        || !(instanceOf.getClazz() instanceof TypedTree typeTree)
                        || !(instanceOf.getPattern() instanceof J.Identifier patternVariable)
                        || !(TypeUtils.asFullyQualified(typeTree.getType()) instanceof JavaType.Class recordType)
                        || recordType.getKind() != JavaType.FullyQualified.Kind.Record) {
                    return visited;
                }

                List<JavaType.Variable> components = recordComponents(recordType);
                if (components.isEmpty()) return visited;

                J.Return returned = immediateReturn(visited.getThenPart());
                if (returned == null || returned.getExpression() == null) return visited;

                String recordVariable = patternVariable.getSimpleName();
                AccessorUsage usage = analyzeUsage(returned.getExpression(), recordVariable, components);
                if (!usage.safe() || usage.counts().isEmpty()
                        || usage.counts().values().stream().anyMatch(count -> count != 1)) {
                    return visited;
                }

                Map<String, String> bindings = componentBindings(components, usage.counts());
                Expression rewrittenReturnExpression = (Expression) new JavaVisitor<Integer>() {
                    @Override
                    public J visitMethodInvocation(J.MethodInvocation method, Integer unused) {
                        J.MethodInvocation invocation = (J.MethodInvocation) super.visitMethodInvocation(method, unused);
                        String binding = accessorBinding(invocation, recordVariable, bindings);
                        if (binding == null) return invocation;
                        return new J.Identifier(
                            Tree.randomId(), invocation.getPrefix(), invocation.getMarkers(), List.of(),
                            binding, invocation.getType(), null);
                    }
                }.visitNonNull(returned.getExpression(), 0);

                J.Return rewrittenReturn = returned.withExpression(rewrittenReturnExpression);
                J.Block thenBlock = (J.Block) visited.getThenPart();
                J.Block rewrittenThen = thenBlock.withStatements(List.of(rewrittenReturn));

                String recordTypeSource = instanceOf.getClazz().printTrimmed(getCursor());
                String patternSource = components.stream()
                    .map(component -> bindings.containsKey(component.getName())
                        ? "var " + bindings.get(component.getName()) : "_")
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
                JavaTemplate javaTemplate = JavaTemplate.builder(
                    "#{any()} instanceof " + recordTypeSource + "(" + patternSource + ")")
                    .contextSensitive()
                    .build();
                Cursor ifCursor = updateCursor(visited);
                Cursor conditionCursor = new Cursor(ifCursor, visited.getIfCondition());
                Cursor instanceOfCursor = new Cursor(conditionCursor, instanceOf);
                Expression rewrittenCondition = javaTemplate.apply(
                    instanceOfCursor, instanceOf.getCoordinates().replace(), instanceOf.getExpression());
                return visited
                    .withIfCondition(visited.getIfCondition().withTree(rewrittenCondition))
                    .withThenPart(rewrittenThen);
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

            private J.Return immediateReturn(J thenPart) {
                if (!(thenPart instanceof J.Block block)
                        || block.getStatements().size() != 1
                        || !(block.getStatements().getFirst() instanceof J.Return returned)) {
                    return null;
                }
                return returned;
            }

            private AccessorUsage analyzeUsage(
                    Expression expression,
                    String recordVariable,
                    List<JavaType.Variable> components) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                List<String> componentNames = components.stream().map(JavaType.Variable::getName).toList();
                boolean[] safe = {true};
                new JavaVisitor<Integer>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier, Integer unused) {
                        J.Identifier visited = (J.Identifier) super.visitIdentifier(identifier, unused);
                        if (!recordVariable.equals(visited.getSimpleName())) return visited;
                        if (!(getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation invocation)
                                || invocation.getSelect() != identifier
                                || !componentNames.contains(invocation.getSimpleName())
                                || !hasNoArguments(invocation)) {
                            safe[0] = false;
                            return visited;
                        }
                        counts.merge(invocation.getSimpleName(), 1, Integer::sum);
                        return visited;
                    }
                }.visit(expression, 0);
                return new AccessorUsage(safe[0], counts);
            }

            private Map<String, String> componentBindings(
                    List<JavaType.Variable> components,
                    Map<String, Integer> usedComponents) {
                Map<String, String> bindings = new LinkedHashMap<>();
                List<String> allocated = new ArrayList<>();
                for (JavaType.Variable component : components) {
                    if (!usedComponents.containsKey(component.getName())) continue;
                    String candidate = VariableNameUtils.generateVariableName(
                        component.getName(), getCursor(), VariableNameUtils.GenerationStrategy.INCREMENT_NUMBER);
                    while (allocated.contains(candidate)) candidate = increment(candidate);
                    allocated.add(candidate);
                    bindings.put(component.getName(), candidate);
                }
                return bindings;
            }

            private String increment(String name) {
                int split = name.length();
                while (split > 0 && Character.isDigit(name.charAt(split - 1))) split--;
                int number = split == name.length() ? 1 : Integer.parseInt(name.substring(split)) + 1;
                return name.substring(0, split) + number;
            }

            private String accessorBinding(
                    J.MethodInvocation invocation,
                    String recordVariable,
                    Map<String, String> bindings) {
                if (!(invocation.getSelect() instanceof J.Identifier receiver)
                        || !recordVariable.equals(receiver.getSimpleName())
                        || !hasNoArguments(invocation)) {
                    return null;
                }
                return bindings.get(invocation.getSimpleName());
            }

            private boolean hasNoArguments(J.MethodInvocation invocation) {
                return invocation.getArguments().size() == 1
                    && invocation.getArguments().getFirst() instanceof J.Empty;
            }
        };
    }

    private record AccessorUsage(boolean safe, Map<String, Integer> counts) {
    }
}
