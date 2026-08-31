package com.claudecode.recipes;

import com.google.errorprone.refaster.annotation.AfterTemplate;
import com.google.errorprone.refaster.annotation.BeforeTemplate;
import java.util.Collection;
import org.openrewrite.java.template.RecipeDescriptor;

/**
 * Refaster template that replaces a stream membership test with the equivalent collection API.
 *
 * <p>{@link Collection#containsAll(Collection)} has the same empty-input and short-circuit result
 * as {@code items.stream.allMatch(container::contains)}. Any surrounding conditions, such as an
 * explicit {@code !items.isEmpty} guard, remain untouched.
 */
public final class UseContainsAll {

    private UseContainsAll() {
    }

    @RecipeDescriptor(
        name = "Use Collection.containsAll",
        description = "Replace `items.stream().allMatch(container::contains)` with `container.containsAll(items)`.")
    public static final class StreamAllMatchContains {
        @BeforeTemplate
        boolean before(Collection<?> items, Collection<?> container) {
            return items.stream().allMatch(container::contains);
        }

        @AfterTemplate
        boolean after(Collection<?> items, Collection<?> container) {
            return container.containsAll(items);
        }
    }
}
