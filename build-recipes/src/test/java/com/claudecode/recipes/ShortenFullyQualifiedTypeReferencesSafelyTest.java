package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ShortenFullyQualifiedTypeReferencesSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ShortenFullyQualifiedTypeReferencesSafely());
    }

    @Test
    void addsAnImportWhenTheSimpleNameIsUnambiguous() {
        rewriteRun(
            java(
                """
                    package support;
                    public final class GoalCommand {
                        public static final String ERROR = "error";
                    }
                    """),
            java(
                """
                    package example;

                    class Example {
                        String message() {
                            return support.GoalCommand.ERROR;
                        }
                    }
                    """,
                """
                    package example;

                    import support.GoalCommand;

                    class Example {
                        String message() {
                            return GoalCommand.ERROR;
                        }
                    }
                    """));
    }

    @Test
    void preservesFullyQualifiedNamesWhenTwoTypesShareASimpleName() {
        rewriteRun(java(
            """
                package example;

                record Manifest(String value) {}

                final class Port {
                    record Manifest(String value) {}
                }

                class Example {
                    Port.Manifest read() {
                        example.Manifest manifest = new example.Manifest("value");
                        return new Port.Manifest(manifest.value());
                    }
                }
                """));
    }
}
