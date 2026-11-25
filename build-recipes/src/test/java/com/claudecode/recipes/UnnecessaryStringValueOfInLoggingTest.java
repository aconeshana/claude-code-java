package com.claudecode.recipes;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class UnnecessaryStringValueOfInLoggingTest implements RewriteTest {

    private static final String SLF4J_LOGGER_STUB = """
        package org.slf4j;

        public interface Logger {
            void debug(String msg);
            void debug(String format, Object arg);
            void debug(String format, Object arg1, Object arg2);
            void debug(String format, Object... arguments);
            void info(String format, Object arg);
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UnnecessaryStringValueOfInLogging());
    }

    @Test
    void removesRedundantStringValueOfInLogPlaceholderArguments() {
        rewriteRun(
            java(SLF4J_LOGGER_STUB),
            java(
                """
                import org.slf4j.Logger;

                class Sample {
                    void logFailure(Logger log, long elapsedMs, Exception e) {
                        log.debug("elapsedMs={} cause={}", elapsedMs, String.valueOf(e.getCause()), e.getCause());
                        log.info("count={}", String.valueOf(42));
                    }
                }
                """,
                """
                import org.slf4j.Logger;

                class Sample {
                    void logFailure(Logger log, long elapsedMs, Exception e) {
                        log.debug("elapsedMs={} cause={}", elapsedMs, e.getCause(), e.getCause());
                        log.info("count={}", 42);
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesSemanticStringValueOfInLogging() {
        rewriteRun(
            java(SLF4J_LOGGER_STUB),
            java(
                """
                import org.slf4j.Logger;

                class Sample {
                    void keep(Logger log, Exception e, char[] chars, Object x) {
                        log.debug("cause={}", String.valueOf(e));
                        log.debug("chars={}", String.valueOf(chars));
                        log.debug(String.valueOf(x));
                    }
                }
                """
            )
        );
    }
}
