package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import java.util.List;
import java.util.stream.Stream;
import org.openrewrite.java.template.RecipeDescriptor;

/** Safe stream-factory cleanup for arrays produced by {@link String#split(String)}. */
public final class UseStreamOf {

    private UseStreamOf() {
    }

    @RecipeDescriptor(
        name = "Use Stream.of for String.split results",
        description = "Replace `List.of(text.split(regex)).stream()` with `Stream.of(text.split(regex))`.")
    public static final class StringSplit {
        @BeforeTemplate
        Stream<String> before(String text, String regex) {
            return List.of(text.split(regex)).stream();
        }

        @AfterTemplate
        Stream<String> after(String text, String regex) {
            return Stream.of(text.split(regex));
        }
    }
}
