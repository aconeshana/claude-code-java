package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.input.KeyStroke;
import java.util.Objects;

/** One terminal-drain run of unmodified printable characters. */
public final class PlainTextKeyStroke extends KeyStroke {

    private final String text;

    public PlainTextKeyStroke(String text) {
        super(firstCharacter(text), false, false);
        this.text = text;
    }

    public String text() {
        return text;
    }

    private static char firstCharacter(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() < 2) {
            throw new IllegalArgumentException("plain text batch requires at least two characters");
        }
        return text.charAt(0);
    }
}
