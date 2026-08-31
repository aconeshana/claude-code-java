package com.claudecode.commands.impl.info;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class CostCommandTest {

    private final CostCommand command = new CostCommand();
    private CommandContext ctx;

    @BeforeEach
    void setUp() {
        SessionCostState.get().reset();
        ctx = CommandContext.builder(
            "claude-opus-4-8", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false).build();
    }

    @Test
    void emptyState_showsAllFiveLinesWithZeroUsage() {
        CommandResult result = command.execute(ctx, "");
        String out = result.output();
        assertEquals(CommandResultDisplay.LOCAL, result.display(),
            "TS /cost is type:'local': user slash input followed by system local-command output");
        assertTrue(Strings.CS.contains(out, "Total cost:            $0.0000"), out);
        assertTrue(Strings.CS.contains(out, "Total duration (API):  0s"), out);
        assertTrue(Strings.CS.contains(out, "Total duration (wall):"), out);
        assertTrue(Strings.CS.contains(out, "Total code changes:    0 lines added, 0 lines removed"), out);
        assertTrue(Strings.CS.contains(out, "Usage:                 0 input, 0 output, 0 cache read, 0 cache write"), out);
    }

    @Test
    void withUsage_pricesAndBreaksDownByModel() {
        // Opus 4.8 is priced via CostCalculator.forModel; 1M input tokens → a
        // non-trivial cost (>$0.5 so the 2-decimal formatCost rule applies).
        SessionCostState.get().recordApiRequest(
            "claude-opus-4-8", new Usage(1_000_000, 100_000, 0, 0), 5000);
        SessionCostState.get().recordLinesChanged(42, 7);

        String out = command.execute(ctx, "").output();

        // Cost line present with 2-decimal formatting (cost > $0.5).
        assertTrue(out.matches("(?s).*Total cost:            \\$\\d+\\.\\d{2}\\n.*"),
            "expected 2-decimal cost; got: " + out);
        assertTrue(Strings.CS.contains(out, "Total duration (API):  5s"), out);
        assertTrue(Strings.CS.contains(out, "Total code changes:    42 lines added, 7 lines removed"), out);
// Per-model line grouped under the display name, using.
        assertTrue(Strings.CS.contains(out, "Usage by model:"), out);
        assertTrue(Strings.CS.contains(out, "Opus 4.8:"), out);
        assertTrue(Strings.CS.contains(out, "1.0m input"), out);
        assertTrue(Strings.CS.contains(out, "100.0k output"), out);
        assertTrue(out.matches("(?s).*Opus 4\\.8:.*\\(\\$\\d+\\.\\d{2}\\).*"), out);
    }

    @Test
    void singularLineWording() {
        SessionCostState.get().recordLinesChanged(1, 1);
        String out = command.execute(ctx, "").output();
        assertTrue(Strings.CS.contains(out, "1 line added, 1 line removed"), out);
    }

    @Test
    void webSearchAppearsWhenPresent() {
        SessionCostState.get().recordApiRequest("claude-opus-4-8",
            new Usage(10, 5, 0, 0, new Usage.ServerToolUse(3, 0)), 100);
        String out = command.execute(ctx, "").output();
        assertTrue(Strings.CS.contains(out, "3 web search"), out);
    }

    @Test
    void multipleRawModelsFoldByDisplayName() {
        // Two raw ids that both display as "Opus 4.8" collapse to one line.
        SessionCostState.get().recordApiRequest("claude-opus-4-8", new Usage(100, 0, 0, 0), 100);
        SessionCostState.get().recordApiRequest("anthropic.claude-opus-4-8", new Usage(200, 0, 0, 0), 100);
        String out = command.execute(ctx, "").output();
        // Exactly one "Opus 4.8:" line, with summed input 300.
        int occurrences = out.split("Opus 4.8:", -1).length - 1;
        assertEquals(1, occurrences, "raw ids should fold into one display line; got: " + out);
        assertTrue(Strings.CS.contains(out, "300 input"), out);
    }

    @Test
    void restoredCostsAreDisplayedWithoutRepricingPersistedUsage() {
        SessionCostState.get().restore(new SessionCostState.Snapshot(
            0, 0, 0, 0, 0, 0,
            Map.of("custom-model", new Usage(1, 0, 0, 0)),
            Map.of("custom-model", 0.123456),
            0.654321));

        String out = command.execute(ctx, "").output();

        assertTrue(Strings.CS.contains(out, "Total cost:            $0.65"), out);
        assertTrue(Strings.CS.contains(out, "($0.1235)"), out);
    }
}
