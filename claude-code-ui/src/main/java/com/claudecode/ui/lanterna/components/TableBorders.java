package com.claudecode.ui.lanterna.components;

/**
 * Box-drawing glyphs and layout constants for the markdown table renderer.
 */
public final class TableBorders {

    private TableBorders() {}

    // ── Border glyphs (Unicode box-drawing) ─────────────────────────────────

    /** Top row corners and tee. */
    public static final char TOP_LEFT      = '┌';
    public static final char TOP_RIGHT     = '┐';
    public static final char TOP_TEE       = '┬';

    /** Middle (header separator) row. */
    public static final char MID_LEFT      = '├';
    public static final char MID_RIGHT     = '┤';
    public static final char MID_CROSS     = '┼';

    /** Bottom row corners and tee. */
    public static final char BOTTOM_LEFT   = '└';
    public static final char BOTTOM_RIGHT  = '┘';
    public static final char BOTTOM_TEE    = '┴';

    /** Vertical cell separator. */
    public static final char VERTICAL      = '│';

    /** Horizontal fill (used by all three border rows). */
    public static final char HORIZONTAL    = '─';

    // ── Layout constants ────────────────────────────────────────────────────

    /**
     * Reserved columns subtracted from the terminal width before laying out the
     * table — accounts for the parent message indentation and the right-side
     * clip race that otherwise causes flicker on resize.
     */
    public static final int SAFETY_MARGIN = 4;

    /** Minimum cell width — anything narrower triggers vertical (key/value) layout. */
    public static final int MIN_COLUMN_WIDTH = 3;

    /**
     * Maximum row height before the renderer falls back to vertical layout.
     * If any cell would wrap beyond this many lines, the table is re-rendered
     * with each row split into key/value pairs.
     */
    public static final int MAX_ROW_LINES = 4;

    /**
     * Border overhead per column: 1 char for the leading {@code │}, 2 for cell
     * padding, 1 for the trailing border = 3 per column + 1 for the rightmost.
     * Use this to compute available cell width given terminal width.
     */
    public static int computeBorderOverhead(int numCols) {
        return 1 + numCols * 3;
    }

    /** Returns the four border characters for the requested row position. */
    public static char[] borderRowFor(Row row) {
        return switch (row) {
            case TOP    -> new char[]{TOP_LEFT,    HORIZONTAL, TOP_TEE,    TOP_RIGHT};
            case MIDDLE -> new char[]{MID_LEFT,    HORIZONTAL, MID_CROSS,  MID_RIGHT};
            case BOTTOM -> new char[]{BOTTOM_LEFT, HORIZONTAL, BOTTOM_TEE, BOTTOM_RIGHT};
        };
    }

    public enum Row { TOP, MIDDLE, BOTTOM }
}
