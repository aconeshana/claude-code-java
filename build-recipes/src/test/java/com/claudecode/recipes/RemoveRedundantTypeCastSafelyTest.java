package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveRedundantTypeCastSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveRedundantTypeCastSafely());
    }

    @Test
    void removesOrdinaryRedundantCast() {
        rewriteRun(java(
            """
                class Example {
                    int bounded(long value) {
                        return (int) Math.clamp(value, 1, 10);
                    }
                }
                """,
            """
                class Example {
                    int bounded(long value) {
                        return Math.clamp(value, 1, 10);
                    }
                }
                """));
    }

    @Test
    void preservesMethodHandleInvokeExactCast() {
        rewriteRun(java(
            """
                import java.lang.invoke.MethodHandle;

                class Example {
                    int callNative(MethodHandle handle, int fd) throws Throwable {
                        return (int) handle.invokeExact(fd);
                    }
                }
                """));
    }
}
