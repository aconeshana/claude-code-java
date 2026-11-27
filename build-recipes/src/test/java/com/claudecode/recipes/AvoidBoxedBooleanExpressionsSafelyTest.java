package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class AvoidBoxedBooleanExpressionsSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AvoidBoxedBooleanExpressionsSafely());
    }

    @Test
    void simplifiesBooleanTrueEqualsInNullGuardedTernaryFalseBranch() {
        rewriteRun(java(
            """
                class Example {
                    Boolean multimodal;

                    int state() {
                        return multimodal == null ? 0 : Boolean.TRUE.equals(multimodal) ? 1 : 2;
                    }
                }
                """,
            """
                class Example {
                    Boolean multimodal;

                    int state() {
                        return multimodal == null ? 0 : multimodal ? 1 : 2;
                    }
                }
                """));
    }

    @Test
    void simplifiesBooleanTrueEqualsInNonNullGuardedTernaryTrueBranch() {
        rewriteRun(java(
            """
                class Example {
                    int state(Boolean flag) {
                        return flag != null ? (Boolean.TRUE.equals(flag) ? 1 : 2) : 0;
                    }
                }
                """,
            """
                class Example {
                    int state(Boolean flag) {
                        return flag != null ? (flag ? 1 : 2) : 0;
                    }
                }
                """));
    }

    @Test
    void preservesBooleanTrueEqualsWhenNullable() {
        rewriteRun(java(
            """
                class Example {
                    int state(Boolean flag) {
                        return Boolean.TRUE.equals(flag) ? 1 : 2;
                    }
                }
                """));
    }
}
