package com.claudecode.recipes;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class UseMathClampSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseMathClampSafely());
    }

    @Test
    void rewritesLiteralIntAndLongClampsInBothOrders() {
        rewriteRun(
            java(
                """
                class Sample {
                    int byteClamp(int v) {
                        return Math.max(0, Math.min(255, v));
                    }

                    int percentClamp(int used) {
                        return Math.min(100, Math.max(0, used));
                    }

                    int commutedArgs(int width) {
                        return Math.min(Math.max(width - 5, 4), 22);
                    }

                    long longClamp(long value) {
                        return Math.max(-10L, Math.min(1000L, value));
                    }
                }
                """,
                """
                class Sample {
                    int byteClamp(int v) {
                        return Math.clamp(v, 0, 255);
                    }

                    int percentClamp(int used) {
                        return Math.clamp(used, 0, 100);
                    }

                    int commutedArgs(int width) {
                        return Math.clamp(width - 5, 4, 22);
                    }

                    long longClamp(long value) {
                        return Math.clamp(value, -10L, 1000L);
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesDynamicInvertedFloatingPointOrScaledExpressions() {
        rewriteRun(
            java(
                """
                class Sample {
                    int dynamicUpper(int v, int hi) {
                        return Math.max(0, Math.min(hi, v));
                    }

                    int invertedBounds(int v) {
                        return Math.max(10, Math.min(5, v));
                    }

                    double floatingClamp(double r) {
                        return Math.max(0d, Math.min(1d, r));
                    }

                    int scaledIntermediate(long current, long required) {
                        return (int) Math.min(100L, Math.max(0L, current) * 100L / required);
                    }
                }
                """
            )
        );
    }
}
