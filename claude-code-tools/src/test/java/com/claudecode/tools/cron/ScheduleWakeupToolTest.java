package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.ToolTexts;
import com.claudecode.core.serialization.JsonUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.claudecode.tools.loop.LoopWakeupManager;

class ScheduleWakeupToolTest {

    private final AtomicLong now = new AtomicLong(
        Instant.parse("2026-07-31T08:00:30Z").toEpochMilli());

    @AfterEach
    void resetStore() {
        CronStore.resetForTest();
    }

    @Test
    void schemaAndDescriptionMatchOfficial197Contract() {
        ScheduleWakeupTool tool = new ScheduleWakeupTool(manager(true));

        assertEquals("ScheduleWakeup", tool.name());
        assertEquals(ToolTexts.description("ScheduleWakeup"),
            tool.description());
        assertTrue(Strings.CS.startsWith(tool.description(), "Schedule when to resume work in /loop dynamic mode — the user invoked /loop without an interval"));
        assertTrue(Strings.CS.contains(tool.description(), "**Don't pick 300s.**"));
        var schema = tool.inputSchema();
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
        assertEquals("number", schema.path("properties").path("delaySeconds").path("type").asText());
        assertEquals("Seconds from now to wake up. Clamped to [60, 3600] by the runtime.",
            schema.path("properties").path("delaySeconds").path("description").asText());
        assertEquals(3, schema.path("required").size());
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertTrue(tool.shouldDefer());
        assertEquals(1_000, tool.maxResultSizeChars());
        assertInstanceOf(PermissionDecision.Allow.class,
            tool.checkPermissions(JsonUtils.getMapper().createObjectNode(),
                ToolPermissionContext.of(Path.of("."))));
    }

    @Test
    void disabledGateReturnsOfficialEndedPayload() {
        ScheduleWakeupTool tool = new ScheduleWakeupTool(manager(false));

        StructuredToolOutput output = tool.call(input(90), context());

        assertEquals("Wakeup not scheduled. Either the /loop dynamic runtime gate is off or the loop reached its maximum duration — the loop has ended; do not re-issue.",
            output.text());
        assertEquals(Map.of(
            "scheduledFor", 0L,
            "clampedDelaySeconds", 0,
            "wasClamped", false), output.toolUseResult());
    }

    @Test
    void successfulCallReturnsOfficialTextAndStructuredPayload() {
        ScheduleWakeupTool tool = new ScheduleWakeupTool(manager(true));

        StructuredToolOutput output = tool.call(input(1), context());

        assertEquals("Next wakeup scheduled for 08:02:00 (in 90s) (clamped to 60s from your requested value). Nothing more to do this turn — the harness re-invokes you when the wakeup fires or a task-notification arrives.",
            output.text());
        assertEquals(Map.of(
            "scheduledFor", Instant.parse("2026-07-31T08:02:00Z").toEpochMilli(),
            "clampedDelaySeconds", 60,
            "wasClamped", true), output.toolUseResult());
    }

    private LoopWakeupManager manager(boolean enabled) {
        return new LoopWakeupManager(
            () -> enabled, () -> false, now::get, ZoneOffset.UTC,
            7L * 24 * 60 * 60 * 1_000, 15_000, () -> 0x1234abcd);
    }

    private ObjectNode input(double delay) {
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("delaySeconds", delay);
        input.put("reason", "watching deploy");
        input.put("prompt", "/loop check deploy");
        return input;
    }

    private ToolExecutionContext context() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }
}
