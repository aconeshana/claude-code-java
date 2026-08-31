package com.claudecode.ui.lanterna.components;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders the interactive {@code /pokemon} result as a Buddy-style terminal card.
 */
@Explanation("Interactive Pokémon rarity/stat card; UI-only and never enters model context.")
public final class PokemonCardRenderer {

    private static final int MAX_CARD_WIDTH = 52;
    private static final int MIN_CARD_WIDTH = 34;
    private static final int FULL_SPRITE_MIN_TERMINAL_WIDTH = 56;
    private static final int LABEL_WIDTH = 10;

    /** Appends a command echo followed by a responsive rarity-colored card. */
    public void show(MessagePanel panel, int terminalWidth, PokemonProfile profile) {
        if (panel == null || profile == null) return;
        panel.appendMixed(List.of(
            new MessagePanel.Segment("❯ ", LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("/pokemon", LanternaTheme.inputText(),
                null, null, Set.of(SGR.BOLD))));

        int available = Math.max(MIN_CARD_WIDTH, terminalWidth - 4);
        int width = Math.min(MAX_CARD_WIDTH, available);
        TextColor accent = rarityColor(profile.rarity());
        String horizontal = "─".repeat(Math.max(1, width - 2));
        panel.appendLine("  ┌" + horizontal + "┐", accent);

        appendHeader(panel, profile, width, accent);
        appendBody(panel, "", width, accent);
        appendBody(panel, profile.displayName(), width, accent,
            LanternaTheme.inputText(), Set.of(SGR.BOLD));
        appendBody(panel, profile.shiny() ? "✦ SHINY" : "", width, accent,
            profile.shiny() ? accent : LanternaTheme.welcomeDim(), Set.of());
        appendBody(panel, "", width, accent);

        LogoPanel.SpriteArtwork artwork = LogoPanel.spriteArtwork(profile);
        if (terminalWidth >= FULL_SPRITE_MIN_TERMINAL_WIDTH
                && artwork.width() <= width - 4) {
            appendSprite(panel, artwork, width, accent);
            appendBody(panel, "", width, accent);
        }

        int innerWidth = width - 4;
        int barWidth = Math.max(6, innerWidth - LABEL_WIDTH - 5);
        for (PokemonProfile.Stat stat : PokemonProfile.Stat.values()) {
            appendStat(panel, stat, profile.stats().getOrDefault(stat, 0),
                width, barWidth, accent);
        }
        panel.appendLine("  └" + horizontal + "┘", accent);
        panel.appendLine("", TextColor.ANSI.DEFAULT);
    }

    private static void appendSprite(MessagePanel panel, LogoPanel.SpriteArtwork artwork,
                                     int width, TextColor accent) {
        int innerWidth = width - 4;
        for (List<MessagePanel.Segment> spriteRow : artwork.rows()) {
            int spriteWidth = spriteRow.stream()
                .mapToInt(segment -> FormatUtils.displayWidth(segment.text()))
                .sum();
            int left = Math.max(0, (innerWidth - spriteWidth) / 2);
            int right = Math.max(0, innerWidth - spriteWidth - left);
            List<MessagePanel.Segment> segments = new ArrayList<>(spriteRow.size() + 4);
            segments.add(new MessagePanel.Segment("  │ ", accent));
            segments.add(defaultBackground(" ".repeat(left)));
            segments.addAll(spriteRow);
            segments.add(defaultBackground(" ".repeat(right)));
            segments.add(new MessagePanel.Segment(" │", accent));
            panel.appendMixed(segments);
        }
    }

    private static void appendHeader(MessagePanel panel, PokemonProfile profile,
                                     int width, TextColor accent) {
        String rarity = profile.stars() + "  " + title(profile.rarity());
        String right = "POKÉMON";
        int innerWidth = width - 4;
        int gap = Math.max(1, innerWidth - FormatUtils.displayWidth(rarity)
            - FormatUtils.displayWidth(right));
        String content = rarity + " ".repeat(gap) + right;
        if (FormatUtils.displayWidth(content) > innerWidth) {
            content = FormatUtils.truncateNoEllipsis(content, innerWidth);
        }
        List<MessagePanel.Segment> segments = new ArrayList<>();
        segments.add(new MessagePanel.Segment("  │ ", accent));
        segments.add(new MessagePanel.Segment(content, accent,
            null, null, Set.of(SGR.BOLD)));
        segments.add(new MessagePanel.Segment(
            " ".repeat(Math.max(0, innerWidth - FormatUtils.displayWidth(content))) + " │", accent));
        panel.appendMixed(segments);
    }

    private static void appendStat(MessagePanel panel, PokemonProfile.Stat stat,
                                   int value, int width, int barWidth, TextColor accent) {
        int bounded = Math.clamp(value, 0, 100);
        int filled = bounded == 0 ? 0 : Math.max(1, (int) Math.round(barWidth * bounded / 100.0));
        filled = Math.min(barWidth, filled);
        String label = stat.name();
        String labelPadding = " ".repeat(Math.max(1, LABEL_WIDTH - label.length()));
        String valueText = String.format(Locale.ROOT, "%3d", bounded);
        int used = label.length() + labelPadding.length() + barWidth + 1 + valueText.length();
        int trailing = Math.max(0, width - 4 - used);

        panel.appendMixed(List.of(
            new MessagePanel.Segment("  │ ", accent),
            new MessagePanel.Segment(label + labelPadding, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("█".repeat(filled), accent),
            new MessagePanel.Segment("░".repeat(barWidth - filled), LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(" " + valueText + " ".repeat(trailing), LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(" │", accent)));
    }

    private static void appendBody(MessagePanel panel, String text, int width, TextColor accent) {
        appendBody(panel, text, width, accent, LanternaTheme.welcomeDim(), Set.of());
    }

    private static void appendBody(MessagePanel panel, String text, int width, TextColor accent,
                                   TextColor color, Set<SGR> modifiers) {
        int innerWidth = width - 4;
        String content = FormatUtils.displayWidth(text) <= innerWidth
            ? text : FormatUtils.truncateNoEllipsis(text, innerWidth);
        panel.appendMixed(List.of(
            new MessagePanel.Segment("  │ ", accent),
            new MessagePanel.Segment(content, color, null, null, modifiers),
            new MessagePanel.Segment(
                " ".repeat(Math.max(0, innerWidth - FormatUtils.displayWidth(content))) + " │", accent)));
    }

    private static TextColor rarityColor(PokemonProfile.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> LanternaTheme.welcomeDim();
            case UNCOMMON -> LanternaTheme.toolSuccess();
            case RARE -> LanternaTheme.permission();
            case EPIC -> LanternaTheme.acceptPurple();
            case LEGENDARY -> LanternaTheme.autoYellow();
        };
    }

    private static String title(PokemonProfile.Rarity rarity) {
        String lower = rarity.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static MessagePanel.Segment defaultBackground(String text) {
        return new MessagePanel.Segment(text, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
    }
}
