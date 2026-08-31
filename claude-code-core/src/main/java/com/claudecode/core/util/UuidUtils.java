package com.claudecode.core.util;

import java.util.regex.Pattern;

/**
 * UUID validation compatible with the CLI's permissive UUID shape check.
 *
 * <ul>
 *   <li>{@code uuidRegex} and
 *       {@code validateUuid}; validation checks only the exact 8-4-4-4-12 hex
 *       shape and deliberately does not require a specific UUID version.</li>
 * </ul>
 */
public final class UuidUtils {
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE);

    private UuidUtils() {}

    public static String validate(Object value) {
        return value instanceof String text && UUID_PATTERN.matcher(text).matches() ? text : null;
    }

    public static boolean isValid(String value) {
        return validate(value) != null;
    }
}
