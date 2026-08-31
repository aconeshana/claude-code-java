package com.claudecode.recipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ReplaceAnonymousClassWithLambdaSafelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ReplaceAnonymousClassWithLambdaSafely());
    }

    @Test
    void replacesSingleAbstractMethodImplementations() {
        rewriteRun(java(
            """
                import java.io.IOException;
                import java.nio.file.Path;
                import java.util.List;

                class Example {
                    interface Resources {
                        List<Path> files(Path directory) throws IOException;
                    }

                    Resources resources = new Resources() {
                        @Override public List<Path> files(Path directory) { return List.of(); }
                    };
                }
                """,
            """
                import java.io.IOException;
                import java.nio.file.Path;
                import java.util.List;

                class Example {
                    interface Resources {
                        List<Path> files(Path directory) throws IOException;
                    }

                    Resources resources = _ -> List.of();
                }
                """));
    }

    @Test
    void usesAStaticMethodReferenceForAZeroArgumentFactory() {
        rewriteRun(java(
            """
                import java.util.List;

                class Example {
                    interface Tasks { List<String> list(); }

                    Tasks tasks = new Tasks() {
                        @Override public List<String> list() { return List.of(); }
                    };
                }
                """,
            """
                import java.util.List;

                class Example {
                    interface Tasks { List<String> list(); }

                    Tasks tasks = List::of;
                }
                """));
    }

    @Test
    void replacesVoidAndBlockBodies() {
        rewriteRun(java(
            """
                class Example {
                    interface Sink { void accept(String left, String right); }

                    Sink empty = new Sink() {
                        @Override public void accept(String left, String right) { }
                    };
                    Sink block = new Sink() {
                        @Override public void accept(String left, String right) {
                            System.out.println(left);
                            System.out.println(right);
                        }
                    };
                }
                """,
            """
                class Example {
                    interface Sink { void accept(String left, String right); }

                    Sink empty = (_, _) -> {
                    };
                    Sink block = (left, right) -> {
                        System.out.println(left);
                        System.out.println(right);
                    };
                }
                """));
    }

    @Test
    void preservesNonFunctionalAnonymousClasses() {
        rewriteRun(java(
            """
                class Example {
                    interface Plans {
                        String read();
                        void copy();
                    }

                    Plans plans = new Plans() {
                        @Override public String read() { return ""; }
                        @Override public void copy() { }
                    };
                }
                """));
    }

    @Test
    void preservesAnonymousStateAndIdentity() {
        rewriteRun(java(
            """
                class Example {
                    interface Supplier { Object get(); }

                    Supplier stateful = new Supplier() {
                        private final Object value = new Object();
                        @Override public Object get() { return value; }
                    };
                    Supplier identity = new Supplier() {
                        @Override public Object get() { return this; }
                    };
                }
                """));
    }

    @Test
    void preservesAnnotatedMethodsAndVarTargets() {
        rewriteRun(java(
            """
                class Example {
                    interface Supplier { String get(); }

                    @interface Special {}

                    Supplier annotated = new Supplier() {
                        @Override @Special public String get() { return "value"; }
                    };
                    Object inferred() {
                        var supplier = new Supplier() {
                            @Override public String get() { return "value"; }
                        };
                        return supplier;
                    }
                }
                """));
    }

    @Test
    void preservesWidenedObjectTargets() {
        rewriteRun(java(
            """
                class Example {
                    Object task = new Runnable() {
                        @Override public void run() { }
                    };
                }
                """));
    }

    @Test
    void qualifiesNestedTypesInheritedFromTheAnonymousInterface() {
        rewriteRun(java(
            """
                class Example {
                    interface Sandbox {
                        record Status(boolean available) {}
                        Status status(String config);
                    }

                    Sandbox sandbox = new Sandbox() {
                        @Override public Status status(String config) {
                            return new Status(false);
                        }
                    };
                }
                """,
            """
                class Example {
                    interface Sandbox {
                        record Status(boolean available) {}
                        Status status(String config);
                    }

                    Sandbox sandbox = _ -> new Sandbox.Status(false);
                }
                """));
    }
}
