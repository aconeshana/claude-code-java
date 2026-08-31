package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ReplaceLambdaWithMethodReferenceSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceLambdaWithMethodReferenceSafely());
    }

    @Test
    void replacesUniquelyApplicableZeroArgumentOverload() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        String text(String defaultValue) { return defaultValue; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(node -> node.text()).orElse("");
                    }
                }
                """,
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        String text(String defaultValue) { return defaultValue; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(Example.Value::text).orElse("");
                    }
                }
                """));
    }

    @Test
    void supportsExplicitLambdaParameterTypes() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        String text(String defaultValue) { return defaultValue; }
                    }

                    String read(Optional<Value> value) {
                        return value.map((Value node) -> node.text()).orElse("");
                    }
                }
                """,
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        String text(String defaultValue) { return defaultValue; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(Example.Value::text).orElse("");
                    }
                }
                """));
    }

    @Test
    void preservesStaticOverloadApplicableToTheSameFunctionShape() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        static String text(Value value) { return ""; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(node -> node.text()).orElse("");
                    }
                }
                """));
    }

    @Test
    void ignoresStaticOverloadWithAnIncompatibleParameterType() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        static String text(String value) { return value; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(node -> node.text()).orElse("");
                    }
                }
                """,
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        static String text(String value) { return value; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(Example.Value::text).orElse("");
                    }
                }
                """));
    }

    @Test
    void preservesVarargsOverloadApplicableWithNoArguments() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    static class Value {
                        String text() { return ""; }
                        String text(String... values) { return ""; }
                    }

                    String read(Optional<Value> value) {
                        return value.map(node -> node.text()).orElse("");
                    }
                }
                """));
    }

    @Test
    void preservesLazyArrayAccessReceivers() {
        rewriteRun(java(
            """
                import java.util.function.Supplier;

                class Example {
                    String value() { return ""; }

                    Supplier<String> deferred(Example[] holder) {
                        return () -> holder[0].value();
                    }
                }
                """));
    }
}
