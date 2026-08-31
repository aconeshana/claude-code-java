package com.claudecode.session.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.claudecode.session.stats.ClaudeCodeStats.ModelUsage;


class TranscriptStatsScannerTest {

    @TempDir Path tempDir;

    @Test
    void cappedReadStartsAfterFirstPartialLineInTail() throws IOException {
        assertEquals(TranscriptStatsScanner.MAX_JSONL_READ_BYTES, 100L * 1024 * 1024);
        Path transcript = tempDir.resolve("large.jsonl");
        String old = "{\"type\":\"user\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n";
        String spanning = "{\"type\":\"progress\",\"padding\":\"" + "x".repeat(1800) + "\"}\n";
        String recentUser = "{\"type\":\"user\",\"timestamp\":\"2026-08-12T00:00:00Z\","
            + "\"isSidechain\":false}\n";
        String recentAssistant = "{\"type\":\"assistant\",\"timestamp\":\"2026-08-12T00:00:05Z\","
            + "\"message\":{\"content\":[{\"name\":\"Read\",\"type\":\"tool_use\"}],"
            + "\"usage\":{\"output_tokens\":4,\"input_tokens\":6},\"model\":\"m\"}}\n";
        Files.writeString(transcript, old + spanning + recentUser + recentAssistant,
            StandardCharsets.UTF_8);

        TranscriptStatsScanner.ScanResult result =
            new TranscriptStatsScanner(1024).scan(transcript, false);

        assertEquals(2, result.mainCount());
        assertEquals("2026-08-12T00:00:00Z", result.firstTimestamp());
        assertEquals("2026-08-12T00:00:05Z", result.lastTimestamp());
        assertEquals(1, result.toolUseCount());
        ModelUsage usage = result.usageByModel().get("m");
        assertEquals(6, usage.inputTokens());
        assertEquals(4, usage.outputTokens());
    }

    @Test
    void malformedRowsAndBomAreTolerated() throws IOException {
        Path transcript = tempDir.resolve("tolerant.jsonl");
        Files.writeString(transcript,
            """
            ﻿{"type":"user","timestamp":"2026-08-12T00:00:00Z"}
            { malformed }
            {"type":"user","timestamp":"2026-08-12T01:00:00Z"}\
            {"unexpected":true}
            {"type":"speculation-accept","timeSavedMs":25}
            """);

        TranscriptStatsScanner.ScanResult result =
            new TranscriptStatsScanner(1024 * 1024).scan(transcript, false);

        assertEquals(1, result.mainCount());
        assertEquals(25, result.speculationMs());
    }
}
