package com.claudecode.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/** Replaces exact element-copy iterations with the corresponding collection bulk operation. */
public final class UseBulkCollectionOperations extends Recipe {

    private static final MethodMatcher COLLECTION_ADD =
        new MethodMatcher("java.util.Collection add(..)", true);
    private static final MethodMatcher MAP_FOR_EACH =
        new MethodMatcher("java.util.Map forEach(..)", true);
    private static final MethodMatcher MAP_PUT =
        new MethodMatcher("java.util.Map put(..)", true);

    @Override
    public String getDisplayName() {
        return "Use collection bulk operations";
    }

    @Override
    public String getDescription() {
        return "Replaces exact add/put copy iterations with addAll, Collections.addAll, or putAll.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<>() {
          @Override
          public J visitForEachLoop(J.ForEachLoop forEachLoop, ExecutionContext ctx) {
            J.ForEachLoop visited = (J.ForEachLoop) super.visitForEachLoop(forEachLoop, ctx);
            if (!(visited.getControl().getVariable() instanceof J.VariableDeclarations variable)
                || variable.getVariables().size() != 1) {
              return visited;
            }
            J.MethodInvocation add = soleMethodInvocation(visited.getBody());
            if (add == null || !COLLECTION_ADD.matches(add) || add.getSelect() == null
                || !(add.getSelect() instanceof J.Identifier target)
                || add.getArguments().size() != 1
                || !(add.getArguments().getFirst() instanceof J.Identifier argument)
                || !variable.getVariables().getFirst().getSimpleName()
                .equals(argument.getSimpleName())) {
              return visited;
            }

            Expression source = visited.getControl().getIterable();
            if (sameIdentifier(target, source)) {
              return visited;
            }

            String targetSource = target.printTrimmed(getCursor());
            String sourceCode = source.printTrimmed(getCursor());
            String replacement;
            JavaType.Array array = TypeUtils.asArray(source.getType());
            if (array != null && !(array.getElemType() instanceof JavaType.Primitive)) {
              maybeAddImport("java.util.Collections");
              replacement = "Collections.addAll(" + targetSource + ", " + sourceCode + ");";
              return JavaTemplate.builder(replacement)
                  .contextSensitive()
                  .imports("java.util.Collections")
                  .build()
                  .apply(updateCursor(visited), visited.getCoordinates().replace());
            } else if (TypeUtils.isAssignableTo("java.util.Collection", source.getType())) {
              replacement = targetSource + ".addAll(" + sourceCode + ");";
            } else {
              return visited;
            }
            return JavaTemplate.builder(replacement)
                .contextSensitive()
                .build()
                .apply(updateCursor(visited), visited.getCoordinates().replace());
          }

          @Override
          public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation visited =
                (J.MethodInvocation) super.visitMethodInvocation(method, ctx);
            if (!MAP_FOR_EACH.matches(visited)
                || !(visited.getSelect() instanceof J.Identifier source)
                || visited.getArguments().size() != 1
                || !(visited.getArguments().getFirst() instanceof J.MemberReference reference)
                || !(reference.getContaining() instanceof J.Identifier target)
                || reference.getMethodType() == null || !MAP_PUT.matches(reference)
                || sameIdentifier(source, target)) {
              return visited;
            }
            String replacement = target.printTrimmed(getCursor()) + ".putAll("
                + source.printTrimmed(getCursor()) + ")";
            return JavaTemplate.builder(replacement)
                .contextSensitive()
                .build()
                .apply(updateCursor(visited), visited.getCoordinates().replace());
          }

          private J.MethodInvocation soleMethodInvocation(Statement body) {
            if (body instanceof J.MethodInvocation invocation) {
              return invocation;
            }
            if (body instanceof J.Block block && block.getStatements().size() == 1
                && block.getStatements().getFirst() instanceof J.MethodInvocation invocation) {
              return invocation;
            }
            return null;
          }

          private boolean sameIdentifier(J.Identifier left, Expression right) {
            return right instanceof J.Identifier identifier
                && left.getSimpleName().equals(identifier.getSimpleName());
          }
        };
    }
}
