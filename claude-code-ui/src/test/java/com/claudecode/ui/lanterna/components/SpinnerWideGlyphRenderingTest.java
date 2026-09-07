package com.claudecode.ui.lanterna.components;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for double-width glyphs on the spinner line and its tip row.
 *
 * <p>Writing one character per string index blanks every CJK cell, because Lanterna clears a
 * wide character when a later write lands on its trailing half. A Chinese verb — which the
 * spinner adopts from an in-progress task's {@code activeForm} — then rendered as blanks.
 */
class SpinnerWideGlyphRenderingTest {

    private static final String CHINESE_VERB = "审查并修复自动总结差异";

    @Test
    void wideVerbKeepsEveryGlyphOnTheSpinnerLine() {
        SpinnerComponent spinner = new SpinnerComponent();
        try {
            spinner.setOverrideMessage(CHINESE_VERB);
            spinner.start("Thinking");
            String line = drawRow(spinner, 1);
            assertTrue(Strings.CS.contains(line, CHINESE_VERB + "…"), "spinner line was: " + line);
        } finally {
            spinner.stop();
        }
    }

    @Test
    void wideTipKeepsEveryGlyphBeneathTheSpinner() {
        SpinnerComponent spinner = new SpinnerComponent();
        try {
            spinner.start("Thinking");
            spinner.setSpinnerTip("循环审查 UI 与代码行为差异");
            String tip = drawRow(spinner, 2);
            assertTrue(Strings.CS.contains(tip, "循环审查 UI 与代码行为差异"), "tip row was: " + tip);
        } finally {
            spinner.stop();
        }
    }

    @Test
    void preferredWidthCountsWideVerbInTerminalColumns() {
        SpinnerComponent spinner = new SpinnerComponent();
        try {
            spinner.setOverrideMessage(CHINESE_VERB);
            spinner.start("Thinking");
            assertTrue(spinner.calculatePreferredSize().getColumns() > CHINESE_VERB.length() * 2,
                "preferred width must reserve two columns per wide glyph");
        } finally {
            spinner.stop();
        }
    }

    /** Draws the spinner into an off-screen image and reads back one row as displayed text. */
    private static String drawRow(SpinnerComponent spinner, int row) {
        BasicTextImage image = new BasicTextImage(new TerminalSize(120, 6));
        image.newTextGraphics().fill(' ');
        spinner.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < image.getSize().getColumns(); column++) {
            TextCharacter cell = image.getCharacterAt(column, row);
            text.append(cell.getCharacterString());
            // A wide cell owns the following column too; skip its blank trailing half.
            if (cell.isDoubleWidth()) column++;
        }
        return text.toString();
    }
}
