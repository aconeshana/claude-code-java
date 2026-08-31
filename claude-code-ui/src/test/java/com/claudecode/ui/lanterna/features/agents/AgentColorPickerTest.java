package com.claudecode.ui.lanterna.features.agents;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentColorPicker}.
 */
class AgentColorPickerTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(AgentColorPicker p, KeyStroke k) {
        p.handleKey(k, new AtomicBoolean(true));
    }

    @Test
    void activate_seedsSelectionFromCurrentColor() {
        AgentColorPicker p = new AgentColorPicker();
        p.activate("cyan", "my-agent", _ -> {}, () -> {});
        assertEquals("cyan", p.selectedColor());
    }

    @Test
    void activate_nullColor_seedsAutomatic() {
        AgentColorPicker p = new AgentColorPicker();
        p.activate(null, "my-agent", _ -> {}, () -> {});
        assertNull(p.selectedColor());
    }

    @Test
    void navigation_wrapsAround() {
        AgentColorPicker p = new AgentColorPicker();
        p.activate(null, "my-agent", _ -> {}, () -> {});
        send(p, UP); // wraps to last option
        assertEquals("cyan", p.selectedColor());
    }

    @Test
    void enter_confirmsSelectedColor() {
        AgentColorPicker p = new AgentColorPicker();
        String[] result = {"unset"};
        p.activate(null, "my-agent", c -> result[0] = c, () -> {});
        send(p, DOWN); // -> "red"
        send(p, ENTER);
        assertEquals("red", result[0]);
        assertFalse(p.isPickerVisible());
    }

    @Test
    void esc_cancelsWithoutConfirming() {
        AgentColorPicker p = new AgentColorPicker();
        boolean[] confirmed = {false};
        boolean[] cancelled = {false};
        p.activate(null, "my-agent", _ -> confirmed[0] = true, () -> cancelled[0] = true);
        send(p, ESC);
        assertFalse(confirmed[0]);
        assertTrue(cancelled[0]);
        assertFalse(p.isPickerVisible());
    }

    @Test
    void inactivePicker_ignoresKeys() {
        AgentColorPicker p = new AgentColorPicker();
        AtomicBoolean deliver = new AtomicBoolean(true);
        p.handleKey(ENTER, deliver);
        assertTrue(deliver.get(), "an inactive picker must not consume keys");
    }

    @Test
    void releasedCopyUsesAutomaticColorAndAtNamePreview() {
        AgentColorPicker p = new AgentColorPicker();
        p.activate(null, "my-agent", _ -> {}, () -> {});

        assertEquals("Automatic color", p.optionLabel(0));
        assertEquals("Preview:  @my-agent ", p.previewText());
        String rendered = render(p);
        assertTrue(rendered.contains("Create new agent"), rendered);
        assertTrue(rendered.contains("Choose background color"), rendered);
        assertTrue(rendered.contains("Automatic color"), rendered);
        assertTrue(rendered.contains("Preview:  @my-agent "), rendered);
        assertTrue(rendered.contains("Esc go back"), rendered);
    }

    private static String render(AgentColorPicker picker) {
        TerminalSize size = new TerminalSize(80, 20);
        picker.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        picker.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        return imageText(image, size);
    }

    private static String imageText(BasicTextImage image, TerminalSize size) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
