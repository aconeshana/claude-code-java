package com.claudecode.ui.vim;

/**
 * Vim motions for cursor movement.
 */
public enum VimMotion {
    LEFT('h'),
    DOWN('j'),
    UP('k'),
    RIGHT('l'),
    WORD_FORWARD('w'),
    WORD_BACK('b'),
    WORD_END('e'),
    LINE_START('0'),
    FIRST_NON_BLANK('^'),
    LINE_END('$'),

    WORD_BIG_FORWARD('W'),
    WORD_BIG_BACK('B'),
    WORD_BIG_END('E'),

    LAST_LINE('G'),
    // g-prefixed motions, consumed via the 'g' prefix (NOT fromChar):
    FIRST_LINE('g'),    // gg
    DOWN_VISUAL('J'),   // gj (visual-line down; equals DOWN without wrap width)
    UP_VISUAL('K');     // gk (visual-line up; equals UP without wrap width)

    private final char key;

    VimMotion(char key) {
        this.key = key;
    }

    public char key() {
        return key;
    }

    public static VimMotion fromChar(char c) {
        return switch (c) {
            case 'h' -> LEFT;
            case 'j' -> DOWN;
            case 'k' -> UP;
            case 'l' -> RIGHT;
            case 'w' -> WORD_FORWARD;
            case 'b' -> WORD_BACK;
            case 'e' -> WORD_END;
            case '0' -> LINE_START;
            case '^' -> FIRST_NON_BLANK;
            case '$' -> LINE_END;
            case 'W' -> WORD_BIG_FORWARD;
            case 'B' -> WORD_BIG_BACK;
            case 'E' -> WORD_BIG_END;
            case 'G' -> LAST_LINE;
            // 'g' is a prefix, not a standalone motion, so it is intentionally
            // absent here (handled by the g-prefix state in VimStateMachine).
            default -> null;
        };
    }
}
