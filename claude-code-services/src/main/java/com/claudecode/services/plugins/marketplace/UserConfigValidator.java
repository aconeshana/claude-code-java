package com.claudecode.services.plugins.marketplace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared validation for plugin and MCPB user-configuration schemas.
 */
public final class UserConfigValidator {
    private UserConfigValidator() {}

    public static List<String> validate(Map<String, Object> values,
                                        Map<String, UserConfigOption> schema) {
        List<String> errors = new ArrayList<>();
        schema.forEach((key, option) -> validateOne(key, option, values.get(key), errors));
        return List.copyOf(errors);
    }

    public static LinkedHashMap<String, UserConfigOption> unconfigured(
            Map<String, Object> values, Map<String, UserConfigOption> schema) {
        LinkedHashMap<String, UserConfigOption> result = new LinkedHashMap<>();
        schema.forEach((key, option) -> {
            List<String> errors = new ArrayList<>();
            validateOne(key, option, values.get(key), errors);
            if (!errors.isEmpty()) result.put(key, option);
        });
        return result;
    }

    private static void validateOne(String key, UserConfigOption option, Object value,
                                    List<String> errors) {
        String title = option.title() == null ? key : option.title();
        if (Boolean.TRUE.equals(option.required()) && empty(value)) {
            errors.add(title + " is required but not provided");
            return;
        }
        if (empty(value)) return;
        switch (option.type() == null ? "string" : option.type()) {
            case "string" -> {
                if (value instanceof List<?> list) {
                    if (!Boolean.TRUE.equals(option.multiple())) {
                        errors.add(title + " must be a string, not an array");
                    } else if (list.stream().anyMatch(item -> !(item instanceof String))) {
                        errors.add(title + " must be an array of strings");
                    }
                } else if (!(value instanceof String)) errors.add(title + " must be a string");
            }
            case "number" -> {
                if (!(value instanceof Number number)) errors.add(title + " must be a number");
                else {
                    if (option.min() != null && number.doubleValue() < option.min())
                        errors.add(title + " must be at least " + option.min());
                    if (option.max() != null && number.doubleValue() > option.max())
                        errors.add(title + " must be at most " + option.max());
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) errors.add(title + " must be a boolean");
            }
            case "file", "directory" -> {
                if (!(value instanceof String)) errors.add(title + " must be a path string");
            }
            default -> errors.add(title + " has unsupported type " + option.type());
        }
    }

    private static boolean empty(Object value) {
        return value == null || value instanceof String string && string.isEmpty()
            || value instanceof List<?> list && (list.isEmpty()
                || list.stream().anyMatch(UserConfigValidator::empty));
    }
}
