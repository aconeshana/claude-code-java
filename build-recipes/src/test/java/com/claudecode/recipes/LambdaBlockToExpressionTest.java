package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.staticanalysis.LambdaBlockToExpression;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class LambdaBlockToExpressionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new LambdaBlockToExpression());
    }

    @Test
    void singleStatementBlockToExpression() {
        rewriteRun(java(
            """
                class Example {
                    void run() {
                        Runnable r = () -> {
                            System.out.println("hello");
                        };
                    }
                }
                """,
            """
                class Example {
                    void run() {
                        Runnable r = () ->
                            System.out.println("hello");
                    }
                }
                """));
    }

    @Test
    void returnBlockToExpression() {
        rewriteRun(java(
            """
                class Example {
                    String run() {
                        java.util.function.Supplier<String> s = () -> {
                            return "value";
                        };
                        return s.get();
                    }
                }
                """,
            """
                class Example {
                    String run() {
                        java.util.function.Supplier<String> s = () -> "value";
                        return s.get();
                    }
                }
                """));
    }

    @Test
    void preservesMultiStatementBlock() {
        rewriteRun(java(
            """
                class Example {
                    void run() {
                        Runnable r = () -> {
                            System.out.println("a");
                            System.out.println("b");
                        };
                    }
                }
                """));
    }
}
