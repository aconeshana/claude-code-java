package com.claudecode.commands.impl.context;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.insights.InsightsPort;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


class InsightsCommandTest {

    private static CommandContext.Builder builder(Path cwd) {
        return CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0,
            cwd.toString(), false);
    }

    private static InsightsPort.Stats data(long scanned) {
        return new InsightsPort.Stats(42, scanned == 0 ? null : scanned,
            1234, 99.6, 7, "2026-01-01", "2026-07-12");
    }

    @Test
    void metadata_matchesTs() {
        InsightsCommand command = new InsightsCommand();
        assertEquals("insights", command.name());
        assertEquals("Generate a report analyzing your Claude Code sessions", command.description());
        assertTrue(command.isLongRunning(), "up to ~56 LLM calls — must not run on the GUI thread");
    }

    @Test
    void noPipeline_degradesToMessage(@TempDir Path tmp) {
        CommandResult r = new InsightsCommand().execute(builder(tmp).build(), "");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "Insights is not available"));
    }

    @Test
    void buildPrompt_withInsights(@TempDir Path tmp) throws Exception {
        Map<String, JsonNode> insights = new LinkedHashMap<>();
        insights.put("at_a_glance", JsonUtils.getMapper().readTree(
            "{\"whats_working\":\"Great flow\",\"quick_wins\":\"Try /memory\"}"));
        InsightsPort.Report report = new InsightsPort.Report(
            insights, JsonUtils.getMapper().valueToTree(insights).toPrettyString(),
            tmp.resolve("usage-data").resolve("report.html"), data(100));

        String prompt = InsightsCommand.buildPrompt(report, "file://" + report.htmlPath());

        // Stats line: scanned > analyzed → dual label.
        assertTrue(Strings.CS.contains(prompt, "100 sessions total · 42 analyzed"), prompt);
        assertTrue(Strings.CS.contains(prompt, "1,234 messages"));
        assertTrue(Strings.CS.contains(prompt, "100h"), "rounded duration hours");
        assertTrue(Strings.CS.contains(prompt, "7 commits"));
        assertTrue(Strings.CS.contains(prompt, "2026-01-01 to 2026-07-12"));

        assertTrue(Strings.CS.contains(prompt, "**What's working:** Great flow See _Impressive Things You Did_."));
        assertTrue(Strings.CS.contains(prompt, "**Quick wins to try:** Try /memory See _Features to Try_."));
        // Verbatim <message> block with the file URL.
        assertTrue(Strings.CS.contains(prompt, "Now output the following message exactly:"));
        assertTrue(Strings.CS.contains(prompt, "<message>\nYour shareable insights report is ready:\nfile://"));
        assertTrue(Strings.CS.contains(prompt, "Want to dig into any section or try one of the suggestions?\n</message>"));
        assertTrue(Strings.CS.contains(prompt, "Facets directory: "));
    }

    @Test
    void buildPrompt_noInsights_fallbackLine(@TempDir Path tmp) {
        InsightsPort.Report report = new InsightsPort.Report(
            Map.of(), "{}", tmp.resolve("report.html"), data(0));
        String prompt = InsightsCommand.buildPrompt(report, "file://x");
        assertTrue(Strings.CS.contains(prompt, "_No insights generated_"));
        assertTrue(Strings.CS.contains(prompt, "42 sessions"), "no-scanned label variant");
    }

    @Test
    void nullFromSupplier_degradesToMessage(@TempDir Path tmp) {
        CommandContext ctx = builder(tmp)
            .insightsPipeline(() -> null)
            .build();
        CommandResult r = new InsightsCommand().execute(ctx, "");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "Insights is not available"));
    }

    @Test
    void promptResultCarriesBuiltinProgressEnvelope() {
        CommandResult result = InsightsCommand.promptResult("report prompt");

        assertTrue(result.shouldQuery());
        assertEquals("analyzing your sessions", result.promptInvocation().progressMessage());
        assertEquals("builtin", result.promptInvocation().source());
        assertEquals("insights", result.promptInvocation().userFacingName());
    }
}
