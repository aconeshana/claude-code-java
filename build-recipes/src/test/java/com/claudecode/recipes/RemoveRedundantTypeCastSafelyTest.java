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
    void removesRedundantCastOnFreeGenericReturnInReturnAndDeclaration() {
        rewriteRun(java(
            """
                class Base {
                    @SuppressWarnings("unchecked")
                    <T extends Base> T deepCopy() {
                        return (T) this;
                    }
                }

                class Sub extends Base {}

                class Example {
                    Sub copy(Base raw) {
                        Sub local = (Sub) raw.deepCopy();
                        if (local != null) {
                            return (Sub) raw.deepCopy();
                        }
                        return local;
                    }
                }
                """,
            """
                class Base {
                    @SuppressWarnings("unchecked")
                    <T extends Base> T deepCopy() {
                        return (T) this;
                    }
                }

                class Sub extends Base {}

                class Example {
                    Sub copy(Base raw) {
                        Sub local = raw.deepCopy();
                        if (local != null) {
                            return raw.deepCopy();
                        }
                        return local;
                    }
                }
                """));
    }

    @Test
    void preservesCastWhenTargetIsVarOrNonGenericDowncast() {
        rewriteRun(java(
            """
                class Base {
                    @SuppressWarnings("unchecked")
                    <T extends Base> T deepCopy() {
                        return (T) this;
                    }
                }

                class Sub extends Base {}

                class Example {
                    Sub keep(Base raw) {
                        var copy = (Sub) raw.deepCopy();
                        return (Sub) raw;
                    }
                }
                """));
    }

    @Test
    void preservesCastWhenOverloadedSiblingHasFreeReturnTypeButInvokedOverloadBindsClassToken() {
        rewriteRun(java(
            """
                class Base {}
                class Sub extends Base {}
                class Spec {}

                class Mapper {
                    <T> T readValue(String json, Spec spec) { return null; }
                    <T> T readValue(String json, Class<T> type) { return null; }
                }

                class Example {
                    Sub parse(Mapper mapper, String json) {
                        Sub sub = (Sub) mapper.readValue(json, Base.class);
                        return sub;
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
