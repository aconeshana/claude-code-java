package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ReplaceWhileWithEnhancedForSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceWhileWithEnhancedForSafely())
            .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
    }

    @Test
    void replacesWhileLoopWithLeadingElementDeclaration() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    int totalLength(List<String> items) {
                        int total = 0;
                        Iterator<String> it = items.iterator();
                        while (it.hasNext()) {
                            String item = it.next();
                            total += item.length();
                        }
                        return total;
                    }
                }
                """,
            """
                import java.util.List;

                class Example {
                    int totalLength(List<String> items) {
                        int total = 0;
                        for (String item : items) {
                            total += item.length();
                        }
                        return total;
                    }
                }
                """));
    }

    @Test
    void replacesWhileLoopWithInlineChainedNextCall() {
        rewriteRun(java(
            """
                import com.fasterxml.jackson.databind.JsonNode;

                class Example {
                    String firstText(JsonNode input) {
                        var fields = input.properties().iterator();
                        while (fields.hasNext()) {
                            JsonNode value = fields.next().getValue();
                            if (value.isTextual()) {
                                return value.asText();
                            }
                        }
                        return "";
                    }
                }
                """,
            """
                import com.fasterxml.jackson.databind.JsonNode;

                class Example {
                    String firstText(JsonNode input) {
                        for (var field : input.properties()) {
                            JsonNode value = field.getValue();
                            if (value.isTextual()) {
                                return value.asText();
                            }
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void replacesReassignedIteratorWhileLoops() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    int sumTwice(List<Integer> nums) {
                        int sum = 0;
                        Iterator<Integer> it = nums.iterator();
                        while (it.hasNext()) {
                            Integer n = it.next();
                            sum += n;
                        }
                        it = nums.iterator();
                        while (it.hasNext()) {
                            Integer n = it.next();
                            sum += n;
                        }
                        return sum;
                    }
                }
                """,
            """
                import java.util.List;

                class Example {
                    int sumTwice(List<Integer> nums) {
                        int sum = 0;
                        for (Integer n : nums) {
                            sum += n;
                        }
                        for (Integer n : nums) {
                            sum += n;
                        }
                        return sum;
                    }
                }
                """));
    }

    @Test
    void replacesIteratorForLoopWithEnhancedFor() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    int countNonEmpty(List<String> lines) {
                        int count = 0;
                        for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                            String line = it.next();
                            if (!line.isEmpty()) count++;
                        }
                        return count;
                    }
                }
                """,
            """
                import java.util.List;

                class Example {
                    int countNonEmpty(List<String> lines) {
                        int count = 0;
                        for (String line : lines) {
                            if (!line.isEmpty()) count++;
                        }
                        return count;
                    }
                }
                """));
    }

    @Test
    void avoidsNameCollisionForSyntheticLoopVariable() {
        rewriteRun(java(
            """
                import java.util.Map;

                class Example {
                    String find(Map<String, String> map, String field) {
                        var fields = map.entrySet().iterator();
                        while (fields.hasNext()) {
                            String v = fields.next().getValue();
                            if (v.equals(field)) return v;
                        }
                        return "";
                    }
                }
                """,
            """
                import java.util.Map;

                class Example {
                    String find(Map<String, String> map, String field) {
                        for (var entry : map.entrySet()) {
                            String v = entry.getValue();
                            if (v.equals(field)) return v;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void preservesLoopWhenIteratorRemoveIsCalled() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    void prune(List<String> items) {
                        Iterator<String> it = items.iterator();
                        while (it.hasNext()) {
                            String item = it.next();
                            if (item.isEmpty()) it.remove();
                        }
                    }
                }
                """));
    }

    @Test
    void preservesLoopOverNonIterableIteratorSource() {
        rewriteRun(java(
            """
                import java.util.Iterator;

                class Example {
                    void drain(Iterator<String> stream) {
                        while (stream.hasNext()) {
                            String item = stream.next();
                            if (item.isEmpty()) break;
                        }
                    }
                }
                """));
    }

    @Test
    void preservesLoopWhenNextIsCalledConditionally() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    void tricky(List<String> items, boolean flag) {
                        Iterator<String> it = items.iterator();
                        while (it.hasNext()) {
                            if (flag) {
                                String item = it.next();
                                if (item.isEmpty()) break;
                            } else {
                                break;
                            }
                        }
                    }
                }
                """));
    }

    @Test
    void preservesLoopWhenIteratorIsUsedAfterLoop() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    boolean stoppedEarly(List<String> items) {
                        Iterator<String> it = items.iterator();
                        while (it.hasNext()) {
                            String item = it.next();
                            if (item.isEmpty()) break;
                        }
                        return it.hasNext();
                    }
                }
                """));
    }

    @Test
    void preservesLoopWhenNextResultIsDiscardedAsStandaloneStatement() {
        rewriteRun(java(
            """
                import java.util.Iterator;
                import java.util.List;

                class Example {
                    void drain(List<String> items) {
                        Iterator<String> it = items.iterator();
                        while (it.hasNext()) {
                            it.next();
                        }
                    }
                }
                """));
    }
}
