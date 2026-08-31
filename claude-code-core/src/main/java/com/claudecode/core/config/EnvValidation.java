package com.claudecode.core.config;

import org.apache.commons.lang3.StringUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates bounded-integer environment variables.
 *
 * Values use a leading signed decimal integer; unset or invalid values use the
 * default, non-positive values are invalid, and values above the upper limit
 * are capped.
 *
 * <p>Lives in {@code claude-code-core} so every consumer of the same env var
 * shares one parse: the tool layer ({@code OutputLimits} /
 * {@code TaskOutputFormatting} in {@code claude-code-tools}, which apply
 * {@code BASH_MAX_OUTPUT_LENGTH} / {@code TASK_MAX_OUTPUT_LENGTH} at the real
 * truncation points), the API layer ({@code ModelOutputTokens} in
 * {@code claude-code-services}, which calls this directly — there is no
 * facade class in between), and {@code /doctor}'s "Environment Variables"
 * section ({@code DoctorDiagnosticsCollector}, also a direct caller). Keeping
 * all of them on this one function guarantees doctor's verdict matches what
 * the tool/request layers actually do.
 */
public final class EnvValidation {

    private static final Pattern LEADING_INTEGER = Pattern.compile("^\\s*([+-]?\\d+)");

    private EnvValidation() {}

    /** {@code status} is one of {@code "valid"}, {@code "capped"}, {@code "invalid"}. */
    public record Result(long effective, String status, String message) {}

    /**
     * @param name         env var name (only used in the message text)
     * @param value        raw env value (may be null/blank → uses default)
     * @param defaultValue value to use when unset or invalid
     * @param upperLimit   values above this are capped down to it
     */
    public static Result validateBoundedIntEnvVar(String name, String value,
                                                  long defaultValue, long upperLimit) {
        if (StringUtils.isBlank(value)) {
            return new Result(defaultValue, "valid", null);
        }
        Long parsed = parseLeadingInt(value);
        if (parsed == null || parsed <= 0) {
            return new Result(defaultValue, "invalid",
                "Invalid value \"" + value + "\" (using default: " + defaultValue + ")");
        }
        if (parsed > upperLimit) {
            return new Result(upperLimit, "capped", "Capped from " + parsed + " to " + upperLimit);
        }
        return new Result(parsed, "valid", null);
    }

    private static Long parseLeadingInt(String value) {
        Matcher m = LEADING_INTEGER.matcher(value);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
