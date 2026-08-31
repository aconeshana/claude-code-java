package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openrewrite.java.template.RecipeDescriptor;

/** Refaster templates for diamond operators inside casts that the general recipe cannot infer. */
public final class UseDiamondOperatorInTypeCasts {

    private UseDiamondOperatorInTypeCasts() {
    }

    @RecipeDescriptor(
        name = "Use diamond operator for a cast LinkedHashMap copy",
        description = "Replace explicit String/Object constructor arguments with the diamond operator.")
    public static final class LinkedHashMapStringObject {
        @BeforeTemplate
        Map<String, Object> before(Map<String, Object> source) {
            return (Map<String, Object>) new LinkedHashMap<String, Object>(source);
        }

        @AfterTemplate
        Map<String, Object> after(Map<String, Object> source) {
            return (Map<String, Object>) new LinkedHashMap<>(source);
        }
    }
}
