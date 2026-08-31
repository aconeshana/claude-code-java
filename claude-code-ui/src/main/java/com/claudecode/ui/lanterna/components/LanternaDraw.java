package com.claudecode.ui.lanterna.components;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.claudecode.ui.lanterna.theme.LanternaTheme;


public final class LanternaDraw {

    private LanternaDraw() {}

    static final int LEFT_PAD    = 2;



    /** Horizontal {@code ─} divider spanning {@code cols} columns at {@code row}. */
    public static void divider(TextGUIGraphics g, int cols, int row) {
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(0, row, "─".repeat(Math.max(0, cols)));
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

/** Bold, left-aligned title at ({@code leftPad}, 1) — matches Dialog's title. */
    public static void title(TextGUIGraphics g, String title) {
        title(g, title, LEFT_PAD);
    }

/** Bold, left-aligned title at ({@code leftPad}, 1) — matches Dialog's title. */
    public static void title(TextGUIGraphics g, String title, int leftPad) {
        g.setForegroundColor(LanternaTheme.permission());
        g.enableModifiers(SGR.BOLD);
        g.putString(leftPad, 1, title);
        g.disableModifiers(SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

/** Dim footer text at ({@code leftPad}, {@code footerRow}) — matches Dialog's footer. */
    public static void footer(TextGUIGraphics g, String text, int leftPad, int footerRow) {
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(leftPad, footerRow, text);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
    }



    /**
     * Bare (borderless) search line: {@code ⌕ query█} when focused, dim
     * {@code ⌕ (type to search)} placeholder otherwise. Used by the raw
     * {@code TextGUIGraphics} panels (ConfigPanel, PermissionRulesTab).
     */
    public static void searchBoxLine(TextGUIGraphics g, int rowY, int leftPad,
                                     boolean focused, CharSequence query, int cursorOffset) {
        g.setForegroundColor(focused ? LanternaTheme.suggestion() : LanternaTheme.ghostText());
        if (focused) {
            int co = Math.min(cursorOffset, query.length());
            int column = leftPad;
            g.setCharacter(column++, rowY, '⌕');
            g.setCharacter(column++, rowY, ' ');
            for (int index = 0; index < co; index++) {
                g.setCharacter(column++, rowY, query.charAt(index));
            }
            g.setCharacter(column++, rowY, '█');
            for (int index = co; index < query.length(); index++) {
                g.setCharacter(column++, rowY, query.charAt(index));
            }
        } else {
            g.putString(leftPad, rowY, "⌕ (type to search)");
        }
    }

    /** Top border of a round-bordered search box: {@code ╭─…─╮} of {@code width} columns. */
    public static String borderedSearchBoxTop(int width) {
        return "╭" + "─".repeat(Math.max(0, width - 2)) + "╮";
    }

    /** Bottom border of a round-bordered search box: {@code ╰─…─╯} of {@code width} columns. */
    public static String borderedSearchBoxBottom(int width) {
        return "╰" + "─".repeat(Math.max(0, width - 2)) + "╯";
    }

    /**
     * Middle row of a round-bordered search box: {@code │ ⌕ before█after│} space-padded to exactly
     * {@code width} columns.
     */
    public static String borderedSearchBoxContent(boolean focused, String query,
                                                  int cursorOffset, int width) {
        if (focused) {
            int co = Math.min(cursorOffset, query.length());
            String before = query.substring(0, co);
            String after = query.substring(co);
            String inner = "│ ⌕ " + before + "█" + after;
            return inner + " ".repeat(Math.max(0, width - inner.length() - 1)) + "│";
        }
        String inner = "│ ⌕ " + (query.isEmpty() ? "Search…" : query);
        return inner + " ".repeat(Math.max(0, width - inner.length() - 1)) + "│";
    }



    /**
     * A selectable list row: {@code ❯ text} when selected (claude + input text,
     * bold), {@code "  text"} otherwise (dim). Used by the generic list panels
     * (WorkspaceTab, PermissionRulesTab).
     */
    public static void listItem(TextGUIGraphics g, int rowY, String text,
                                boolean selected, int leftPad) {
        if (selected) {
            g.setForegroundColor(LanternaTheme.claude());
            g.enableModifiers(SGR.BOLD);
            g.putString(leftPad, rowY, "❯ ");
        } else {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.disableModifiers(SGR.BOLD);
            g.putString(leftPad, rowY, "  ");
        }
        g.setForegroundColor(selected ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
        g.putString(leftPad + 2, rowY, text);
        g.disableModifiers(SGR.BOLD);
    }
}
