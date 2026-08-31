package com.claudecode.tools.shell;

import com.claudecode.core.config.EnvValidation;
import com.claudecode.core.text.StringUtils;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Arrays;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Shell (Bash/PowerShell) output length limit resolution and inline truncation.
 *
 * <ul>
 *   <li>{@code BASH_MAX_OUTPUT_DEFAULT}
 *       (30 000) / {@code BASH_MAX_OUTPUT_UPPER_LIMIT} (150 000) and
 *       {@code getMaxOutputLength} ({@code BASH_MAX_OUTPUT_LENGTH} env var
 *       resolved through {@code validateBoundedIntEnvVar}).</li>
 *   <li>{@code formatOutput}: head-keeping
 *       truncation at {@code getMaxOutputLength} chars with the
 *       {@code "\n\n... [N lines truncated] ..."} marker, and its
 *       {@code isImageOutput} data-URI guard (image output is never truncated).</li>
 *   <li>{@code countCharInString} (the
 *       remaining-line count fed into the truncation marker).</li>
 * </ul>
 *
 * <p>All methods take an injectable env lookup ({@code Function<String,String>},
 * same seam as {@code ModelNames.defaultMainLoopModel(envLookup)}) so tests can
 * exercise env-dependent behaviour without mutating the real process environment.
 */
public final class OutputLimits {


    public static final int BASH_MAX_OUTPUT_UPPER_LIMIT = 150_000;

    public static final int BASH_MAX_OUTPUT_DEFAULT = 30_000;


    private static final Pattern IMAGE_OUTPUT =
        Pattern.compile("^data:image/[a-z0-9.+_-]+;base64,", Pattern.CASE_INSENSITIVE);

    private OutputLimits() {}


    public static int getMaxOutputLength() {
        return getMaxOutputLength(SubprocessEnvironment::get);
    }

    /**
     * {@link #getMaxOutputLength} with an injectable env lookup. Unset →
     * {@link #BASH_MAX_OUTPUT_DEFAULT}; invalid (non-numeric / ≤ 0) → default;
     * above {@link #BASH_MAX_OUTPUT_UPPER_LIMIT} → capped — the exact
     * {@code validateBoundedIntEnvVar} semantics {@code /doctor} reports on.
     */
    public static int getMaxOutputLength(Function<String, String> envLookup) {
        return (int) EnvValidation.validateBoundedIntEnvVar(
            "BASH_MAX_OUTPUT_LENGTH",
            envLookup.apply("BASH_MAX_OUTPUT_LENGTH"),
            BASH_MAX_OUTPUT_DEFAULT,
            BASH_MAX_OUTPUT_UPPER_LIMIT).effective();
    }


    public static String formatOutput(String content, Function<String, String> envLookup) {
        if (!wouldTruncate(content, envLookup)) {
            return content;
        }
        int maxOutputLength = getMaxOutputLength(envLookup);
        String truncatedPart = content.substring(0, maxOutputLength);
        int remainingLines = StringUtils.countChar(content, '\n', maxOutputLength) + 1;
        return truncatedPart + "\n\n... [" + remainingLines + " lines truncated] ...";
    }

    /** Returns whether {@link #formatOutput} will replace the tail with a truncation marker. */
    public static boolean wouldTruncate(String content, Function<String, String> envLookup) {
        return org.apache.commons.lang3.StringUtils.isNotEmpty(content)
            && !isImageOutput(content)
            && content.length() > getMaxOutputLength(envLookup);
    }

    /**
     * Removes only leading and trailing lines that contain no non-whitespace characters.
     */
    public static String stripEmptyLines(String content) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(content)) return content;
        String[] lines = content.split("\\n", -1);
        int start = 0;
        while (start < lines.length && lines[start].trim().isEmpty()) start++;
        int end = lines.length - 1;
        while (end >= start && lines[end].trim().isEmpty()) end--;
        if (start > end) return "";
        return String.join("\n", Arrays.copyOfRange(lines, start, end + 1));
    }


    static boolean isImageOutput(String content) {
        return IMAGE_OUTPUT.matcher(content).find();
    }

}
