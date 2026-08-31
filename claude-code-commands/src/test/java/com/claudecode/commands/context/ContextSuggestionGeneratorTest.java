package com.claudecode.commands.context;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData.MemoryFileEntry;
import com.claudecode.commands.context.ContextData.MessageBreakdown;
import com.claudecode.commands.context.ContextData.ToolIo;
import com.claudecode.commands.context.ContextSuggestionGenerator.Severity;
import com.claudecode.commands.context.ContextSuggestionGenerator.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextSuggestionGeneratorTest {

    private static ContextData data(int percentage, boolean autoCompact,
                                    List<ToolIo> tools, List<MemoryFileEntry> memory) {
        return new ContextData(
            List.of(), percentage * 2_000L, 200_000L, percentage, "m",
            memory, List.of(), List.of(), null,
            autoCompact ? 187_000L : null, autoCompact,
            new MessageBreakdown(0, 0, 0, 0, 0, tools), null);
    }

    @Test
    void nearCapacity_warnsWithAutocompactCopy() {
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(85, true, List.of(), List.of()));
        assertEquals(1, s.size());
        assertEquals(Severity.WARNING, s.getFirst().severity());
        assertEquals("Context is 85% full", s.getFirst().title());
        assertTrue(Strings.CS.contains(s.getFirst().detail(), "Autocompact will trigger soon"));
    }

    @Test
    void nearCapacity_disabledAutocompactCopy() {
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(85, false, List.of(), List.of()));
        assertTrue(Strings.CS.contains(s.getFirst().detail(), "Autocompact is disabled"));
    }

    @Test
    void largeBashResults_warnWithSavings() {
        // 40k tokens = 20% of 200k window, above both thresholds.
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(30, true, List.of(new ToolIo("Bash", 10_000, 30_000)), List.of()));
        assertEquals(1, s.size());
        assertEquals(Severity.WARNING, s.getFirst().severity());
        assertEquals("Bash results using 40k tokens (20%)", s.getFirst().title());
        assertEquals(20_000L, s.getFirst().savingsTokens());
    }

    @Test
    void unknownToolBelow20Percent_noSuggestion() {
        // 16% — above the 15% band but unknown tools need >= 20%.
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(30, true, List.of(new ToolIo("Task", 2_000, 30_000)), List.of()));
        assertTrue(s.isEmpty());
    }

    @Test
    void readBloat_skippedWhenAlreadyCoveredByLargeBand() {
        // 40k total Read tokens ≥ 15% band → large-tool suggestion fires,
        // read-bloat must NOT double-report.
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(30, true, List.of(new ToolIo("Read", 10_000, 30_000)), List.of()));
        assertEquals(1, s.size());
        assertTrue(Strings.CS.startsWith(s.getFirst().title(), "Read results using"));
    }

    @Test
    void readBloat_firesInTheMidBand() {
        // Result tokens 12k = 6% (≥5% + ≥10k) but total 13k < 15% band.
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(30, true, List.of(new ToolIo("Read", 1_000, 12_000)), List.of()));
        assertEquals(1, s.size());
        assertTrue(Strings.CS.startsWith(s.getFirst().title(), "File reads using 12k tokens"));
    }

    @Test
    void memoryBloat_listsLargestFiles() {
        List<MemoryFileEntry> memory = List.of(
            new MemoryFileEntry("/a/one.md", "User", 8_000),
            new MemoryFileEntry("/a/two.md", "Project", 2_500),
            new MemoryFileEntry("/a/three.md", "Project", 500),
            new MemoryFileEntry("/a/four.md", "Local", 100));
        List<Suggestion> s = ContextSuggestionGenerator.generate(
            data(30, true, List.of(), memory));
        assertEquals(1, s.size());
        assertTrue(Strings.CS.startsWith(s.getFirst().title(), "Memory files using 11.1k tokens"));
        assertTrue(Strings.CS.contains(s.getFirst().detail(), "one.md (8k)"));
        assertFalse(Strings.CS.contains(s.getFirst().detail(), "four.md"), "only top 3 listed");
    }

    @Test
    void autoCompactDisabled_infoOnlyInMidBand() {
        assertEquals(1, ContextSuggestionGenerator.generate(
            data(60, false, List.of(), List.of())).size());
        // Below 50%: nothing.
        assertTrue(ContextSuggestionGenerator.generate(
            data(40, false, List.of(), List.of())).isEmpty());
        // At 85% the near-capacity warning replaces it.
        List<Suggestion> high = ContextSuggestionGenerator.generate(
            data(85, false, List.of(), List.of()));
        assertEquals(1, high.size());
        assertEquals(Severity.WARNING, high.getFirst().severity());
    }

    @Test
    void sortOrder_warningsFirstThenBySavings() {
        List<Suggestion> s = ContextSuggestionGenerator.generate(data(85, true,
            List.of(
                new ToolIo("Read", 10_000, 30_000),   // info, save 12k
                new ToolIo("Bash", 10_000, 30_000)),  // warning, save 20k
            List.of()));
        assertEquals(3, s.size());
        assertEquals(Severity.WARNING, s.getFirst().severity());
        assertEquals(Severity.WARNING, s.get(1).severity());
        assertEquals(Severity.INFO, s.get(2).severity());
        // Bash (with savings) sorts above the near-capacity warning (no savings).
        assertTrue(Strings.CS.startsWith(s.getFirst().title(), "Bash results"));
    }
}
