package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;


class TaskOutputFormattingTest {

    private static final Path OUT = Path.of("/tmp/tasks/abc123.output");

    private static Function<String, String> env(String value) {
        return name -> Strings.CS.equals("TASK_MAX_OUTPUT_LENGTH", name) ? value : null;
    }

    // ── getMaxTaskOutputLength ───────────────────────────────────────────────

    @Test
    void unsetEnv_usesDefault32000() {
        assertEquals(32_000, TaskOutputFormatting.getMaxTaskOutputLength(env(null)));
        assertEquals(32_000, TaskOutputFormatting.TASK_MAX_OUTPUT_DEFAULT);
    }

    @Test
    void validEnvValue_isHonored() {
        assertEquals(500, TaskOutputFormatting.getMaxTaskOutputLength(env("500")));
    }

    @Test
    void aboveUpperLimit_isCappedTo160000() {
        assertEquals(160_000, TaskOutputFormatting.getMaxTaskOutputLength(env("500000")));
        assertEquals(160_000, TaskOutputFormatting.TASK_MAX_OUTPUT_UPPER_LIMIT);
    }

    @Test
    void invalidEnvValue_fallsBackToDefault() {
        assertEquals(32_000, TaskOutputFormatting.getMaxTaskOutputLength(env("garbage")));
        assertEquals(32_000, TaskOutputFormatting.getMaxTaskOutputLength(env("0")));
        assertEquals(32_000, TaskOutputFormatting.getMaxTaskOutputLength(env("-1")));
    }

    // ── formatTaskOutput ─────────────────────────────────────────────────────

    @Test
    void underLimit_passesThroughUntruncated() {
        var result = TaskOutputFormatting.formatTaskOutput("short output", OUT, env("500"));
        assertFalse(result.wasTruncated());
        assertEquals("short output", result.content());
    }

    @Test
    void overLimit_prependsHeaderAndKeepsTail() {
        String output = "HEAD-" + "x".repeat(400) + "-TAIL";
        var result = TaskOutputFormatting.formatTaskOutput(output, OUT, env("200"));

        assertTrue(result.wasTruncated());
        String header = "[Truncated. Full output: " + OUT + "]\n\n";
        assertTrue(Strings.CS.startsWith(result.content(), header), result.content());
        // Total content length equals the limit exactly (header + tail slice).
        assertEquals(200, result.content().length());
        // Tail of the original output survives; the head does not.
        assertTrue(Strings.CS.endsWith(result.content(), "-TAIL"));
        assertFalse(Strings.CS.contains(result.content(), "HEAD-"));
        // Tail is the last (maxLen - header.length) chars, verbatim.
        assertEquals(output.substring(output.length() - (200 - header.length())),
            result.content().substring(header.length()));
    }

    @Test
    void exactlyAtLimit_isUnchanged() {
        String output = "z".repeat(200);
        var result = TaskOutputFormatting.formatTaskOutput(output, OUT, env("200"));
        assertFalse(result.wasTruncated());
        assertEquals(output, result.content());
    }

    @Test
    void defaultLimit_appliesWhenEnvUnset() {
        String output = "a".repeat(32_001);
        var result = TaskOutputFormatting.formatTaskOutput(output, OUT, env(null));
        assertTrue(result.wasTruncated());
        assertEquals(32_000, result.content().length());
    }

    @Test
    void pathologicallySmallLimit_clampsTailToEmptyInsteadOfThrowing() {
        // Limit smaller than the header itself: header survives, tail is empty.
        var result = TaskOutputFormatting.formatTaskOutput("y".repeat(100), OUT, env("10"));
        assertTrue(result.wasTruncated());
        assertEquals("[Truncated. Full output: " + OUT + "]\n\n", result.content());
    }
}
