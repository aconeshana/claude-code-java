package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ReplaceWhileWithDoWhileSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceWhileWithDoWhileSafely());
    }

    @Test
    void replacesSingleStatementWhileWithDoWhile() {
        rewriteRun(java(
            """
                class Example {
                    int skip(String s, int i) {
                        i++;
                        while (i < s.length() && s.charAt(i) == ' ') i++;
                        return i;
                    }
                }
                """,
            """
                class Example {
                    int skip(String s, int i) {
                        do {
                            i++;
                        } while (i < s.length() && s.charAt(i) == ' ');
                        return i;
                    }
                }
                """));
    }

    @Test
    void replacesBlockWhileWithDoWhile() {
        rewriteRun(java(
            """
                class Example {
                    int skip(String s, int i) {
                        i++;
                        while (i < s.length() && s.charAt(i) == ' ') {
                            i++;
                        }
                        return i;
                    }
                }
                """,
            """
                class Example {
                    int skip(String s, int i) {
                        do {
                            i++;
                        } while (i < s.length() && s.charAt(i) == ' ');
                        return i;
                    }
                }
                """));
    }

    @Test
    void replacesMultiStatementBodyWithDoWhile() {
        rewriteRun(java(
            """
                class Example {
                    void step(int x) {}

                    int advance(int i, int limit) {
                        step(i);
                        i++;
                        while (i < limit) {
                            step(i);
                            i++;
                        }
                        return i;
                    }
                }
                """,
            """
                class Example {
                    void step(int x) {}

                    int advance(int i, int limit) {
                        do {
                            step(i);
                            i++;
                        } while (i < limit);
                        return i;
                    }
                }
                """));
    }

    @Test
    void preservesLeadingCommentBeforeMergedLoop() {
        rewriteRun(java(
            """
                class Example {
                    int skip(String s, int i) {
                        // Consume leading slash and following spaces.
                        i++;
                        while (i < s.length() && s.charAt(i) == ' ') i++;
                        return i;
                    }
                }
                """,
            """
                class Example {
                    int skip(String s, int i) {
                        // Consume leading slash and following spaces.
                        do {
                            i++;
                        } while (i < s.length() && s.charAt(i) == ' ');
                        return i;
                    }
                }
                """));
    }

    @Test
    void preservesWhenVariableDeclarationWouldNarrowScope() {
        rewriteRun(java(
            """
                class Example {
                    int next() { return 0; }

                    int read() {
                        int x = next();
                        while (x > 0) {
                            int y = next();
                        }
                        return x;
                    }
                }
                """));
    }

    @Test
    void preservesWhenPrecedingStatementDiffers() {
        rewriteRun(java(
            """
                class Example {
                    int skip(String s, int i) {
                        i += 2;
                        while (i < s.length() && s.charAt(i) == ' ') i++;
                        return i;
                    }
                }
                """));
    }

    @Test
    void preservesWhenLoopBodyContainsBreakOrContinue() {
        rewriteRun(java(
            """
                class Example {
                    boolean check(int i) { return i > 0; }

                    void run(int i) {
                        for (int k = 0; k < 3; k++) {
                            if (check(i)) break;
                            while (i < 10) {
                                if (check(i)) break;
                            }
                        }
                    }
                }
                """));
    }
}
