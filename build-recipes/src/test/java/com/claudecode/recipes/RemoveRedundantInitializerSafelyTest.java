package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveRedundantInitializerSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveRedundantInitializerSafely());
    }

    @Test
    void stripsRedundantNullBeforeTryWhenCatchAndFinallyNeverReadVariable() {
        rewriteRun(java(
            """
                class Example {
                    String open() { return "s1"; }
                    void close(String id) {}
                    void notifyDone(boolean succeeded) {}

                    void runTask() {
                        String openedSessionId = null;
                        boolean succeeded = false;
                        try {
                            openedSessionId = open();
                            succeeded = true;
                            if (!openedSessionId.isEmpty()) {
                                close(openedSessionId);
                            }
                        } catch (RuntimeException failure) {
                            System.err.println(failure.getMessage());
                        } finally {
                            notifyDone(succeeded);
                        }
                    }
                }
                """,
            """
                class Example {
                    String open() { return "s1"; }
                    void close(String id) {}
                    void notifyDone(boolean succeeded) {}

                    void runTask() {
                        String openedSessionId;
                        boolean succeeded = false;
                        try {
                            openedSessionId = open();
                            succeeded = true;
                            if (!openedSessionId.isEmpty()) {
                                close(openedSessionId);
                            }
                        } catch (RuntimeException failure) {
                            System.err.println(failure.getMessage());
                        } finally {
                            notifyDone(succeeded);
                        }
                    }
                }
                """));
    }

    @Test
    void stripsRedundantInitializerBeforeExhaustiveIfElse() {
        rewriteRun(java(
            """
                class Example {
                    String choose(boolean flag) {
                        String label = null;
                        if (flag) {
                            label = "yes";
                        } else {
                            label = "no";
                        }
                        return label;
                    }
                }
                """,
            """
                class Example {
                    String choose(boolean flag) {
                        String label;
                        if (flag) {
                            label = "yes";
                        } else {
                            label = "no";
                        }
                        return label;
                    }
                }
                """));
    }

    @Test
    void stripsRedundantInitializerBeforeDirectOverwrite() {
        rewriteRun(java(
            """
                class Example {
                    int compute(int x) {
                        int result = 0;
                        int step = x + 1;
                        result = step * 2;
                        return result;
                    }
                }
                """,
            """
                class Example {
                    int compute(int x) {
                        int result;
                        int step = x + 1;
                        result = step * 2;
                        return result;
                    }
                }
                """));
    }

    @Test
    void preservesInitializerWhenReadInFinallyOrAfterSwallowedCatch() {
        rewriteRun(java(
            """
                class Example {
                    String load() { return "ok"; }
                    void cleanup(String resource) {}

                    String runWithCleanup() {
                        String resource = null;
                        try {
                            resource = load();
                        } finally {
                            if (resource != null) {
                                cleanup(resource);
                            }
                        }
                        return resource;
                    }

                    String runSwallowed() {
                        String value = null;
                        try {
                            value = load();
                        } catch (RuntimeException ignored) {
                        }
                        return value;
                    }
                }
                """));
    }

    @Test
    void preservesInitializerWhenIfHasNoElseOrSideEffectInitializer() {
        rewriteRun(java(
            """
                class Example {
                    int nextDefault() { return 1; }

                    String partialIf(boolean flag) {
                        String out = null;
                        if (flag) {
                            out = "set";
                        }
                        return out;
                    }

                    int sideEffectInit(int x) {
                        int v = nextDefault();
                        v = x;
                        return v;
                    }
                }
                """));
    }
}
