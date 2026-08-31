package com.claudecode.ui.vim;

/**
 * Vim text objects (inner/around).
 */
public record VimTextObject(Scope scope, Target target) {

    public enum Scope {
        INNER('i'),
        AROUND('a');

        private final char key;

        Scope(char key) {
            this.key = key;
        }

        public char key() {
            return key;
        }

        public static Scope fromChar(char c) {
            return switch (c) {
                case 'i' -> INNER;
                case 'a' -> AROUND;
                default -> null;
            };
        }
    }

    public enum Target {
        WORD('w'),
        BIG_WORD('W'),
        SINGLE_QUOTE('\''),
        DOUBLE_QUOTE('"'),
        BACKTICK('`'),
        PAREN('('),
        BRACKET('['),
        BRACE('{'),
        ANGLE('<');

        private final char key;

        Target(char key) {
            this.key = key;
        }

        public char key() {
            return key;
        }

        public static Target fromChar(char c) {
            return switch (c) {
                case 'w' -> WORD;
                case 'W' -> BIG_WORD;
                case '\'' -> SINGLE_QUOTE;
                case '"' -> DOUBLE_QUOTE;
                case '`' -> BACKTICK;
                case '(', ')', 'b' -> PAREN;
                case '[', ']' -> BRACKET;
                case '{', '}', 'B' -> BRACE;
                case '<', '>' -> ANGLE;
                default -> null;
            };
        }
    }
}
