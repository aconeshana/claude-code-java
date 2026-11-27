package com.claudecode.recipes;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class UseStringBuilderRepeatSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseStringBuilderRepeatSafely());
    }

    @Test
    void rewritesSingleCharAndMultiCharStringLiterals() {
        rewriteRun(
            java(
                """
                class Sample {
                    String format(int spaces, String label) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(" ".repeat(spaces)).append(label);
                        sb.append("ab".repeat(3));
                        sb.append("\\n".repeat(2));
                        sb.append("'".repeat(4));
                        sb.append("\\\\".repeat(2));
                        sb.append("─".repeat(10));
                        sb.append("".repeat(spaces));
                        return sb.toString();
                    }

                    String buffer(int count) {
                        StringBuffer buf = new StringBuffer();
                        buf.append("x".repeat(count));
                        return buf.toString();
                    }
                }
                """,
                """
                class Sample {
                    String format(int spaces, String label) {
                        StringBuilder sb = new StringBuilder();
                        sb.repeat(' ', spaces).append(label);
                        sb.repeat("ab", 3);
                        sb.repeat('\\n', 2);
                        sb.repeat('\\'', 4);
                        sb.repeat('\\\\', 2);
                        sb.repeat('─', 10);
                        sb.repeat("", spaces);
                        return sb.toString();
                    }

                    String buffer(int count) {
                        StringBuffer buf = new StringBuffer();
                        buf.repeat('x', count);
                        return buf.toString();
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesFluentMultilineFormattingAndConcatenation() {
        rewriteRun(
            java(
                """
                class Sample {
                    String chained(String prefix, int count) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("head")
                            .append("x".repeat(80))
                            .append((prefix + "-").repeat(count))
                            .append("tail");
                        return sb.toString();
                    }
                }
                """,
                """
                class Sample {
                    String chained(String prefix, int count) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("head")
                            .repeat('x', 80)
                            .repeat(prefix + "-", count)
                            .append("tail");
                        return sb.toString();
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesPotentiallyNullStringExpressions() {
        rewriteRun(
            java(
                """
                class Sample {
                    void dynamic(StringBuilder sb, String indent, int count) {
                        sb.append(indent.repeat(count));
                        sb.append(compute().repeat(count));
                    }

                    private String compute() {
                        return " ";
                    }
                }
                """
            )
        );
    }
}
