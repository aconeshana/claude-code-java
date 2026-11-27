package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class InlineRedundantLocalVariableSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new InlineRedundantLocalVariableSafely());
    }

    @Test
    void inlinesFinalInstanceFieldAlias() {
        rewriteRun(java(
            """
                import java.util.concurrent.ScheduledExecutorService;
                import java.util.concurrent.ScheduledFuture;
                import java.util.concurrent.TimeUnit;

                class Watchdog {
                    private final ScheduledExecutorService timer;
                    private final long idleMillis;

                    Watchdog(ScheduledExecutorService timer, long idleMillis) {
                        this.timer = timer;
                        this.idleMillis = idleMillis;
                    }

                    ScheduledFuture<?> rearm(Runnable task) {
                        long delay = idleMillis;
                        return timer.schedule(task, delay, TimeUnit.MILLISECONDS);
                    }
                }
                """,
            """
                import java.util.concurrent.ScheduledExecutorService;
                import java.util.concurrent.ScheduledFuture;
                import java.util.concurrent.TimeUnit;

                class Watchdog {
                    private final ScheduledExecutorService timer;
                    private final long idleMillis;

                    Watchdog(ScheduledExecutorService timer, long idleMillis) {
                        this.timer = timer;
                        this.idleMillis = idleMillis;
                    }

                    ScheduledFuture<?> rearm(Runnable task) {
                        return timer.schedule(task, idleMillis, TimeUnit.MILLISECONDS);
                    }
                }
                """));
    }

    @Test
    void inlinesEffectivelyFinalLocalAliasIncludingLambdaCaptureAndPreservesComments() {
        rewriteRun(
            java(
                """
                    import java.nio.file.Path;

                    record Holder(Path value) {}
                    """),
            java(
                """
                    import java.nio.file.Path;
                    import java.util.function.Supplier;

                    class Assembler {
                        Supplier<String> build(Holder holder) {
                            if (holder instanceof Holder(var path)) {
                                // Keep supplier wired to the resolved path value.
                                final Path captured = path;
                                return () -> captured.toString();
                            }
                            return () -> "";
                        }
                    }
                    """,
                """
                    import java.util.function.Supplier;

                    class Assembler {
                        Supplier<String> build(Holder holder) {
                            if (holder instanceof Holder(var path)) {
                                // Keep supplier wired to the resolved path value.
                                return () -> path.toString();
                            }
                            return () -> "";
                        }
                    }
                    """));
    }

    @Test
    void preservesVolatileAndNonFinalFieldSnapshots() {
        rewriteRun(java(
            """
                class Holder {
                    private volatile Runnable listener;
                    private String mutableState;

                    void fire() {
                        Runnable current = listener;
                        if (current != null) {
                            current.run();
                        }
                        String snap = mutableState;
                        if (snap != null) {
                            System.out.println(snap);
                        }
                    }
                }
                """));
    }

    @Test
    void preservesFinalCopyOfMutatedLocalAndMutatedWorkingCopy() {
        rewriteRun(java(
            """
                import java.util.function.Supplier;

                class Example {
                    Supplier<String> captureMutated(boolean flag) {
                        String tmp = null;
                        if (flag) {
                            tmp = "ok";
                        }
                        final String captured = tmp;
                        return () -> captured;
                    }

                    int advance(int start) {
                        int cursor = start;
                        cursor++;
                        return cursor;
                    }
                }
                """));
    }

    @Test
    void preservesWideningUpcastStaticConstantAndShadowedField() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    private static final String DEFAULT_NAME = "demo";
                    private final String name = "instance";

                    long widen(int count) {
                        long wide = count;
                        return wide * 1024L;
                    }

                    Object upcast(String text) {
                        Object obj = text;
                        return obj.toString();
                    }

                    String constantRole() {
                        String expected = DEFAULT_NAME;
                        return expected;
                    }

                    String shadowed(List<String> items) {
                        String outerName = name;
                        for (String name : items) {
                            if (name.equals(outerName)) {
                                return name;
                            }
                        }
                        return "";
                    }

                    static class BaseTask {
                        protected int state = 1;
                    }

                    BaseTask anonymousSuperFieldShadow(int state) {
                        final int outerState = state;
                        return new BaseTask() {
                            int read() {
                                return outerState + state;
                            }
                        };
                    }

                    int descriptiveRoleForSingleCharSource(String source, int i) {
                        char c = source.charAt(i);
                        if (c == '\\'' || c == '"') {
                            char quote = c;
                            i++;
                            while (i < source.length() && source.charAt(i) != quote) {
                                i++;
                            }
                        }
                        return i;
                    }
                }
                """));
    }
}
