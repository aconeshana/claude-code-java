package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseCollectionCopyConstructorsSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseCollectionCopyConstructorsSafely());
    }

    @Test
    void replacesMapPutAllWithCopyConstructor() {
        rewriteRun(java(
            """
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, Integer> copy(Map<String, Integer> source) {
                        Map<String, Integer> result = new LinkedHashMap<>();
                        result.putAll(source);
                        return result;
                    }
                }
                """,
            """
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, Integer> copy(Map<String, Integer> source) {
                        Map<String, Integer> result = new LinkedHashMap<>(source);
                        return result;
                    }
                }
                """));
    }

    @Test
    void replacesCollectionAddAllWithCopyConstructor() {
        rewriteRun(java(
            """
                import java.util.ArrayList;
                import java.util.List;

                class Example {
                    List<String> copy(List<String> source) {
                        List<String> result = new ArrayList<>();
                        result.addAll(source);
                        return result;
                    }
                }
                """,
            """
                import java.util.ArrayList;
                import java.util.List;

                class Example {
                    List<String> copy(List<String> source) {
                        List<String> result = new ArrayList<>(source);
                        return result;
                    }
                }
                """));
    }

    @Test
    void preservesArgumentsThatReferenceTheTarget() {
        rewriteRun(java(
            """
                import java.util.LinkedHashMap;
                import java.util.Map;

                class Example {
                    Map<String, Integer> filter(Map<String, Integer> source,
                                                Map<String, Integer> current) {
                        return source;
                    }

                    Map<String, Integer> copy(Map<String, Integer> source) {
                        Map<String, Integer> result = new LinkedHashMap<>();
                        result.putAll(filter(source, result));
                        return result;
                    }
                }
                """));
    }

    @Test
    void preservesNonAdjacentOperationsAndRiskyConstructors() {
        rewriteRun(java(
            """
                import java.util.Map;
                import java.util.TreeMap;

                class Example {
                    Map<String, Integer> copy(Map<String, Integer> source) {
                        Map<String, Integer> result = new TreeMap<>();
                        System.out.println(source.size());
                        result.putAll(source);
                        return result;
                    }

                    Map<String, Integer> sortedCopy(Map<String, Integer> source) {
                        Map<String, Integer> result = new TreeMap<>();
                        result.putAll(source);
                        return result;
                    }
                }
                """));
    }

    @Test
    void preservesSelfCopiesAndCustomCollections() {
        rewriteRun(java(
            """
                import java.util.HashMap;

                class Example {
                    static class CustomMap<K, V> extends HashMap<K, V> {}

                    void copy() {
                        HashMap<String, Integer> self = new HashMap<>();
                        self.putAll(self);
                        CustomMap<String, Integer> custom = new CustomMap<>();
                        custom.putAll(self);
                    }
                }
                """));
    }
}
