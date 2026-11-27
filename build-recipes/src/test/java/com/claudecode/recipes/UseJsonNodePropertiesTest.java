package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseJsonNodePropertiesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseJsonNodeProperties())
            .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
    }

    @Test
    void replacesFieldsForEachRemainingWithPropertiesForEach() {
        rewriteRun(java(
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, String> collect(JsonNode node) {
                        Map<String, String> result = new LinkedHashMap<>();
                        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
                        return result;
                    }
                }
                """,
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, String> collect(JsonNode node) {
                        Map<String, String> result = new LinkedHashMap<>();
                        node.properties().forEach(e -> result.put(e.getKey(), e.getValue().asText()));
                        return result;
                    }
                }
                """));
    }

    @Test
    void replacesIteratorWhileLoopWithEnhancedFor() {
        rewriteRun(java(
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.Iterator;
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, String> stringMap(JsonNode request, String fieldName) {
                        JsonNode value = request.path(fieldName);
                        if (!value.isObject()) return Map.of();
                        Map<String, String> result = new LinkedHashMap<>();
                        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            result.put(entry.getKey(), entry.getValue().asText());
                        }
                        return result;
                    }
                }
                """,
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, String> stringMap(JsonNode request, String fieldName) {
                        JsonNode value = request.path(fieldName);
                        if (!value.isObject()) return Map.of();
                        Map<String, String> result = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonNode> entry : value.properties()) {
                            result.put(entry.getKey(), entry.getValue().asText());
                        }
                        return result;
                    }
                }
                """));
    }

    @Test
    void replacesIteratorForLoopWithEnhancedFor() {
        rewriteRun(java(
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.Iterator;
                import java.util.Map;

                class Example {
                    int count(JsonNode input) {
                        int n = 0;
                        for (Iterator<Map.Entry<String, JsonNode>> it = input.fields(); it.hasNext(); ) {
                            Map.Entry<String, JsonNode> entry = it.next();
                            if (entry.getValue().isTextual()) n++;
                        }
                        return n;
                    }
                }
                """,
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.Map;

                class Example {
                    int count(JsonNode input) {
                        int n = 0;
                        for (Map.Entry<String, JsonNode> entry : input.properties()) {
                            if (entry.getValue().isTextual()) n++;
                        }
                        return n;
                    }
                }
                """));
    }

    @Test
    void fallsBackToPropertiesIteratorWhenIteratorRemoveIsUsed() {
        rewriteRun(java(
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.Iterator;
                import java.util.Map;

                class Example {
                    void prune(JsonNode node) {
                        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            if (entry.getValue().isNull()) fields.remove();
                        }
                    }
                }
                """,
            """
                import com.fasterxml.jackson.databind.JsonNode;
                import java.util.Iterator;
                import java.util.Map;

                class Example {
                    void prune(JsonNode node) {
                        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            if (entry.getValue().isNull()) fields.remove();
                        }
                    }
                }
                """));
    }

    @Test
    void fallsBackToPropertiesIteratorForDirectIteratorCalls() {
        rewriteRun(java(
            """
                import com.fasterxml.jackson.databind.JsonNode;

                class Example {
                    String firstKey(JsonNode node) {
                        if (!node.fields().hasNext()) return "";
                        return node.fields().next().getKey();
                    }
                }
                """,
            """
                import com.fasterxml.jackson.databind.JsonNode;

                class Example {
                    String firstKey(JsonNode node) {
                        if (!node.properties().iterator().hasNext()) return "";
                        return node.properties().iterator().next().getKey();
                    }
                }
                """));
    }

    @Test
    void ignoresNonJsonNodeFieldsMethod() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    record Output(List<String> fields) {}

                    List<String> read(Output output) {
                        return output.fields();
                    }
                }
                """));
    }
}
