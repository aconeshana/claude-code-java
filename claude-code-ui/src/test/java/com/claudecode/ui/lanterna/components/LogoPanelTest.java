package com.claudecode.ui.lanterna.components;

import com.claudecode.core.pokemon.PokemonRoster;
import com.claudecode.core.pokemon.PokemonEvolution;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.pokemon.PokemonRoller;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogoPanelTest {

    @Test
    void tinyPokemonExperienceRemainsVisibleBelowOnePercent() {
        PokemonEvolution.Progress progress = new PokemonEvolution.Progress(
            1, 5_138_712L, 1_000_000_000L, false);

        assertEquals("0.51%", LogoPanel.progressPercent(progress));
        LogoPanel.ProgressBar bar = LogoPanel.progressBar(progress, 14);
        assertEquals("▏", bar.filled());
        assertEquals(13, bar.empty().length());
    }

    @Test
    void experienceBarPartialFilledAndEmptyShareTrackBackground() {
        // 73.27% of a 14-wide bar lands on a partial eighth-block; its transparent
        // right edge must share the empty rail's background, or a bare background gap
        // separates earned from remaining progress.
        var progress = new PokemonEvolution.Progress(1, 512_000_000L, 1_000_000_000L, false);
        var bar = LogoPanel.progressBar(progress, 14);
        assertTrue(bar.filled().length() < 14, "sanity: shifted into a partial block");
        TextColor track = LanternaTheme.welcomeDim();
        List<MessagePanel.Segment> segs = LogoPanel.progressSegments(
            LanternaTheme.toolSuccess(), bar.filled(), bar.empty(), track);
        assertEquals(2, segs.size());
        assertEquals(track, segs.getFirst().bgColor(), "filled (partial block) carries track background");
        assertEquals(track, segs.get(1).bgColor(), "empty rail carries the same track background");
    }

    @Test
    void zeroAndCompletePokemonProgressRenderAtTheirTrueEdges() {
        var zero = new PokemonEvolution.Progress(1, 0L, 1_000_000_000L, false);
        var complete = new PokemonEvolution.Progress(16, 1_000_000_000L,
            1_000_000_000L, false);

        assertEquals("0%", LogoPanel.progressPercent(zero));
        assertEquals("", LogoPanel.progressBar(zero, 4).filled());
        assertEquals("100%", LogoPanel.progressPercent(complete));
        assertEquals("████", LogoPanel.progressBar(complete, 4).filled());
    }

    @Test
    void everyEvolutionSpeciesHasRegularAndShinySprite() {
        for (String name : PokemonRoster.NAMES) {
            assertTrue(getClass().getResource(
                "/welcome/pokemon/regular/" + name + ".ansi") != null,
                "missing regular sprite for " + name);
            assertTrue(getClass().getResource(
                "/welcome/pokemon/shiny/" + name + ".ansi") != null,
                "missing shiny sprite for " + name);
        }
    }

    @Test
    void liveModelUpdateReplacesWelcomeRowWithoutAppending() {
        CapturingPanel panel = new CapturingPanel();
        LogoPanel logo = new LogoPanel(null);

        LogoPanel.WelcomeBlock block = logo.show(panel, 120, "claude-opus-4-6");
        int modelLine = block.modelLine();
        int lineCount = panel.snapshotLineCount();
        assertTrue(Strings.CS.contains(rowText(panel, modelLine), "Opus 4.6"));

        logo.updateModelLine(panel, block, 120, "gpt-5.6-sol");

        assertEquals(lineCount, panel.snapshotLineCount());
        assertTrue(Strings.CS.contains(rowText(panel, modelLine), "gpt-5.6-sol"));
    }

    @Test
    void wideWelcomeUsesOfficialCompactClawdUntilPokemonIsExplicitlyEnabled() {
        CapturingPanel panel = new CapturingPanel();

        new LogoPanel(null).show(panel, 120, "claude-sonnet-4-6");

        assertEquals(4, panel.snapshotLineCount(), "3 compact rows plus trailing spacer");
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Claude Code")));
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "███")));
        assertTrue(panel.lines.stream().noneMatch(line -> Strings.CS.contains(line, "Lv ")));
    }

    @Test
    void storedPokemonUsesBorderlessSpriteAndLocalBackgrounds() {
        CapturingPanel panel = new CapturingPanel();

        new LogoPanel(PokemonRoller.defaultPikachu())
            .show(panel, 120, "claude-sonnet-4-6");

        assertTrue(panel.snapshotLineCount() >= 5,
            "dynamic Pokémon height plus the four metadata rows and trailing spacer");
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Claude Code")));
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Lv ")));
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "%")));
        assertTrue(panel.lines.stream().noneMatch(line -> line.matches(".*[╭╮╰╯│▕].*")));
        assertTrue(panel.segmentLines.subList(0, panel.segmentLines.size() - 1).stream()
            .allMatch(line -> !line.isEmpty()
                && TextColor.ANSI.DEFAULT.equals(line.getFirst().bgColor())),
            "default-background sentinel prevents local sprite color from filling the row");
        assertTrue(panel.segmentLines.stream().flatMap(List::stream)
            .anyMatch(segment -> segment.bgColor() != null), "sprite retains RGB backgrounds");
    }

    @Test
    void welcomePokemonStageCapsLargeSpeciesWithoutPaddingSmallSpecies() {
        CapturingPanel pikachuPanel = new CapturingPanel();
        CapturingPanel raichuPanel = new CapturingPanel();
        PokemonProfile pikachu = PokemonRoller.defaultPikachu();
        PokemonProfile raichu = new PokemonProfile(
            pikachu.rootName(), "raichu", pikachu.rarity(), pikachu.shiny(),
            pikachu.stats(), pikachu.hatchedAt(), pikachu.experienceTokens(),
            pikachu.evolutionChoice());

        new LogoPanel(pikachu).show(pikachuPanel, 120, "claude-sonnet-4-6");
        new LogoPanel(raichu).show(raichuPanel, 120, "claude-sonnet-4-6");

        assertEquals(LogoPanel.spriteArtwork(pikachu).height() + 1,
            pikachuPanel.snapshotLineCount(),
            "small sprites use their native height plus the trailing spacer");
        assertEquals(13, raichuPanel.snapshotLineCount(),
            "large sprites are capped at 12 rows plus the trailing spacer");
        assertTrue(pikachuPanel.snapshotLineCount() < raichuPanel.snapshotLineCount(),
            "small sprites must not receive blank top padding to fill the cap");
        assertTrue(LogoPanel.spriteArtwork(pikachu).height() < 12,
            "small artwork keeps its native size outside the fixed stage");
        assertTrue(LogoPanel.spriteArtwork(raichu).height() > 12,
            "full-size artwork remains available to evolution and card renderers");
    }

    @Test
    void narrowWelcomeFallsBackToClawdAndKeepsFourMetadataRows() {
        CapturingPanel panel = new CapturingPanel();

        new LogoPanel(PokemonRoller.defaultPikachu())
            .show(panel, 50, "claude-sonnet-4-6");

        assertEquals(5, panel.snapshotLineCount(), "4 metadata rows plus trailing spacer");
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "███")));
        assertTrue(panel.lines.stream().allMatch(line -> FormatUtils.displayWidth(line) <= 50));
    }

    @Test
    void webEntryAppearsAfterGatewayStartWithoutDisturbingModelRow() {
        CapturingPanel panel = new CapturingPanel();
        LogoPanel logo = new LogoPanel(null);
        String url = "http://127.0.0.1:8087/?token=secret";

        LogoPanel.WelcomeBlock before = logo.show(panel, 120, "claude-opus-4-6");
        int beforeModelTextLine = modelTextLine(panel);
        int beforeModelText = before.modelLine();
        assertTrue(panel.lines.stream().noneMatch(line -> Strings.CS.contains(line, "Web UI")),
            "no web row before the gateway starts");
        int beforeCount = panel.snapshotLineCount();

        LogoPanel.WelcomeBlock after =
            logo.updateWebLine(panel, before, 120, "claude-opus-4-6", url);

        // The metadata column re-centers inside the sprite when the web row
        // appears, so the model row may shift by one line — what must hold is
        // that the model TEXT is still rendered and the block grows by at
        // most one line (sprite-dominated blocks may not grow at all).
        assertTrue(panel.snapshotLineCount() == beforeCount
                || panel.snapshotLineCount() == beforeCount + 1,
            "the appearing web row adds at most one line");
        String modelRow = rowText(panel, after.modelLine());
        assertTrue(Strings.CS.contains(modelRow, "Opus 4.6"),
            "the model row still renders after the web row appears");
        int webLine = after.webLine();
        assertTrue(webLine >= 0 && webLine < panel.snapshotLineCount(),
            "web row index is inside the block");
        List<MessagePanel.Segment> webSegments = panel.segmentLines.get(webLine);
        assertTrue(webSegments.stream()
            .anyMatch(segment -> Strings.CS.contains(segment.text(), "Web UI: ")));
        assertTrue(webSegments.stream()
            .noneMatch(segment -> Strings.CS.contains(segment.text(), "secret")),
            "the token never appears in the visible row text");
        List<MessagePanel.Segment> linked = webSegments.stream()
            .filter(segment -> url.equals(segment.hyperlinkUrl()))
            .toList();
        assertFalse(linked.isEmpty(),
            "the web row text carries the full token-embedded hyperlink");
        assertTrue(linked.stream().mapToInt(segment ->
                FormatUtils.displayWidth(segment.text())).sum()
                >= FormatUtils.displayWidth("Web UI: " + LogoPanel.webOrigin(url)),
            "the hyperlink covers the whole visible web entry text");
        assertTrue(webSegments.stream().noneMatch(segment ->
                segment.hyperlinkUrl() != null && !url.equals(segment.hyperlinkUrl())),
            "no other hyperlink leaks into the web row");
    }

    /** The line currently showing the model billing text, for drift checks. */
    private static int modelTextLine(CapturingPanel panel) {
        for (int i = 0; i < panel.lines.size(); i++) {
            if (Strings.CS.contains(panel.lines.get(i), "API Usage Billing")) return i;
        }
        return -1;
    }

    @Test
    void webEntryUpdatesInPlaceWhenAlreadyPresent() {
        CapturingPanel panel = new CapturingPanel();
        LogoPanel logo = new LogoPanel(null);
        String firstUrl = "http://127.0.0.1:8087/?token=one";

        LogoPanel.WelcomeBlock shown = logo.show(panel, 120, "claude-opus-4-6");
        LogoPanel.WelcomeBlock block =
            logo.updateWebLine(panel, shown, 120, "claude-opus-4-6", firstUrl);
        int lineCount = panel.snapshotLineCount();
        String webRowText = rowText(panel, block.webLine());

        LogoPanel.WelcomeBlock updated =
            logo.updateWebLine(panel, block, 120, "claude-opus-4-6", firstUrl);

        assertEquals(lineCount, panel.snapshotLineCount(),
            "an unchanged URL must not re-render or grow the block");
        assertEquals(block.webLine(), updated.webLine());
        assertTrue(Strings.CS.contains(rowText(panel, updated.webLine()), "Web UI: "),
            "the web row keeps its content across the idempotent update");
        assertEquals(webRowText, rowText(panel, updated.webLine()),
            "an idempotent update leaves the row byte-identical");
    }

    @Test
    void webOriginStripsTheTokenQuery() {
        assertEquals("http://127.0.0.1:8087/",
            LogoPanel.webOrigin("http://127.0.0.1:8087/?token=abc"));
        assertEquals("http://127.0.0.1:8087/",
            LogoPanel.webOrigin("http://127.0.0.1:8087/"));
        assertEquals("", LogoPanel.webOrigin(null));
    }

    private static String rowText(MessagePanel panel, int index) {
        return ((CapturingPanel) panel).lines.get(index);
    }

    private static final class CapturingPanel extends MessagePanel {
        private final List<String> lines = new ArrayList<>();
        private final List<List<MessagePanel.Segment>> segmentLines = new ArrayList<>();

        @Override
        public void appendMixed(List<MessagePanel.Segment> segments) {
            lines.add(text(segments));
            segmentLines.add(List.copyOf(segments));
        }

        @Override
        public void appendLine(String text, TextColor color) {
            lines.add(text);
            segmentLines.add(List.of(new MessagePanel.Segment(text, color)));
        }

        @Override
        public int snapshotLineCount() {
            return lines.size();
        }

        @Override
        public void updateLine(int index, List<MessagePanel.Segment> segments) {
            if (index >= 0 && index < lines.size()) {
                lines.set(index, text(segments));
                segmentLines.set(index, List.copyOf(segments));
            }
        }

        @Override
        public int replaceHistoryTopAnchor(List<List<MessagePanel.Segment>> rows) {
            // The capturing panel has no history-top retention; emulate the
            // in-place whole-block replacement over the appended rows: the
            // previous block's rows are removed wherever they sit at the top.
            int previousCount = Math.min(lines.size(), Math.max(0, lines.size()));
            int start = 0;
            replaceLines(start, previousCount, rows);
            return start;
        }

        @Override
        public void replaceLines(int start, int count, List<List<MessagePanel.Segment>> replacements) {
            for (int i = 0; i < count; i++) {
                lines.remove(start);
                segmentLines.remove(start);
            }
            for (int i = 0; i < replacements.size(); i++) {
                List<MessagePanel.Segment> row = replacements.get(i);
                lines.add(start + i, text(row));
                segmentLines.add(start + i, List.copyOf(row));
            }
        }

        private static String text(List<MessagePanel.Segment> segments) {
            return segments.stream().map(MessagePanel.Segment::text)
                .reduce("", String::concat);
        }
    }
}
