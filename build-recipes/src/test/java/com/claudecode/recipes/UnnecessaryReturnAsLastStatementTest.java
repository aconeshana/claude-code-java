package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.staticanalysis.UnnecessaryReturnAsLastStatement;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UnnecessaryReturnAsLastStatementTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UnnecessaryReturnAsLastStatement());
    }

    @Test
    void removesOnlyTheTrailingReturnFromVoidMethod() {
        rewriteRun(java(
            """
                class Example {
                    void run(boolean stop) {
                        if (stop) return;
                        work();
                        return;
                    }

                    void work() {}
                }
                """,
            """
                class Example {
                    void run(boolean stop) {
                        if (stop) return;
                        work();
                    }

                    void work() {}
                }
                """));
    }

    @Test
    void removesTrailingReturnsFromTerminalIfBranches() {
        rewriteRun(java(
            """
                class Example {
                    void run(boolean enabled) {
                        if (enabled) {
                            work();
                            return;
                        } else {
                            fallback();
                            return;
                        }
                    }

                    void work() {}
                    void fallback() {}
                }
                """,
            """
                class Example {
                    void run(boolean enabled) {
                        if (enabled) {
                            work();
                        } else {
                            fallback();
                        }
                    }

                    void work() {}
                    void fallback() {}
                }
                """));
    }

    @Test
    void preservesValueReturningMethods() {
        rewriteRun(java(
            """
                class Example {
                    String read() {
                        return "value";
                    }
                }
                """));
    }
}
