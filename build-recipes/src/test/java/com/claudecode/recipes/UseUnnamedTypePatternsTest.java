package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseUnnamedTypePatternsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseUnnamedTypePatterns());
    }

    @Test
    void replacesUnusedRulePattern() {
        rewriteRun(java(
            """
                sealed interface Value permits Text, Empty {}
                record Text(String value) implements Value {}
                record Empty() implements Value {}

                class Example {
                    int size(Value value) {
                        return switch (value) {
                            case Text text -> text.value().length();
                            case Empty emptyValue -> 0;
                        };
                    }
                }
                """,
            """
                sealed interface Value permits Text, Empty {}
                record Text(String value) implements Value {}
                record Empty() implements Value {}

                class Example {
                    int size(Value value) {
                        return switch (value) {
                            case Text text -> text.value().length();
                            case Empty _ -> 0;
                        };
                    }
                }
                """));
    }

    @Test
    void preservesReferencedPattern() {
        rewriteRun(java(
            """
                record Value(String text) {}

                class Example {
                    String read(Object value) {
                        return switch (value) {
                            case Value ignored -> ignored.text();
                            default -> "";
                        };
                    }
                }
                """));
    }

    @Test
    void preservesPatternReferencedByGuard() {
        rewriteRun(java(
            """
                record Value(String text) {}

                class Example {
                    String read(Object value) {
                        return switch (value) {
                            case Value ignored when ignored.text().isEmpty() -> "empty";
                            case Value valueRecord -> valueRecord.text();
                            default -> "";
                        };
                    }
                }
                """));
    }

    @Test
    void preservesEnumConstants() {
        rewriteRun(java(
            """
                enum Value { ignored, present }

                class Example {
                    int read(Value value) {
                        return switch (value) {
                            case ignored -> 0;
                            case present -> 1;
                        };
                    }
                }
                """));
    }
}
