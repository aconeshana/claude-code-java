package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/** Shared upward/downward list navigation predicates supporting arrow keys and unmodified {@code k}/{@code j}. */
public final class VimKeyStrokes {

    private VimKeyStrokes() {}

    public static boolean isUp(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_UP
            || (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'k' && !key.isCtrlDown() && !key.isAltDown());
    }

    public static boolean isDown(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_DOWN
            || (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'j' && !key.isCtrlDown() && !key.isAltDown());
    }
}
