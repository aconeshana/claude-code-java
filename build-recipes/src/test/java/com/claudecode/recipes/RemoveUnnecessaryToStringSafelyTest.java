package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveUnnecessaryToStringSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveUnnecessaryToStringSafely());
    }

    @Test
    void removesToStringOnFinalNonNullFieldInStringConcatenation() {
        rewriteRun(java(
            """
                class Example {
                    private final StringBuilder safePrefix = new StringBuilder();

                    String combine(String combined) {
                        return safePrefix.toString() + combined;
                    }
                }
                """,
            """
                class Example {
                    private final StringBuilder safePrefix = new StringBuilder();

                    String combine(String combined) {
                        return safePrefix + combined;
                    }
                }
                """));
    }

    @Test
    void removesToStringOnLocalNonNullStringBuilderOnRightSide() {
        rewriteRun(java(
            """
                class Example {
                    String format(String prefix) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("x");
                        return prefix + sb.toString();
                    }
                }
                """,
            """
                class Example {
                    String format(String prefix) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("x");
                        return prefix + sb;
                    }
                }
                """));
    }

    @Test
    void preservesToStringWhenReceiverMayBeNull() {
        rewriteRun(java(
            """
                class Example {
                    String format(StringBuilder maybeNull, String suffix) {
                        return maybeNull.toString() + suffix;
                    }
                }
                """));
    }

    @Test
    void preservesToStringWhenOtherOperandIsNotString() {
        rewriteRun(java(
            """
                class Example {
                    String format(int count) {
                        StringBuilder sb = new StringBuilder();
                        return sb.toString() + count;
                    }
                }
                """));
    }
}
