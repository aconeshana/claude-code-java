package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseOptionalIfPresentTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseOptionalIfPresent());
    }

    @Test
    void convertsWhenBodyCapturesEffectivelyFinalLocals() {
        rewriteRun(java(
            """
                import java.util.ArrayList;
                import java.util.List;

                class Example {
                    List<String> select(List<String> sources, List<String> targets) {
                        List<String> matches = new ArrayList<>();
                        for (String source : sources) {
                            String matched = targets.stream()
                                .filter(target -> target.startsWith(source))
                                .findFirst().orElse(null);
                            if (matched != null) matches.add(source + matched);
                        }
                        return matches;
                    }
                }
                """,
            """
                import java.util.ArrayList;
                import java.util.List;

                class Example {
                    List<String> select(List<String> sources, List<String> targets) {
                        List<String> matches = new ArrayList<>();
                        for (String source : sources) {
                            targets.stream()
                                    .filter(target -> target.startsWith(source))
                                    .findFirst().ifPresent(matchedValue -> matches.add(source + matchedValue));
                        }
                        return matches;
                    }
                }
                """));
    }

    @Test
    void preservesCaptureThatIsReassignedElsewhere() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    void consume(String value) {}

                    void run(Optional<String> candidate) {
                        String prefix = "a";
                        String matched = candidate.orElse(null);
                        if (matched != null) consume(prefix + matched);
                        prefix = "b";
                    }
                }
                """));
    }

    @Test
    void preservesControlFlowInsideTheNullCheck() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    String read(Optional<String> candidate) {
                        String matched = candidate.orElse(null);
                        if (matched != null) return matched;
                        return "";
                    }
                }
                """));
    }

    @Test
    void preservesTemporaryReferencedAfterTheNullCheck() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    void consume(String value) {}

                    void run(Optional<String> candidate) {
                        String matched = candidate.orElse(null);
                        if (matched != null) consume(matched);
                        consume(matched);
                    }
                }
                """));
    }
}
