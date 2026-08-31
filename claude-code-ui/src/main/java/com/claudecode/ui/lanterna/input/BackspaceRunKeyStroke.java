package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/** One terminal-drain run of unmodified Backspace keys. */
public final class BackspaceRunKeyStroke extends KeyStroke {

    private final int count;

    public BackspaceRunKeyStroke(int count) {
        super(KeyType.BACKSPACE);
        if (count < 2) throw new IllegalArgumentException("backspace run requires count >= 2");
        this.count = count;
    }

    public int count() {
        return count;
    }
}
