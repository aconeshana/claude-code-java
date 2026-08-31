package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import org.apache.commons.lang3.StringUtils;
import org.openrewrite.java.template.RecipeDescriptor;

/**
 * Refaster templates that fold an explicit {@code null} check around {@link String#isEmpty} into
 * null-safe Apache Commons Lang3 {@link StringUtils#isEmpty(CharSequence)} / {@link
 * StringUtils#isNotEmpty(CharSequence)}.
 */
public final class UseStringUtilsEmpty {

    private UseStringUtilsEmpty() {
    }

    @RecipeDescriptor(
        name = "Absorb null check into StringUtils.isEmpty",
        description = "Replace `s == null || s.isEmpty()` with `StringUtils.isEmpty(s)`.")
    public static final class NullOrEmpty {
        @BeforeTemplate
        boolean before(String s) {
            return s == null || s.isEmpty();
        }

        @AfterTemplate
        boolean after(String s) {
            return StringUtils.isEmpty(s);
        }
    }

    @RecipeDescriptor(
        name = "Absorb null check into StringUtils.isNotEmpty",
        description = "Replace `s != null && !s.isEmpty()` with `StringUtils.isNotEmpty(s)`.")
    public static final class NotNullAndNotEmpty {
        @BeforeTemplate
        boolean before(String s) {
            return s != null && !s.isEmpty();
        }

        @AfterTemplate
        boolean after(String s) {
            return StringUtils.isNotEmpty(s);
        }
    }
}
