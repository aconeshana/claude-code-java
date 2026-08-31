package com.claudecode.ui.lanterna.stats;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.model.ModelNames;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.StringUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.claudecode.ui.lanterna.dialog.StatsDialog;

import javax.imageio.ImageIO;
import com.claudecode.ui.lanterna.theme.LanternaTheme;


public final class StatsScreenshot {


    private static final Color BG = new Color(30, 30, 30);
    private static final Color DEFAULT_FG = new Color(229, 229, 229);
    private static final Color GRAY = new Color(128, 128, 128);      // chalk.gray
    private static final Color MAGENTA = new Color(205, 49, 222);   // chalk.magenta
    private static final int PADDING = 48;
    private static final int FONT_SIZE = 22;

    private StatsScreenshot() {}

    record Span(String text, Color color, boolean bold) {
        static Span plain(String t) { return new Span(t, DEFAULT_FG, false); }
        static Span gray(String t)  { return new Span(t, GRAY, false); }
    }

    public record Result(boolean success, String message) {}


    public static Result copy(InteractiveSessionPort.StatsSnapshot stats, boolean overviewTab, ZoneId zone) {
        try {
            List<List<Span>> lines = renderStats(stats, overviewTab, zone);
            BufferedImage image = toImage(lines);
            return copyPngToClipboard(image);
        } catch (Exception e) {
            return new Result(false, "Failed to copy screenshot: "
                + (e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }



    static List<List<Span>> renderStats(InteractiveSessionPort.StatsSnapshot stats, boolean overviewTab, ZoneId zone) {
        List<List<Span>> lines = overviewTab
            ? renderOverviewLines(stats, zone)
            : renderModelsLines(stats);


        while (!lines.isEmpty() && lineWidth(lines.getLast()) == 0) {
            lines.removeLast();
        }
        // Right-aligned gray "/stats" on the last line (content width 70/80).
        if (!lines.isEmpty()) {
            int contentWidth = overviewTab ? 70 : 80;
            List<Span> last = new ArrayList<>(lines.getLast());
            int pad = Math.max(2, contentWidth - lineWidth(last) - "/stats".length());
            last.add(Span.plain(" ".repeat(pad)));
            last.add(Span.gray("/stats"));
            lines.set(lines.size() - 1, last);
        }
        return lines;
    }

    private static int lineWidth(List<Span> line) {
        int w = 0;
        for (Span s : line) w += s.text().length();
        return w;
    }


    private static List<List<Span>> renderOverviewLines(InteractiveSessionPort.StatsSnapshot stats, ZoneId zone) {
        List<List<Span>> lines = new ArrayList<>();
        Color claude = new Color(LanternaTheme.claude().getRed(), LanternaTheme.claude().getGreen(), LanternaTheme.claude().getBlue());

        // Heatmap — fixed 56-col screenshot width.
        if (!stats.dailyActivity().isEmpty()) {
            HeatmapRenderer.Heatmap heatmap =
                HeatmapRenderer.render(stats.dailyActivity(), 56, zone);
            lines.add(List.of(Span.plain(heatmap.monthLabelRow())));
            for (int day = 0; day < 7; day++) {
                List<Span> row = new ArrayList<>();
                row.add(Span.plain(heatmap.dayLabels().get(day)));
                for (HeatmapRenderer.Cell cell : heatmap.grid().get(day)) {
                    row.add(new Span(String.valueOf(cell.ch()),
                        cell.intensity() <= 0 ? GRAY : claude, false));
                }
                lines.add(row);
            }
            lines.add(List.of());
            lines.add(List.of(Span.plain("    Less "),
                new Span("░ ▒ ▓ █", claude, false), Span.plain(" More")));
            lines.add(List.of());
        }

        List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> models = sortedModels(stats);
        long totalTokens = models.stream()
            .mapToLong(e -> e.getValue().inputTokens() + e.getValue().outputTokens()).sum();

        if (!models.isEmpty()) {
            lines.add(row("Favorite model", ModelNames.displayName(models.getFirst().getKey()),
                "Total tokens", FormatUtils.formatNumber(totalTokens), claude));
        }
        lines.add(List.of());
        lines.add(row("Sessions", FormatUtils.formatNumber(stats.totalSessions()),
            "Longest session", stats.longestSession() != null
                ? FormatUtils.formatDuration(stats.longestSession().duration()) : "N/A", claude));
        lines.add(row("Current streak", countWithNoun(stats.streaks().currentStreak()),
            "Longest streak", countWithNoun(stats.streaks().longestStreak()), claude));
        Integer peak = stats.peakActivityHour();
        lines.add(row("Active days", stats.activeDays() + "/" + stats.totalDays(),
            "Peak hour", peak != null ? peak + ":00-" + (peak + 1) + ":00" : "N/A", claude));
        lines.add(List.of());

        String factoid = StatsDialog.generateFunFactoid(stats, totalTokens);
        lines.add(List.of(new Span(factoid, claude, false)));
        lines.add(List.of(Span.gray("Stats from the last " + stats.totalDays() + " days")));
        return lines;
    }

    private static String countWithNoun(long days) {
        return days + " " + StringUtils.plural(days, "day");
    }


    private static List<Span> row(String l1, String v1, String l2, String v2, Color claude) {
        String label1 = StringUtils.padEnd(l1 + ":", 18);
        int spaceBetween = Math.max(2, 40 - (label1.length() + v1.length()));
        String label2 = StringUtils.padEnd(l2 + ":", 18);
        return List.of(
            Span.plain(label1), new Span(v1, claude, false),
            Span.plain(" ".repeat(spaceBetween)),
            Span.plain(label2), new Span(v2, claude, false));
    }


    private static List<List<Span>> renderModelsLines(InteractiveSessionPort.StatsSnapshot stats) {
        List<List<Span>> lines = new ArrayList<>();
        List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> models = sortedModels(stats);
        if (models.isEmpty()) {
            lines.add(List.of(Span.gray("No model usage data available")));
            return lines;
        }
        long totalTokens = models.stream()
            .mapToLong(e -> e.getValue().inputTokens() + e.getValue().outputTokens()).sum();

        // Chart at fixed screenshot width 80.
        StatsDialog.TokenChart chart = StatsDialog.buildTokenChart(
            stats.dailyModelTokens(), models.stream().map(Map.Entry::getKey).toList(), 80);
        if (chart != null) {
            Color[] seriesColors = {
                new Color(LanternaTheme.suggestion().getRed(), LanternaTheme.suggestion().getGreen(), LanternaTheme.suggestion().getBlue()),
                new Color(LanternaTheme.toolSuccess().getRed(), LanternaTheme.toolSuccess().getGreen(), LanternaTheme.toolSuccess().getBlue()),
                new Color(LanternaTheme.toolWarning().getRed(), LanternaTheme.toolWarning().getGreen(), LanternaTheme.toolWarning().getBlue())};
            lines.add(List.of(new Span("Tokens per Day", DEFAULT_FG, true)));
            for (List<AsciiChart.Cell> chartRow : chart.grid()) {
                List<Span> line = new ArrayList<>();
                for (AsciiChart.Cell cell : chartRow) {
                    Color color = cell.seriesIndex() == null
                        ? DEFAULT_FG : seriesColors[cell.seriesIndex() % seriesColors.length];
                    line.add(new Span(cell.text(), color, false));
                }
                lines.add(line);
            }
            lines.add(List.of(Span.gray(chart.xAxisLabels())));
            List<Span> legend = new ArrayList<>();
            for (int i = 0; i < chart.legendModels().size(); i++) {
                if (i > 0) legend.add(Span.plain(" · "));
                legend.add(new Span("●", seriesColors[i % seriesColors.length], false));
                legend.add(Span.plain(" " + chart.legendModels().get(i)));
            }
            lines.add(legend);
            lines.add(List.of());
        }

        // ★ Favorite: X · ◯ Total: N tokens (magenta accents).
        lines.add(List.of(
            Span.plain("★ Favorite: "),
            new Span(ModelNames.displayName(models.getFirst().getKey()), MAGENTA, true),
            Span.plain(" · ◯ Total: "),
            new Span(FormatUtils.formatNumber(totalTokens), MAGENTA, false),
            Span.plain(" tokens")));
        lines.add(List.of());

        // Top-3 model breakdown.
        for (var entry : models.subList(0, Math.min(3, models.size()))) {
            InteractiveSessionPort.ModelUsage usage = entry.getValue();
            long modelTokens = usage.inputTokens() + usage.outputTokens();
            String pct = String.format(Locale.US, "%.1f",
                totalTokens > 0 ? modelTokens * 100.0 / totalTokens : 0);
            lines.add(List.of(
                Span.plain("● "),
                new Span(ModelNames.displayName(entry.getKey()), DEFAULT_FG, true),
                Span.gray(" (" + pct + "%)")));
            lines.add(List.of(Span.gray("  In: " + FormatUtils.formatNumber(usage.inputTokens())
                + " · Out: " + FormatUtils.formatNumber(usage.outputTokens()))));
        }
        return lines;
    }

    private static List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> sortedModels(InteractiveSessionPort.StatsSnapshot stats) {
        return stats.modelUsage().entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, InteractiveSessionPort.ModelUsage> e) ->
                e.getValue().inputTokens() + e.getValue().outputTokens()).reversed())
            .toList();
    }



    static BufferedImage toImage(List<List<Span>> lines) {
        // Measure with a scratch image (AWT needs a graphics context for metrics).
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE);
        Font boldFont = font.deriveFont(Font.BOLD);
        var fm = scratch.createGraphics().getFontMetrics(font);
        int cellW = fm.charWidth('M');
        int cellH = fm.getHeight();

        int maxCols = lines.stream().mapToInt(StatsScreenshot::lineWidth).max().orElse(1);
        int width = maxCols * cellW + PADDING * 2;
        int height = lines.size() * cellH + PADDING * 2;

        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height),
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        int y = PADDING + fm.getAscent();
        for (List<Span> line : lines) {
            int x = PADDING;
            for (Span span : line) {
                g.setFont(span.bold() ? boldFont : font);
                g.setColor(span.color() != null ? span.color() : DEFAULT_FG);
                g.drawString(span.text(), x, y);
                x += span.text().length() * cellW;
            }
            y += cellH;
        }
        g.dispose();
        return image;
    }



    static Result copyPngToClipboard(BufferedImage image) throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "claude-code-screenshots");
        Files.createDirectories(tempDir);
        Path pngPath = tempDir.resolve("screenshot-" + System.currentTimeMillis() + ".png");
        ImageIO.write(image, "png", pngPath.toFile());
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (Strings.CS.contains(os, "mac")) {
                String escaped = pngPath.toString().replace("\\", "\\\\").replace("\"", "\\\"");
                return run(new String[]{"osascript", "-e",
                        "set the clipboard to (read (POSIX file \"" + escaped + "\") as «class PNGf»)"},
                    "Failed to copy to clipboard");
            }
            if (Strings.CS.contains(os, "win")) {
                String ps = "Add-Type -AssemblyName System.Windows.Forms; "
                    + "[System.Windows.Forms.Clipboard]::SetImage([System.Drawing.Image]::FromFile('"
                    + pngPath.toString().replace("'", "''") + "'))";
                return run(new String[]{"powershell", "-NoProfile", "-Command", ps},
                    "Failed to copy to clipboard");
            }

            Result xclip = run(new String[]{"xclip", "-selection", "clipboard",
                "-t", "image/png", "-i", pngPath.toString()}, null);
            if (xclip.success()) return xclip;
            Result xsel = run(new String[]{"xsel", "--clipboard", "--input",
                "--type", "image/png"}, null);
            if (xsel.success()) return xsel;
            return new Result(false,
                "Failed to copy to clipboard. Please install xclip or xsel: sudo apt install xclip");
        } finally {
            try {
                Files.deleteIfExists(pngPath);
            } catch (IOException _) {  }
        }
    }

    private static Result run(String[] command, String failurePrefix) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
            p.getOutputStream().close();
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, "Failed to copy to clipboard: timeout");
            }
            if (p.exitValue() == 0) {
                return new Result(true, "Screenshot copied to clipboard");
            }
            return new Result(false,
                (failurePrefix != null ? failurePrefix + ": " : "") + stderr.trim());
        } catch (Exception e) {
            return new Result(false, "Failed to copy to clipboard: " + e.getMessage());
        }
    }
}
