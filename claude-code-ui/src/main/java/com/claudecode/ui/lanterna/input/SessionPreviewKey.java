package com.claudecode.ui.lanterna.input;

import com.claudecode.core.annotation.Explanation;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/**
 * The session-preview trigger, shared by every list that can preview a
 * transcript: the resume picker ({@code SessionSelectorDialog}) and the project
 * drawer ({@code ProjectPanel}). Released 2.1.197 moved this binding from Ctrl+V
 * to Space; Ctrl+V stays live for muscle memory from older builds.
 *
 * <p>Single owner on purpose — a list that previews sessions under a different
 * key is indistinguishable, to the user, from a list that cannot preview at all.
 */
public final class SessionPreviewKey {

    private SessionPreviewKey() {}

    @Explanation("Space is the released 2.1.197 binding (older builds used Ctrl+V); "
        + "both are accepted so a user coming from either version finds the preview.")
    public static boolean isTrigger(KeyStroke key) {
        if (key == null || key.getKeyType() != KeyType.CHARACTER
                || key.getCharacter() == null) return false;
        char c = key.getCharacter();
        if (c == ' ') return !key.isCtrlDown() && !key.isAltDown();
        return key.isCtrlDown() && Character.toLowerCase(c) == 'v';
    }
}
