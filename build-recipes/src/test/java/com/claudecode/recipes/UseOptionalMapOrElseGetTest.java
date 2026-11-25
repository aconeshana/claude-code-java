package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class UseOptionalMapOrElseGetTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UseOptionalMapOrElseGet());
    }

    @Test
    void foldsDirectOptionalGetIntoOrElseGet() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    String fallback() { return "default"; }

                    String resolve(Optional<String> local) {
                        if (local.isPresent()) return local.get();
                        return fallback();
                    }
                }
                """,
            """
                import java.util.Optional;

                class Example {
                    String fallback() { return "default"; }

                    String resolve(Optional<String> local) {
                        return local.orElseGet(() -> fallback());
                    }
                }
                """));
    }

    @Test
    void foldsPrimitiveMappedReturnIntoMapOrElseGet() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    boolean fallback() { return false; }

                    boolean isReady(Optional<String> remote) {
                        if (remote.isPresent()) {
                            return remote.get().isEmpty();
                        }
                        return fallback();
                    }
                }
                """,
            """
                import java.util.Optional;

                class Example {
                    boolean fallback() { return false; }

                    boolean isReady(Optional<String> remote) {
                        return remote.map(value -> value.isEmpty()).orElseGet(() -> fallback());
                    }
                }
                """));
    }

    @Test
    void preservesPotentiallyNullReferenceMapping() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    String extract(String raw) { return null; }

                    String resolve(Optional<String> remote) {
                        if (remote.isPresent()) {
                            return extract(remote.get());
                        }
                        return "fallback";
                    }
                }
                """));
    }

    @Test
    void preservesBranchThrowingCheckedException() {
        rewriteRun(java(
            """
                import java.io.IOException;
                import java.util.Optional;

                class Example {
                    String readDisk() throws IOException { return "disk"; }

                    String resolve(Optional<String> cached) throws IOException {
                        if (cached.isPresent()) return cached.get();
                        return readDisk();
                    }
                }
                """));
    }

    @Test
    void preservesBranchCapturingReassignedLocal() {
        rewriteRun(java(
            """
                import java.util.Optional;

                class Example {
                    String resolve(Optional<String> cached) {
                        String fallback = "a";
                        fallback = "b";
                        if (cached.isPresent()) return cached.get();
                        return fallback.trim();
                    }
                }
                """));
    }
}
