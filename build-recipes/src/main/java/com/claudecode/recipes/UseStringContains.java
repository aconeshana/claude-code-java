package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import org.apache.commons.lang3.Strings;
import org.openrewrite.java.template.RecipeDescriptor;

/** Refaster templates that replace substring {@code indexOf} checks with {@link Strings.CS}. */
public final class UseStringContains {

    private UseStringContains() {
    }

    @RecipeDescriptor(
        name = "Use String.contains for a missing substring",
        description = "Replace `text.indexOf(search) < 0` with `!Strings.CS.contains(text, search)`.")
    public static final class IndexOfLessThanZero {
        @BeforeTemplate
        boolean before(String text, String search) {
            return text.indexOf(search) < 0;
        }

        @AfterTemplate
        boolean after(String text, String search) {
            return !Strings.CS.contains(text, search);
        }
    }

    @RecipeDescriptor(
        name = "Use String.contains for a present substring",
        description = "Replace `text.indexOf(search) >= 0` with `Strings.CS.contains(text, search)`.")
    public static final class IndexOfGreaterThanOrEqualToZero {
        @BeforeTemplate
        boolean before(String text, String search) {
            return text.indexOf(search) >= 0;
        }

        @AfterTemplate
        boolean after(String text, String search) {
            return Strings.CS.contains(text, search);
        }
    }
}
