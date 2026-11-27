package com.claudecode.core.util;

import java.util.List;

/** Null-safe immutable collection copy helpers. */
public final class CollectionUtils {

    private CollectionUtils() {}

    /** Returns an unmodifiable copy of {@code values}, or {@link List#of()} when {@code null}. */
    public static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
