package com.claudecode.ui.lanterna.stats;

import org.apache.commons.lang3.Strings;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


class StatsScreenshotTest {

    private static InteractiveSessionPort.StatsSnapshot stats(boolean withModels) {
        Map<String, InteractiveSessionPort.ModelUsage> usage = new LinkedHashMap<>();
        if (withModels) {
            usage.put("claude-opus-4-8", new InteractiveSessionPort.ModelUsage(1000, 500, 0, 0, 0, 0, 0, 0));
            usage.put("claude-sonnet-5", new InteractiveSessionPort.ModelUsage(300, 100, 0, 0, 0, 0, 0, 0));
            usage.put("claude-haiku-4-5", new InteractiveSessionPort.ModelUsage(50, 20, 0, 0, 0, 0, 0, 0));
            usage.put("claude-fable-5", new InteractiveSessionPort.ModelUsage(10, 5, 0, 0, 0, 0, 0, 0));
        }
        return new InteractiveSessionPort.StatsSnapshot(
            42, 1234, 30, 12,
            new InteractiveSessionPort.StreakInfo(2, 5, "2026-07-10", "2026-06-01", "2026-06-05"),
            List.of(new InteractiveSessionPort.DailyActivity("2026-07-01", 10, 2, 3)),
            List.of(new InteractiveSessionPort.DailyModelTokens("2026-07-01", Map.of("claude-opus-4-8", 900L)),
                    new InteractiveSessionPort.DailyModelTokens("2026-07-02", Map.of("claude-opus-4-8", 600L))),
            new InteractiveSessionPort.SessionStats("s1", 3_600_000, 50, "2026-07-01T02:00:00.000Z"),
            usage, "2026-06-01T00:00:00.000Z", "2026-07-12T00:00:00.000Z",
            "2026-07-01", 14, 0);
    }

    private static String text(List<List<StatsScreenshot.Span>> lines) {
        StringBuilder sb = new StringBuilder();
        for (var line : lines) {
            for (var s : line) sb.append(s.text());
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void overview_twoColumnGeometryAndRows() {
        String out = text(StatsScreenshot.renderStats(stats(true), true, ZoneOffset.UTC));
        // Label padded to 18, values present.
        assertTrue(Strings.CS.contains(out, "Favorite model:   Opus 4.8"), out);
        assertTrue(Strings.CS.contains(out, "Total tokens:     2.0k"), out);
        assertTrue(Strings.CS.contains(out, "Sessions:         42"), out);
        assertTrue(Strings.CS.contains(out, "Longest session:  1h 0m"), out.lines()
            .filter(l -> Strings.CS.contains(l, "Longest session")).findFirst().orElse("(missing)"));
        assertTrue(Strings.CS.contains(out, "Current streak:   2 days"), out);
        assertTrue(Strings.CS.contains(out, "Active days:      12/30"), out);
        assertTrue(Strings.CS.contains(out, "Peak hour:        14:00-15:00"), out);
        assertTrue(Strings.CS.contains(out, "Stats from the last 30 days"), out);
        // Heatmap block present (56-col fixed → 52-week grid + legend).
        assertTrue(Strings.CS.contains(out, "Less ░ ▒ ▓ █ More"), out);
    }

    @Test
    void statsLabelRightAlignedOnLastLine() {
        var lines = StatsScreenshot.renderStats(stats(true), true, ZoneOffset.UTC);
        var last = lines.getLast();
        assertEquals("/stats", last.getLast().text());
// Right-aligned at content width 70 (label ends at or before col 70, padding ≥ 2.
        int width = last.stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(width >= "Stats from the last 30 days".length() + 2 + 6, "padded: " + width);
    }

    @Test
    void models_summaryAndTop3Cap() {
        String out = text(StatsScreenshot.renderStats(stats(true), false, ZoneOffset.UTC));
        assertTrue(Strings.CS.contains(out, "★ Favorite: Opus 4.8 · ◯ Total: 2.0k tokens"), out);
        assertTrue(Strings.CS.contains(out, "● Opus 4.8 (75.6%)"), out);
        assertTrue(Strings.CS.contains(out, "In: 1.0k · Out: 500"), out);
        // 4 models in data, only top-3 rendered.
        assertFalse(Strings.CS.contains(out, "Fable"), "4th model must be capped: " + out);
        assertTrue(Strings.CS.contains(out, "Tokens per Day"), out);
    }

    @Test
    void models_emptyUsage() {
        String out = text(StatsScreenshot.renderStats(stats(false), false, ZoneOffset.UTC));
        assertTrue(Strings.CS.contains(out, "No model usage data available"));
    }

    @Test
    void toImage_darkBackgroundAndPadding() {
        var lines = StatsScreenshot.renderStats(stats(true), true, ZoneOffset.UTC);
        BufferedImage img = StatsScreenshot.toImage(lines);
        assertTrue(img.getWidth() > 96 && img.getHeight() > 96, "bigger than 2×48px padding");

        int rgb = img.getRGB(1, 1);
        assertEquals(30, (rgb >> 16) & 0xFF);
        assertEquals(30, (rgb >> 8) & 0xFF);
        assertEquals(30, rgb & 0xFF);
    }
}
