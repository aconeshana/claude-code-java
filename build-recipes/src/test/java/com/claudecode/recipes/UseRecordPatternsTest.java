package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseRecordPatternsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseRecordPatterns());
    }

    @Test
    void deconstructsAnySingleComponentRecord() {
        rewriteRun(java(
            """
                class Example {
                    record Success(String absolutePath) {}

                    String read(Object result) {
                        if (result instanceof Success success) {
                            return success.absolutePath();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Success(String absolutePath) {}

                    String read(Object result) {
                        if (result instanceof Success(var absolutePath)) {
                            return absolutePath;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void supportsMultipleComponentsAndUnusedPatterns() {
        rewriteRun(java(
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair pair) {
                            return pair.right();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair(_, var right)) {
                            return right;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void preservesRecordComponentOrderWhenSeveralAreUsed() {
        rewriteRun(java(
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair pair) {
                            return pair.left() + pair.right();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair(var left, var right)) {
                            return left + right;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void avoidsVariableNameCollisions() {
        rewriteRun(java(
            """
                class Example {
                    record Success(String absolutePath) {}

                    String read(Object result) {
                        String absolutePath = "fallback";
                        if (result instanceof Success success) {
                            return success.absolutePath();
                        }
                        return absolutePath;
                    }
                }
                """,
            """
                class Example {
                    record Success(String absolutePath) {}

                    String read(Object result) {
                        String absolutePath = "fallback";
                        if (result instanceof Success(var absolutePath1)) {
                            return absolutePath1;
                        }
                        return absolutePath;
                    }
                }
                """));
    }

    @Test
    void preservesElseBranches() {
        rewriteRun(java(
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped.number();
                        } else {
                            return -1;
                        }
                    }
                }
                """,
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value(var number)) {
                            return number;
                        } else {
                            return -1;
                        }
                    }
                }
                """));
    }

    @Test
    void doesNotChangeWholeRecordUsage() {
        rewriteRun(java(
            """
                class Example {
                    record Value(int number) {}

                    Value read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped;
                        }
                        return null;
                    }
                }
                """));
    }

    @Test
    void doesNotChangeRepeatedAccessorCalls() {
        rewriteRun(java(
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped.number() + wrapped.number();
                        }
                        return 0;
                    }
                }
                """));
    }

    @Test
    void doesNotChangeNonRecordPatterns() {
        rewriteRun(java(
            """
                class Example {
                    static final class Value {
                        int number() { return 1; }
                    }

                    int read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped.number();
                        }
                        return 0;
                    }
                }
                """));
    }
}
