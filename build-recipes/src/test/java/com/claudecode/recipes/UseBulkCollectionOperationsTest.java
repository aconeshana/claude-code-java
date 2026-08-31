package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseBulkCollectionOperationsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseBulkCollectionOperations());
    }

    @Test
    void replacesArrayIterationWithCollectionsAddAll() {
        rewriteRun(java(
            """
                import java.util.HashSet;
                import java.util.Set;

                class Example {
                    void add(Set<String> target, String... values) {
                        for (String value : values) target.add(value);
                    }
                }
                """,
            """
                import java.util.Collections;
                import java.util.HashSet;
                import java.util.Set;

                class Example {
                    void add(Set<String> target, String... values) {
                        Collections.addAll(target, values);
                    }
                }
                """));
    }

    @Test
    void replacesCollectionIterationWithAddAll() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    void add(List<String> target, List<String> values) {
                        for (String value : values) {
                            target.add(value);
                        }
                    }
                }
                """,
            """
                import java.util.List;

                class Example {
                    void add(List<String> target, List<String> values) {
                        target.addAll(values);
                    }
                }
                """));
    }

    @Test
    void replacesMapForEachPutWithPutAll() {
        rewriteRun(java(
            """
                import java.util.Map;

                class Example {
                    void copy(Map<String, Integer> source, Map<String, Integer> target) {
                        source.forEach(target::put);
                    }
                }
                """,
            """
                import java.util.Map;

                class Example {
                    void copy(Map<String, Integer> source, Map<String, Integer> target) {
                        target.putAll(source);
                    }
                }
                """));
    }

    @Test
    void preservesLoopsWithAdditionalBehaviorOrTransformation() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    void copy(List<String> source, List<String> target) {
                        for (String value : source) {
                            if (!value.isBlank()) target.add(value);
                        }
                        for (String value : source) {
                            target.add(value.trim());
                        }
                        for (String value : source) {
                            target.add(value);
                            System.out.println(value);
                        }
                    }
                }
                """));
    }

    @Test
    void preservesUnstableTargetsAndSelfCopies() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    List<String> target() { return List.of(); }

                    void copy(List<String> values) {
                        for (String value : values) target().add(value);
                        for (String value : values) values.add(value);
                    }
                }
                """));
    }

    @Test
    void preservesPrimitiveArrayIterations() {
        rewriteRun(java(
            """
                import java.util.Set;

                class Example {
                    void copy(int[] values, Set<Integer> target) {
                        for (int value : values) target.add(value);
                    }
                }
                """));
    }

    @Test
    void preservesNonExactMapConsumersAndSelfCopies() {
        rewriteRun(java(
            """
                import java.util.Map;

                class Example {
                    void copy(Map<String, Integer> source, Map<String, Integer> target) {
                        source.forEach((key, value) -> target.put(key, value + 1));
                        source.forEach(source::put);
                    }
                }
                """));
    }
}
