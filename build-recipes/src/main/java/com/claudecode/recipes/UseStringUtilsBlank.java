package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import org.apache.commons.lang3.StringUtils;
import org.openrewrite.java.template.RecipeDescriptor;


public final class UseStringUtilsBlank {

    private UseStringUtilsBlank() {
    }

    @RecipeDescriptor(
        name = "Absorb null check into StringUtils.isBlank",
        description = "Replace `s == null || s.isBlank()` with `StringUtils.isBlank(s)`.")
    public static final class NullOrBlank {
        @BeforeTemplate
        boolean before(String s) {
            return s == null || s.isBlank();
        }

        @AfterTemplate
        boolean after(String s) {
            return StringUtils.isBlank(s);
        }
    }

    @RecipeDescriptor(
        name = "Absorb null check into StringUtils.isNotBlank",
        description = "Replace `s != null && !s.isBlank()` with `StringUtils.isNotBlank(s)`.")
    public static final class NotNullAndNotBlank {
        @BeforeTemplate
        boolean before(String s) {
            return s != null && !s.isBlank();
        }

        @AfterTemplate
        boolean after(String s) {
            return StringUtils.isNotBlank(s);
        }
    }

    @RecipeDescriptor(
        name = "Use StringUtils.isBlank",
        description = "Replace `String#isBlank()` with null-safe `StringUtils.isBlank(String)`.")
    public static final class IsBlank {
        @BeforeTemplate
        boolean before(String s) {
            return s.isBlank();
        }

        @AfterTemplate
        boolean after(String s) {
            return StringUtils.isBlank(s);
        }
    }

    @RecipeDescriptor(
        name = "Use StringUtils.isNotBlank",
        description = "Replace `!String#isBlank()` with null-safe `StringUtils.isNotBlank(String)`.")
    public static final class IsNotBlank {
        @BeforeTemplate
        boolean before(String s) {
            return !s.isBlank();
        }

        @AfterTemplate
        boolean after(String s) {
            return StringUtils.isNotBlank(s);
        }
    }
}
