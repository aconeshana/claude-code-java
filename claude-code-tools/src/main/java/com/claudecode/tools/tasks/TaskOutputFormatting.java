package com.claudecode.tools.tasks;

import com.claudecode.core.config.EnvValidation;
import com.claudecode.core.process.SubprocessEnvironment;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Task output length limit resolution and tail-keeping truncation for model-facing task output
 * ({@code TaskOutput} tool results).
 */
public final class TaskOutputFormatting {


    public static final int TASK_MAX_OUTPUT_UPPER_LIMIT = 160_000;

    public static final int TASK_MAX_OUTPUT_DEFAULT = 32_000;


    public record Formatted(String content, boolean wasTruncated) {}

    private TaskOutputFormatting() {}


    public static int getMaxTaskOutputLength() {
        return getMaxTaskOutputLength(SubprocessEnvironment::get);
    }

    /**
     * {@link #getMaxTaskOutputLength} with an injectable env lookup. Unset →
     * {@link #TASK_MAX_OUTPUT_DEFAULT}; invalid (non-numeric / ≤ 0) → default;
     * above {@link #TASK_MAX_OUTPUT_UPPER_LIMIT} → capped — the exact
     * {@code validateBoundedIntEnvVar} semantics {@code /doctor} reports on.
     */
    public static int getMaxTaskOutputLength(Function<String, String> envLookup) {
        return (int) EnvValidation.validateBoundedIntEnvVar(
            "TASK_MAX_OUTPUT_LENGTH",
            envLookup.apply("TASK_MAX_OUTPUT_LENGTH"),
            TASK_MAX_OUTPUT_DEFAULT,
            TASK_MAX_OUTPUT_UPPER_LIMIT).effective();
    }


    public static Formatted formatTaskOutput(String output, Path outputFile,
                                             Function<String, String> envLookup) {
        int maxLen = getMaxTaskOutputLength(envLookup);
        if (output.length() <= maxLen) {
            return new Formatted(output, false);
        }
        String header = "[Truncated. Full output: " + outputFile + "]\n\n";

        int availableSpace = Math.max(0, maxLen - header.length());
        String truncated = output.substring(output.length() - availableSpace);
        return new Formatted(header + truncated, true);
    }
}
