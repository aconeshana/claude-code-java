package com.claudecode.recipes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/** Folds safe, adjacent collection population calls into JDK copy constructors. */
public final class UseCollectionCopyConstructorsSafely extends Recipe {

    private static final MethodMatcher COLLECTION_ADD_ALL =
        new MethodMatcher("java.util.Collection addAll(..)", true);
    private static final MethodMatcher MAP_PUT_ALL =
        new MethodMatcher("java.util.Map putAll(..)", true);

    private static final Set<String> COLLECTION_TYPES = Set.of(
        "java.util.ArrayList",
        "java.util.LinkedList",
        "java.util.HashSet",
        "java.util.LinkedHashSet",
        "java.util.ArrayDeque",
        "java.util.concurrent.CopyOnWriteArrayList",
        "java.util.concurrent.CopyOnWriteArraySet"
    );
    private static final Set<String> MAP_TYPES = Set.of(
        "java.util.HashMap",
        "java.util.LinkedHashMap",
        "java.util.concurrent.ConcurrentHashMap"
    );

    @Override
    public String getDisplayName() {
        return "Use safe collection copy constructors";
    }

    @Override
    public String getDescription() {
        return "Replaces adjacent no-argument construction and addAll/putAll calls with "
            + "copy constructors when the source does not reference the target variable.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
          @Override
          public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
            J.Block visited = super.visitBlock(block, ctx);
            List<Statement> statements = visited.getStatements();
            List<Statement> rewritten = new ArrayList<>(statements.size());
            boolean changed = false;

            for (int i = 0; i < statements.size(); i++) {
              Statement statement = statements.get(i);
              if (i + 1 >= statements.size()) {
                rewritten.add(statement);
                continue;
              }
              J.VariableDeclarations declaration = soleDeclaration(statement);
              J.MethodInvocation population = asMethodInvocation(statements.get(i + 1));
              J.VariableDeclarations replacement = replacement(declaration, population);
              if (replacement == null) {
                rewritten.add(statement);
                continue;
              }
              rewritten.add(replacement);
              i++;
              changed = true;
            }
            return changed ? visited.withStatements(rewritten) : visited;
          }

          private J.VariableDeclarations replacement(J.VariableDeclarations declaration,
              J.MethodInvocation population) {
            if (declaration == null || population == null
                || declaration.getVariables().size() != 1
                || !(declaration.getVariables().getFirst().getInitializer()
                instanceof J.NewClass constructor)
                || constructor.getArguments().size() != 1
                || !(constructor.getArguments().getFirst() instanceof J.Empty)
                || !(population.getSelect() instanceof J.Identifier target)
                || population.getArguments().size() != 1
                || population.getArguments().getFirst() instanceof J.Empty) {
              return null;
            }

            J.VariableDeclarations.NamedVariable variable = declaration.getVariables().getFirst();
            if (!variable.getSimpleName().equals(target.getSimpleName())) {
              return null;
            }

            String constructedType = TypeUtils.asFullyQualified(constructor.getType()) == null
                ? null : TypeUtils.asFullyQualified(constructor.getType()).getFullyQualifiedName();
            boolean supported = MAP_PUT_ALL.matches(population)
                ? MAP_TYPES.contains(constructedType)
                : COLLECTION_ADD_ALL.matches(population) && COLLECTION_TYPES.contains(
                    constructedType);
            if (!supported) {
              return null;
            }

            Expression source = population.getArguments().getFirst();
            if (referencesIdentifier(source, variable.getSimpleName())) {
              return null;
            }

            J.NewClass copyConstructor = constructor.withArguments(
                List.of(source.withPrefix(Space.EMPTY)));
            return declaration.withVariables(List.of(
                variable.withInitializer(copyConstructor)));
          }

          private J.VariableDeclarations soleDeclaration(Statement statement) {
            return statement instanceof J.VariableDeclarations declaration
                ? declaration : null;
          }

          private J.MethodInvocation asMethodInvocation(Statement statement) {
            return statement instanceof J.MethodInvocation invocation ? invocation : null;
          }

          private boolean referencesIdentifier(Expression expression, String name) {
            AtomicBoolean found = new AtomicBoolean();
            new JavaIsoVisitor<AtomicBoolean>() {
              @Override
              public J.Identifier visitIdentifier(J.Identifier identifier,
                  AtomicBoolean referenced) {
                if (name.equals(identifier.getSimpleName())) {
                  referenced.set(true);
                }
                return identifier;
              }
            }.visit(expression, found);
            return found.get();
          }
        };
    }
}
