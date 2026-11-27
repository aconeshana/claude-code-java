package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseRecordPatternsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseRecordPatterns());
    }

    @Test
    void deconstructsAnySingleComponentRecord() {
        rewriteRun(java(
            """
                class Example {
                    record Success(String absolutePath) {}

                    String read(Object result) {
                        if (result instanceof Success success) {
                            return success.absolutePath();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Success(String absolutePath) {}

                    String read(Object result) {
                        if (result instanceof Success(var absolutePath)) {
                            return absolutePath;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void deconstructsInAndChainWithRepeatedAccessorsAndStatementBody() {
        rewriteRun(java(
            """
                class Example {
                    record TextBlock(String text) {}

                    void render(Object block, StringBuilder sb) {
                        if (block instanceof TextBlock tb && tb.text() != null && !tb.text().isEmpty()) {
                            sb.append("> ").append(tb.text()).append("\\n");
                        }
                    }
                }
                """,
            """
                class Example {
                    record TextBlock(String text) {}

                    void render(Object block, StringBuilder sb) {
                        if (block instanceof TextBlock(var text) && text != null && !text.isEmpty()) {
                            sb.append("> ").append(text).append("\\n");
                        }
                    }
                }
                """));
    }

    @Test
    void supportsMultipleComponentsAndUnusedPatterns() {
        rewriteRun(java(
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair pair) {
                            return pair.right();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair(_, var right)) {
                            return right;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void preservesRecordComponentOrderWhenSeveralAreUsed() {
        rewriteRun(java(
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair pair) {
                            return pair.left() + pair.right();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Pair(String left, String right) {}

                    String read(Object value) {
                        if (value instanceof Pair(var left, var right)) {
                            return left + right;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void skipsWhenOuterVariableNameCollides() {
        rewriteRun(java(
            """
                class Example {
                    record TextBlock(String text) {}

                    void append(Object inner) {
                        StringBuilder text = new StringBuilder();
                        if (inner instanceof TextBlock tb && tb.text() != null) {
                            text.append(tb.text());
                        }
                    }
                }
                """));
    }

    @Test
    void qualifiesUnqualifiedInstanceFieldWhenShadowed() {
        rewriteRun(java(
            """
                class Example {
                    record TextBlock(String text) {}

                    private String text = "outer";

                    String merge(Object block) {
                        if (block instanceof TextBlock tb && tb.text() != null) {
                            return text + tb.text();
                        }
                        return text;
                    }
                }
                """,
            """
                class Example {
                    record TextBlock(String text) {}

                    private String text = "outer";

                    String merge(Object block) {
                        if (block instanceof TextBlock(var text) && text != null) {
                            return this.text + text;
                        }
                        return text;
                    }
                }
                """));
    }

    @Test
    void qualifiesUnqualifiedInstanceFieldAssignmentWhenShadowed() {
        rewriteRun(java(
            """
                class BlockAccum {
                    record ThinkingBlock(String thinking, String signature) {}

                    private final StringBuilder text = new StringBuilder();
                    private String signature;

                    BlockAccum(Object initial) {
                        if (initial instanceof ThinkingBlock t) {
                            if (t.thinking() != null) text.append(t.thinking());
                            signature = t.signature();
                        }
                    }
                }
                """,
            """
                class BlockAccum {
                    record ThinkingBlock(String thinking, String signature) {}

                    private final StringBuilder text = new StringBuilder();
                    private String signature;

                    BlockAccum(Object initial) {
                        if (initial instanceof ThinkingBlock(var thinking, var signature)) {
                            if (thinking != null) text.append(thinking);
                            this.signature = signature;
                        }
                    }
                }
                """));
    }

    @Test
    void qualifiesStaticFieldOfSameClassWhenShadowed() {
        rewriteRun(java(
            """
                class Example {
                    record TextBlock(String text) {}

                    private static final String text = "prefix:";

                    static String format(Object block) {
                        if (block instanceof TextBlock tb && tb.text() != null) {
                            return text + tb.text();
                        }
                        return text;
                    }
                }
                """,
            """
                class Example {
                    record TextBlock(String text) {}

                    private static final String text = "prefix:";

                    static String format(Object block) {
                        if (block instanceof TextBlock(var text) && text != null) {
                            return Example.text + text;
                        }
                        return text;
                    }
                }
                """));
    }

    @Test
    void rewritesWhenPatternVariableSharesComponentName() {
        rewriteRun(java(
            """
                class Example {
                    record ThinkingBlock(String thinking, String signature) {}

                    String format(Object block) {
                        if (block instanceof ThinkingBlock thinking) {
                            return (thinking.thinking() == null ? "" : thinking.thinking()) + thinking.signature();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record ThinkingBlock(String thinking, String signature) {}

                    String format(Object block) {
                        if (block instanceof ThinkingBlock(var thinking, var signature)) {
                            return (thinking == null ? "" : thinking) + signature;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void rewritesWhenSiblingElseIfBindsSameComponentName() {
        rewriteRun(java(
            """
                class Example {
                    record Block(String reason, String hook) {}
                    record ConditionNotMet(String condition, String reason) {}

                    String format(Object r) {
                        if (r instanceof Block block) {
                            return block.reason();
                        } else if (r instanceof ConditionNotMet(String condition, String reason)) {
                            return condition + reason;
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Block(String reason, String hook) {}
                    record ConditionNotMet(String condition, String reason) {}

                    String format(Object r) {
                        if (r instanceof Block(var reason, _)) {
                            return reason;
                        } else if (r instanceof ConditionNotMet(String condition, String reason)) {
                            return condition + reason;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void rewritesAfterPrecedingPositiveIfDeconstruction() {
        rewriteRun(java(
            """
                class Example {
                    record Invalid(String message) {}
                    record Deny(String message, String reason) {}

                    String check(Object first, Object second) {
                        if (first instanceof Invalid(String message)) {
                            return message;
                        }
                        if (second instanceof Deny deny) {
                            return deny.message();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record Invalid(String message) {}
                    record Deny(String message, String reason) {}

                    String check(Object first, Object second) {
                        if (first instanceof Invalid(String message)) {
                            return message;
                        }
                        if (second instanceof Deny(var message, _)) {
                            return message;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void skipsWhenFlowScopedNegatedGuardPatternVariableCollides() {
        rewriteRun(java(
            """
                class Example {
                    record TextBlock(String text) {}
                    record Other(String text) {}

                    String check(Object a, Object b) {
                        if (!(a instanceof Other(String text))) {
                            return "";
                        }
                        if (b instanceof TextBlock tb) {
                            return text + tb.text();
                        }
                        return text;
                    }
                }
                """));
    }

    @Test
    void skipsWhenOuterClassFieldAccessedFromInnerOrAnonymousClass() {
        rewriteRun(java(
            """
                class Outer {
                    record ThinkingBlock(String thinking, String signature) {}

                    private String signature;

                    Runnable task(Object initial) {
                        return new Runnable() {
                            @Override
                            public void run() {
                                if (initial instanceof ThinkingBlock t) {
                                    signature = t.signature() + t.thinking();
                                }
                            }
                        };
                    }
                }
                """));
    }

    @Test
    void skipsInsideRecordCompactConstructorWhenComponentNameCollides() {
        rewriteRun(java(
            """
                record Holder(String signature, Object extra) {
                    record ThinkingBlock(String thinking, String signature) {}

                    Holder {
                        if (extra instanceof ThinkingBlock t) {
                            signature = t.signature() + t.thinking();
                        }
                    }
                }
                """));
    }

    @Test
    void skipsLargeRecords() {
        rewriteRun(java(
            """
                class Example {
                    record Large(String a, String b, String c, String d) {}

                    String read(Object value) {
                        if (value instanceof Large large) {
                            return large.a();
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void preservesElseBranches() {
        rewriteRun(java(
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped.number();
                        } else {
                            return -1;
                        }
                    }
                }
                """,
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value(var number)) {
                            return number;
                        } else {
                            return -1;
                        }
                    }
                }
                """));
    }

    @Test
    void doesNotChangeWholeRecordUsage() {
        rewriteRun(java(
            """
                class Example {
                    record Value(int number) {}

                    Value read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped;
                        }
                        return null;
                    }
                }
                """));
    }

    @Test
    void rewritesRepeatedAccessorCalls() {
        rewriteRun(java(
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped.number() + wrapped.number();
                        }
                        return 0;
                    }
                }
                """,
            """
                class Example {
                    record Value(int number) {}

                    int read(Object value) {
                        if (value instanceof Value(var number)) {
                            return number + number;
                        }
                        return 0;
                    }
                }
                """));
    }

    @Test
    void doesNotChangeNonRecordPatterns() {
        rewriteRun(java(
            """
                class Example {
                    static final class Value {
                        int number() { return 1; }
                    }

                    int read(Object value) {
                        if (value instanceof Value wrapped) {
                            return wrapped.number();
                        }
                        return 0;
                    }
                }
                """));
    }

    @Test
    void skipsRecordsWithMoreThanOneUnusedComponent() {
        rewriteRun(java(
            """
                class Example {
                    record Triple(String a, String b, String c) {}

                    String read(Object value) {
                        if (value instanceof Triple triple) {
                            return triple.c();
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void handlesElseIfAfterSiblingRecordPattern() {
        rewriteRun(java(
            """
                class Example {
                    record First(String a) {}
                    record Second(String b, String c) {}

                    String read(Object value) {
                        if (value instanceof First(String a)) {
                            return a;
                        } else if (value instanceof Second s) {
                            return s.b();
                        }
                        return "";
                    }
                }
                """,
            """
                class Example {
                    record First(String a) {}
                    record Second(String b, String c) {}

                    String read(Object value) {
                        if (value instanceof First(String a)) {
                            return a;
                        } else if (value instanceof Second(var b, _)) {
                            return b;
                        }
                        return "";
                    }
                }
                """));
    }

    @Test
    void skipsSymmetricSameRecordInstanceOfInAndChain() {
        rewriteRun(java(
            """
                class Example {
                    record Url(String url, String ref) {}

                    boolean same(Object a, Object b) {
                        if (a instanceof Url ua && b instanceof Url ub) {
                            return ua.url().equals(ub.url());
                        }
                        return false;
                    }
                }
                """));
    }

    @Test
    void doesNotChangeOrCondition() {
        rewriteRun(java(
            """
                class Example {
                    record TextBlock(String text) {}

                    boolean check(Object block) {
                        if (!(block instanceof TextBlock tb) || tb.text() == null) {
                            return false;
                        }
                        return true;
                    }
                }
                """));
    }
}
