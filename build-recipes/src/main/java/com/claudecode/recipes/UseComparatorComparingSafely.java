package com.claudecode.recipes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openrewrite.ExecutionContext;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaPrinter;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Replaces a two-parameter {@link java.util.Comparator} lambda that delegates to a boxed
 * {@code compare} method with the equivalent key-extractor combinator, including the reversed
 * form when the two operands are compared in swapped order.
 */
public final class UseComparatorComparingSafely extends Recipe {

    private static final MethodMatcher LONG_COMPARE =
        new MethodMatcher("java.lang.Long compare(long, long)");
    private static final MethodMatcher INTEGER_COMPARE =
        new MethodMatcher("java.lang.Integer compare(int, int)");
    private static final MethodMatcher DOUBLE_COMPARE =
        new MethodMatcher("java.lang.Double compare(double, double)");

    @Override
    public String getDisplayName() {
        return "Use Comparator key-extractor combinators";
    }

    @Override
    public String getDescription() {
        return "Replaces `(a, b) -> Long.compare(key(a), key(b))`-shaped comparator lambdas with "
            + "`Comparator.comparingLong(a -> key(a))`, including the reversed form when the "
            + "operands are compared in swapped order.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitLambda(J.Lambda lambda, ExecutionContext ctx) {
                J.Lambda visited = (J.Lambda) super.visitLambda(lambda, ctx);
                String replacement = replacementFor(visited);
                if (replacement == null) return visited;
                maybeAddImport("java.util.Comparator");
                return JavaTemplate.builder(replacement)
                    .contextSensitive()
                    .imports("java.util.Comparator")
                    .build()
                    .apply(updateCursor(visited), visited.getCoordinates().replace());
            }

            private String replacementFor(J.Lambda lambda) {
                if (!TypeUtils.isOfClassType(lambda.getType(), "java.util.Comparator")) return null;
                List<J> parameters = lambda.getParameters().getParameters();
                if (parameters.size() != 2
                        || !(parameters.get(0) instanceof J.VariableDeclarations left)
                        || !(parameters.get(1) instanceof J.VariableDeclarations right)
                        || left.getVariables().size() != 1 || right.getVariables().size() != 1) {
                    return null;
                }
                JavaType.Variable p0 = left.getVariables().getFirst().getVariableType();
                JavaType.Variable p1 = right.getVariables().getFirst().getVariableType();
                if (p0 == null || p1 == null) return null;

                Expression body = unwrap(lambda.getBody());
                if (!(body instanceof J.MethodInvocation invocation)
                        || invocation.getArguments().size() != 2) {
                    return null;
                }
                String comparingMethod;
                if (LONG_COMPARE.matches(invocation)) comparingMethod = "comparingLong";
                else if (INTEGER_COMPARE.matches(invocation)) comparingMethod = "comparingInt";
                else if (DOUBLE_COMPARE.matches(invocation)) comparingMethod = "comparingDouble";
                else return null;

                Expression arg0 = invocation.getArguments().get(0);
                Expression arg1 = invocation.getArguments().get(1);
                JavaType.Variable ref0 = soleReferencedParameter(arg0, p0, p1);
                JavaType.Variable ref1 = soleReferencedParameter(arg1, p0, p1);
                if (ref0 == null || ref1 == null || ref0 == ref1) return null;

                boolean ascending = ref0 == p0 && ref1 == p1;
                boolean descending = ref0 == p1 && ref1 == p0;
                if (!ascending && !descending) return null;
                if (!normalizedText(arg0, ref0).equals(normalizedText(arg1, ref1))) return null;

                String parameterTypeName = printSpellableType(p0.getType());
                if (parameterTypeName == null) return null;

                Expression keyExpression = ascending ? arg0 : arg1;
                String parameterName = left.getVariables().getFirst().getSimpleName();
                String keyText = keyExpression.printTrimmed(getCursor());
                String combinator = "Comparator." + comparingMethod + "((" + parameterTypeName + " "
                    + parameterName + ") -> " + keyText + ")";
                return descending ? combinator + ".reversed()" : combinator;
            }

            /**
             * Prints {@code type} as source text spellable at the call site, or returns {@code null}
             * when it cannot be reconstructed losslessly: a local/anonymous class has no writable
             * qualified name (its binary name, e.g. {@code Outer$1Scored}, is not valid Java source),
             * and dropping a parameterized type's arguments would erase them to raw types and silently
             * change the meaning of member accesses in the key expression (e.g. {@code Map.Entry}
             * without {@code <K, V>} erases {@code getValue()} to {@code Object}).
             */
            private String printSpellableType(JavaType type) {
                if (type instanceof JavaType.Parameterized parameterized) {
                    String raw = printSpellableType(parameterized.getType());
                    if (raw == null) return null;
                    StringBuilder sb = new StringBuilder(raw).append('<');
                    List<JavaType> typeParameters = parameterized.getTypeParameters();
                    for (int i = 0; i < typeParameters.size(); i++) {
                        String argument = printSpellableType(typeParameters.get(i));
                        if (argument == null) return null;
                        if (i > 0) sb.append(", ");
                        sb.append(argument);
                    }
                    return sb.append('>').toString();
                }
                if (type instanceof JavaType.Array array) {
                    String element = printSpellableType(array.getElemType());
                    return element == null ? null : element + "[]";
                }
                if (type instanceof JavaType.GenericTypeVariable generic) {
                    String name = generic.getName();
                    return "?".equals(name) || name.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? name : null;
                }
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                if (fq == null) return null;
                String fqName = fq.getFullyQualifiedName();
                for (String segment : fqName.split("[.$]")) {
                    if (!segment.isEmpty() && Character.isDigit(segment.charAt(0))) return null;
                }
                return fqName.replace('$', '.');
            }

            private Expression unwrap(J body) {
                if (body instanceof J.Block block) {
                    if (block.getStatements().size() != 1) return null;
                    Statement statement = block.getStatements().getFirst();
                    return statement instanceof J.Return returned ? returned.getExpression() : null;
                }
                return body instanceof Expression expression ? expression : null;
            }

            private JavaType.Variable soleReferencedParameter(Expression expression,
                    JavaType.Variable p0, JavaType.Variable p1) {
                Set<JavaType.Variable> referenced = new HashSet<>();
                new JavaIsoVisitor<Set<JavaType.Variable>>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier,
                            Set<JavaType.Variable> found) {
                        JavaType.Variable fieldType = identifier.getFieldType();
                        if (fieldType == p0 || fieldType == p1) {
                            found.add(fieldType);
                        }
                        return super.visitIdentifier(identifier, found);
                    }
                }.visit(expression, referenced);
                return referenced.size() == 1 ? referenced.iterator().next() : null;
            }

            /** Prints {@code expression} with every reference to {@code target} replaced by a placeholder. */
            private String normalizedText(Expression expression, JavaType.Variable target) {
                PrintOutputCapture<Integer> output = new PrintOutputCapture<>(0);
                new JavaPrinter<Integer>() {
                    @Override
                    public J visitIdentifier(J.Identifier identifier,
                            PrintOutputCapture<Integer> capture) {
                        if (identifier.getFieldType() == target) {
                            beforeSyntax(identifier, Space.Location.IDENTIFIER_PREFIX, capture);
                            capture.append("__key__");
                            afterSyntax(identifier, capture);
                            return identifier;
                        }
                        return super.visitIdentifier(identifier, capture);
                    }
                }.visit(expression, output);
                return output.getOut().trim();
            }
        };
    }
}
