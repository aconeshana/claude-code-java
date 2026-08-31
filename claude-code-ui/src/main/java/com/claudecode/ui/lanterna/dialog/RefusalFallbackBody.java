package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.message.RefusalLearnMoreLink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The pause dialog's body, as styled runs laid out into terminal rows.
 */
final class RefusalFallbackBody {

    /**
     * A stretch of body text sharing one style. {@code hyperlinkUrl} is null for
     * everything but the collapsed {@code learn more} label.
     */
    record Run(String text, TextColor color, Set<SGR> modifiers, String hyperlinkUrl) {

        Run {
            Objects.requireNonNull(text, "text");
            modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
        }

        /** Whether {@code other} would draw identically, so the two can be merged. */
        private boolean sameStyle(Run other) {
            return Objects.equals(color, other.color)
                && modifiers.equals(other.modifiers)
                && Objects.equals(hyperlinkUrl, other.hyperlinkUrl);
        }
    }

    private static final Set<SGR> PLAIN = Set.of(SGR.BOLD);
    private static final Set<SGR> LINKED = Set.of(SGR.BOLD, SGR.UNDERLINE);

    private RefusalFallbackBody() {
    }

    /** The body as runs, before any wrapping. */
    static List<Run> runs(String content, boolean hyperlinksSupported) {
        TextColor color = LanternaTheme.statusCost();
        RefusalLearnMoreLink.Split split =
            RefusalLearnMoreLink.split(content, hyperlinksSupported);
        List<Run> runs = new ArrayList<>(3);
        if (!split.head().isEmpty()) {
            runs.add(new Run(split.head(), color, PLAIN, null));
        }
        if (split.linked()) {
            runs.add(new Run(RefusalLearnMoreLink.LINK_TEXT, color, LINKED, split.url()));
        }
        if (!split.tail().isEmpty()) {
            runs.add(new Run(split.tail(), color, PLAIN, null));
        }
        return List.copyOf(runs);
    }

    /** The body as rows no wider than {@code width}, keeping every run's style. */
    static List<List<Run>> lines(String content, boolean hyperlinksSupported, int width) {
        return wrap(runs(content, hyperlinksSupported), width);
    }

    /**
     * Greedy word wrap that carries each character's style with it. Words longer
     * than {@code width} are left intact on a line of their own, which is what
     * {@link DialogText#wrapWords} does to an over-long word — and what a bare
     * help url is, on the terminals that cannot link it.
     */
    static List<List<Run>> wrap(List<Run> runs, int width) {
        int columns = Math.max(1, width);
        List<List<Cell>> words = words(runs);
        List<List<Run>> lines = new ArrayList<>();
        List<Cell> line = new ArrayList<>();
        for (List<Cell> word : words) {
            if (word.isEmpty()) {
                // A hard line break: flush whatever is pending, even when empty,
                // so a blank line in the source stays a blank line.
                lines.add(merge(line));
                line = new ArrayList<>();
                continue;
            }
            boolean first = line.isEmpty();
            int projected = first ? word.size() : line.size() + 1 + word.size();
            if (!first && projected > columns) {
                lines.add(merge(line));
                line = new ArrayList<>();
                first = true;
            }
            if (!first) {
                // Re-insert the space this break did not consume, styled like the
                // character it followed so a link never gains a stray underline.
                line.add(new Cell(' ', line.getLast().run()));
            }
            line.addAll(word);
        }
        if (!line.isEmpty() || lines.isEmpty()) {
            lines.add(merge(line));
        }
        return List.copyOf(lines);
    }

    /** One character and the run it was styled by. */
    private record Cell(char character, Run run) { }

    /**
     * Splits the runs into words. An empty word marks a hard line break, which is
     * how {@code \n} inside a body survives wrapping.
     */
    private static List<List<Cell>> words(List<Run> runs) {
        List<List<Cell>> words = new ArrayList<>();
        List<Cell> word = new ArrayList<>();
        for (Run run : runs) {
            for (int i = 0; i < run.text().length(); i++) {
                char character = run.text().charAt(i);
                if (character == '\n') {
                    if (!word.isEmpty()) {
                        words.add(word);
                        word = new ArrayList<>();
                    }
                    words.add(List.of());
                } else if (character == ' ') {
                    if (!word.isEmpty()) {
                        words.add(word);
                        word = new ArrayList<>();
                    }
                } else {
                    word.add(new Cell(character, run));
                }
            }
        }
        if (!word.isEmpty()) {
            words.add(word);
        }
        return words;
    }

    /** Coalesces a row's cells back into the fewest runs that draw the same. */
    private static List<Run> merge(List<Cell> cells) {
        List<Run> merged = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Run style = null;
        for (Cell cell : cells) {
            if (style != null && !style.sameStyle(cell.run())) {
                merged.add(withText(style, text.toString()));
                text.setLength(0);
            }
            style = cell.run();
            text.append(cell.character());
        }
        if (style != null) {
            merged.add(withText(style, text.toString()));
        }
        return List.copyOf(merged);
    }

    private static Run withText(Run style, String text) {
        return new Run(text, style.color(), style.modifiers(), style.hyperlinkUrl());
    }
}
