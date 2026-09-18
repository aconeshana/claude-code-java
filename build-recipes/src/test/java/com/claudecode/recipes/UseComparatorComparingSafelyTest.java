package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseComparatorComparingSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseComparatorComparingSafely());
    }

    @Test
    void replacesAscendingLongCompareWithComparingLong() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    record Node(long seq) {}

                    void sort(List<Node> nodes) {
                        nodes.sort((a, b) -> Long.compare(a.seq(), b.seq()));
                    }
                }
                """,
            """
                import java.util.Comparator;
                import java.util.List;

                class Example {
                    record Node(long seq) {}

                    void sort(List<Node> nodes) {
                        nodes.sort(Comparator.comparingLong((Example.Node a) -> a.seq()));
                    }
                }
                """));
    }

    @Test
    void replacesAscendingIntegerCompareInStream() {
        rewriteRun(java(
            """
                import java.util.stream.Stream;

                class Example {
                    record Scored(int score) {}

                    Stream<Scored> sorted(Stream<Scored> stream) {
                        return stream.sorted((a, b) -> Integer.compare(a.score(), b.score()));
                    }
                }
                """,
            """
                import java.util.Comparator;
                import java.util.stream.Stream;

                class Example {
                    record Scored(int score) {}

                    Stream<Scored> sorted(Stream<Scored> stream) {
                        return stream.sorted(Comparator.comparingInt((Example.Scored a) -> a.score()));
                    }
                }
                """));
    }

    @Test
    void replacesDescendingDoubleCompareWithReversed() {
        rewriteRun(java(
            """
                import java.util.stream.Stream;

                class Example {
                    record Weighted(double value) {}

                    Stream<Weighted> sorted(Stream<Weighted> stream) {
                        return stream.sorted((a, b) -> Double.compare(b.value(), a.value()));
                    }
                }
                """,
            """
                import java.util.Comparator;
                import java.util.stream.Stream;

                class Example {
                    record Weighted(double value) {}

                    Stream<Weighted> sorted(Stream<Weighted> stream) {
                        return stream.sorted(Comparator.comparingDouble((Example.Weighted a) -> a.value()).reversed());
                    }
                }
                """));
    }

    @Test
    void replacesKeyExpressionThatCapturesAnOuterVariable() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    record Node(long seq) {}

                    long weight(Node node, long bias) {
                        return node.seq() + bias;
                    }

                    void sort(List<Node> nodes, long bias) {
                        nodes.sort((a, b) -> Long.compare(weight(b, bias), weight(a, bias)));
                    }
                }
                """,
            """
                import java.util.Comparator;
                import java.util.List;

                class Example {
                    record Node(long seq) {}

                    long weight(Node node, long bias) {
                        return node.seq() + bias;
                    }

                    void sort(List<Node> nodes, long bias) {
                        nodes.sort(Comparator.comparingLong((Example.Node a) -> weight(a, bias)).reversed());
                    }
                }
                """));
    }

    @Test
    void leavesLambdasThatMixBothParametersInOneKeyUntouched() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    record Node(long seq) {}

                    void sort(List<Node> nodes) {
                        nodes.sort((a, b) -> Long.compare(a.seq() - b.seq(), 0));
                    }
                }
                """));
    }

    @Test
    void leavesNonComparatorFunctionalInterfacesUntouched() {
        rewriteRun(java(
            """
                import java.util.function.ToIntBiFunction;

                class Example {
                    record Node(long seq) {}

                    ToIntBiFunction<Node, Node> comparator() {
                        return (a, b) -> Long.compare(a.seq(), b.seq());
                    }
                }
                """));
    }

    @Test
    void leavesDifferentKeyExpressionsOnEachSideUntouched() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    record Node(long seq, long priority) {}

                    void sort(List<Node> nodes) {
                        nodes.sort((a, b) -> Long.compare(a.seq(), b.priority()));
                    }
                }
                """));
    }

    @Test
    void preservesGenericTypeArgumentsForAParameterizedParameterType() {
        rewriteRun(java(
            """
                import java.util.Map;
                import java.util.stream.Stream;

                class Example {
                    Stream<Map.Entry<String, Long>> sorted(Stream<Map.Entry<String, Long>> stream) {
                        return stream.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()));
                    }
                }
                """,
            """
                import java.util.Comparator;
                import java.util.Map;
                import java.util.stream.Stream;

                class Example {
                    Stream<Map.Entry<String, Long>> sorted(Stream<Map.Entry<String, Long>> stream) {
                        return stream.sorted(Comparator.comparingLong((java.util.Map.Entry<java.lang.String, java.lang.Long> a) -> a.getValue()).reversed());
                    }
                }
                """));
    }

    @Test
    void leavesALocalClassParameterTypeUntouched() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    void sort(List<Object> nodes) {
                        record Scored(int score) {}
                        List<Scored> scored = nodes.stream().map(o -> new Scored(0)).toList();
                        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
                    }
                }
                """));
    }
}
