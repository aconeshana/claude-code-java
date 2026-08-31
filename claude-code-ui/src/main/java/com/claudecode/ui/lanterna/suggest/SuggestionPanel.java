package com.claudecode.ui.lanterna.suggest;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dropdown suggestion panel shown below the input divider when the user types {@code /} (command
 * autocomplete) or {@code @} (file mention).
 */
public class SuggestionPanel extends AbstractComponent<SuggestionPanel> {

    public enum Layout { COMMAND, UNIFIED, PATH }


    public record Suggestion(String primary, String description, String icon, Layout layout) {
        public Suggestion {
            primary = primary == null ? "" : primary;
            description = description == null ? "" : description;
            icon = icon == null ? "" : icon;
            layout = layout == null ? Layout.COMMAND : layout;
        }

        public Suggestion(String primary, String description, String icon) {
            this(primary, description, icon,
                StringUtils.isEmpty(icon) ? Layout.COMMAND : Layout.UNIFIED);
        }

        /** Backward-compatible constructor with no icon. */
        public Suggestion(String primary, String description) {
            this(primary, description, "");
        }

        public static Suggestion path(String primary) {
            return new Suggestion(primary, "", "", Layout.PATH);
        }
    }

    /** Maximum simultaneously rendered rows; the full result set remains navigable. */
    public static final int MAX_VISIBLE = 15;

    /** Guards the immutable suggestion snapshot and selection state during paint/input races. */
    private final Object stateLock = new Object();
    private List<Suggestion> suggestions = List.of();
    private int selectedIndex;
    private int lastTermWidth = 80;
    private int lastCommandColumnWidth = -1;
    private Consumer<Suggestion> onAccept;

    public void setOnAccept(Consumer<Suggestion> cb) {
        synchronized (stateLock) {
            this.onAccept = cb;
        }
    }

    @Override public boolean isVisible() {
        synchronized (stateLock) {
            return !suggestions.isEmpty();
        }
    }

    public void setSuggestions(List<Suggestion> items, int terminalWidth) {
        setSuggestions(items, terminalWidth, -1);
    }

    /**
     * Populates the dropdown using an optional stable command-name column width.
     * Identical snapshots are ignored so duplicate query notifications do not
     * force another Lanterna paint/layout pass.
     */
    public void setSuggestions(List<Suggestion> items, int terminalWidth,
                               int commandColumnWidth) {
        synchronized (stateLock) {
            List<Suggestion> next = items == null || items.isEmpty()
                ? List.of()
                : List.copyOf(items);
            if (suggestions.equals(next)
                    && lastTermWidth == terminalWidth
                    && lastCommandColumnWidth == commandColumnWidth) {
                return;
            }
            suggestions = next;
            lastTermWidth = Math.max(1, terminalWidth);
            lastCommandColumnWidth = commandColumnWidth;
            selectedIndex = 0;
            invalidate();
        }
    }

    public void hide() {
        synchronized (stateLock) {
            if (suggestions.isEmpty()) return;
            suggestions = List.of();
            selectedIndex = 0;
            invalidate();
        }
    }

    public void moveUp() {
        synchronized (stateLock) {
            if (suggestions.isEmpty()) return;
            selectedIndex = (selectedIndex - 1 + suggestions.size()) % suggestions.size();
            invalidate();
        }
    }

    public void moveDown() {
        synchronized (stateLock) {
            if (suggestions.isEmpty()) return;
            selectedIndex = (selectedIndex + 1) % suggestions.size();
            invalidate();
        }
    }

    public Suggestion acceptSelected() {
        synchronized (stateLock) {
            if (suggestions.isEmpty()) return null;
            Suggestion selected = suggestions.get(selectedIndex);
            hide();
            if (onAccept != null) onAccept.accept(selected);
            return selected;
        }
    }

    public Suggestion peekSelected() {
        synchronized (stateLock) {
            return suggestions.isEmpty() ? null : suggestions.get(selectedIndex);
        }
    }

    @Override
    protected TerminalSize calculatePreferredSize() {
        synchronized (stateLock) {
            if (suggestions.isEmpty()) return TerminalSize.of(0, 0);
            return new TerminalSize(lastTermWidth, Math.min(suggestions.size(), MAX_VISIBLE));
        }
    }

    @Override
    protected ComponentRenderer<SuggestionPanel> createDefaultRenderer() {
        return new SuggestionRenderer();
    }

    private static final class SuggestionRenderer implements ComponentRenderer<SuggestionPanel> {
        @Override
        public TerminalSize getPreferredSize(SuggestionPanel component) {
            return component.calculatePreferredSize();
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, SuggestionPanel component) {
            synchronized (component.stateLock) {
                graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
                graphics.fill(' ');
                int columns = graphics.getSize().getColumns();
                if (columns <= 0) return;
                int startIndex = component.visibleStartIndex();
                String[] rendered = component.renderedRows(columns);
                int rowCount = Math.min(rendered.length, graphics.getSize().getRows());
                for (int i = 0; i < rowCount; i++) {
                    graphics.setForegroundColor(i + startIndex == component.selectedIndex
                        ? LanternaTheme.suggestion()
                        : LanternaTheme.welcomeDim());
                    graphics.putString(0, i, FormatUtils.truncate(rendered[i], columns));
                }
            }
        }
    }

    String[] renderedRows(int availableWidth) {
        int width = availableWidth > 0 ? availableWidth : lastTermWidth;
        int startIndex = visibleStartIndex();
        int endIndex = Math.min(startIndex + MAX_VISIBLE, suggestions.size());
        String[] rows = new String[endIndex - startIndex];
        for (int i = startIndex; i < endIndex; i++) {
            Suggestion suggestion = suggestions.get(i);
            rows[i - startIndex] = switch (suggestion.layout()) {
                case COMMAND -> renderCommandRow(suggestion, width);
                case UNIFIED -> renderUnifiedRow(suggestion, width);
                case PATH -> renderPathRow(suggestion);
            };
        }
        return rows;
    }

    /**
     * Keeps the selected result near the middle of the viewport while allowing navigation across the
     * complete suggestion list.
     */
    private int visibleStartIndex() {
        if (suggestions.size() <= MAX_VISIBLE) return 0;
        int centered = selectedIndex - MAX_VISIBLE / 2;
        return Math.max(0, Math.min(centered, suggestions.size() - MAX_VISIBLE));
    }

    /** Path suggestions have no command-name column and consume the full row. */
    private static String renderPathRow(Suggestion suggestion) {
        return "  " + suggestion.primary();
    }


    private String renderCommandRow(Suggestion suggestion, int width) {
        int maxNameWidth = Math.max(1, (int) Math.floor(width * 0.4));
        int naturalWidth = FormatUtils.displayWidth(suggestion.primary()) + 5;
        int primaryWidth = Math.min(
            lastCommandColumnWidth > 0 ? lastCommandColumnWidth : naturalWidth,
            maxNameWidth);
        int textLimit = Math.max(0, primaryWidth - 2);
        String primary = FormatUtils.displayWidth(suggestion.primary()) > textLimit
            ? FormatUtils.truncate(suggestion.primary(), textLimit)
            : suggestion.primary();
        primary += " ".repeat(Math.max(0, primaryWidth - FormatUtils.displayWidth(primary)));

        int descriptionWidth = Math.max(0, width - primaryWidth - 4);
        String description = descriptionWidth == 0 ? ""
            : FormatUtils.truncate(normalizeDescription(suggestion.description()),
                descriptionWidth);
        return "  " + primary + description;
    }

    /** Unified file/resource rows retain their real icon, after the same two-column inset. */
    private static String renderUnifiedRow(Suggestion suggestion, int width) {
        String primary = suggestion.primary();
        String separator = suggestion.description().isEmpty() ? "" : " – ";
        int fixedWidth = 2 + FormatUtils.displayWidth(suggestion.icon()) + 1
            + FormatUtils.displayWidth(primary) + FormatUtils.displayWidth(separator) + 2;
        int descriptionWidth = Math.max(0, width - fixedWidth);
        String description = descriptionWidth == 0 ? ""
            : FormatUtils.truncate(normalizeDescription(suggestion.description()),
                descriptionWidth);
        return "  " + suggestion.icon() + " " + primary + separator + description;
    }

    private static String normalizeDescription(String description) {
        return description == null ? "" : description.replaceAll("\\s+", " ");
    }
}
