package com.claudecode.core.config;

import org.apache.commons.lang3.StringUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared environment-variable parsing conventions. */
public final class EnvUtils {
    private EnvUtils() {}

    public static boolean isEnvTruthy(String value) {
        if (StringUtils.isEmpty(value)) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    public static boolean isEnvDefinedFalsy(String value) {
        if (StringUtils.isEmpty(value)) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "0", "false", "no", "off" -> true;
            default -> false;
        };
    }

    public static Map<String, String> parseEnvVars(List<String> values) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (values == null) return parsed;
        for (String entry : values) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException(
                    "Invalid environment variable format: " + entry
                        + ", environment variables should be added as: "
                        + "-e KEY1=value1 -e KEY2=value2");
            }
            parsed.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return parsed;
    }
}
