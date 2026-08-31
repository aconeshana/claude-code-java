package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.pokemon.PokemonRoller;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class PokemonCardRendererTest {

    @Test
    void rendersCommandEchoCardAndFiveStatBars() {
        CapturingPanel panel = new CapturingPanel();

        new PokemonCardRenderer().show(panel, 80, PokemonRoller.defaultPikachu());

        assertTrue(Strings.CS.contains(panel.lines.getFirst(), "/pokemon"));
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "★  Common")));
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Pikachu")));
        assertEquals(5, panel.lines.stream().filter(line ->
            Arrays.stream(PokemonProfile.Stat.values())
                .anyMatch(stat -> Strings.CS.contains(line, stat.name()))).count());
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "█")));
        assertTrue(panel.segmentLines.stream().flatMap(List::stream)
            .anyMatch(segment -> segment.bgColor() != null
                && !TextColor.ANSI.DEFAULT.equals(segment.bgColor())),
            "wide cards include the full-color Pokémon sprite");
    }

    @Test
    void narrowsCardWithoutDroppingStats() {
        CapturingPanel panel = new CapturingPanel();

        new PokemonCardRenderer().show(panel, 38, PokemonRoller.defaultPikachu());

        assertTrue(panel.lines.stream().allMatch(line ->
            FormatUtils.displayWidth(line) <= 38));
        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.contains(line, "WISDOM")));
        assertTrue(panel.segmentLines.stream().flatMap(List::stream)
            .noneMatch(segment -> segment.bgColor() != null
                && !TextColor.ANSI.DEFAULT.equals(segment.bgColor())),
            "narrow cards omit the full sprite instead of overflowing");
    }

    @Test
    void rarityControlsCardAccentWhileShinyControlsSpriteVariant() {
        PokemonProfile base = PokemonRoller.defaultPikachu();
        PokemonProfile rare = new PokemonProfile(
            base.rootName(), base.name(), PokemonProfile.Rarity.RARE, false,
            new EnumMap<>(base.stats()), base.hatchedAt(), base.experienceTokens(),
            base.evolutionChoice());
        CapturingPanel commonPanel = new CapturingPanel();
        CapturingPanel rarePanel = new CapturingPanel();

        new PokemonCardRenderer().show(commonPanel, 80, base);
        new PokemonCardRenderer().show(rarePanel, 80, rare);

        TextColor commonBorder = commonPanel.segmentLines.get(1).getFirst().color();
        TextColor rareBorder = rarePanel.segmentLines.get(1).getFirst().color();
        assertNotEquals(commonBorder, rareBorder, "rarity changes the border, stars, and filled stat-bar accent");
    }

    private static final class CapturingPanel extends MessagePanel {
        private final List<String> lines = new ArrayList<>();
        private final List<List<MessagePanel.Segment>> segmentLines = new ArrayList<>();

        @Override public void appendMixed(List<MessagePanel.Segment> segments) {
            lines.add(segments.stream().map(MessagePanel.Segment::text)
                .reduce("", String::concat));
            segmentLines.add(List.copyOf(segments));
        }

        @Override public void appendLine(String text, TextColor color) {
            lines.add(text);
            segmentLines.add(List.of(new MessagePanel.Segment(text, color)));
        }
    }
}
