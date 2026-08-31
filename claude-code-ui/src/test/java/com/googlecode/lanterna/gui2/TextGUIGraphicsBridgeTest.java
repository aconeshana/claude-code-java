package com.googlecode.lanterna.gui2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import org.junit.jupiter.api.Test;

class TextGUIGraphicsBridgeTest {

    @Test
    void skipInitialFillSuppressesOnlyTheBasePaneClear() {
        BasicTextImage image = new BasicTextImage(new TerminalSize(3, 1));
        image.newTextGraphics().fill('x');
        TextGUIGraphics graphics = TextGUIGraphicsBridge.wrapSkippingInitialFill(
            null, image.newTextGraphics());

        graphics.fill(' ');
        assertEquals("xxx", row(image));

        graphics.fill('y');
        assertEquals("yyy", row(image));
    }

    private static String row(BasicTextImage image) {
        StringBuilder row = new StringBuilder(image.getSize().getColumns());
        for (int column = 0; column < image.getSize().getColumns(); column++) {
            row.append(image.getCharacterAt(column, 0).getCharacterString());
        }
        return row.toString();
    }
}
