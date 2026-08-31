package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class ItermImagePreviewWindowTest {

    @Test
    void imageSequencePositionsAndBoundsInlineImageInsideTui() {
        String sequence = ItermImagePreviewWindow.imageSequence(
            "aW1hZ2U=", "bmFtZS5wbmc=", 5, 80, 20);

        assertTrue(Strings.CS.startsWith(sequence, "\0337\033[3;3H\033]1337;File="));
        assertTrue(Strings.CS.contains(sequence, "size=5;inline=1;width=80;height=20"));
        assertTrue(Strings.CS.contains(sequence, "preserveAspectRatio=1:aW1hZ2U="));
        assertTrue(Strings.CS.endsWith(sequence, "\007\0338"));
    }
}
