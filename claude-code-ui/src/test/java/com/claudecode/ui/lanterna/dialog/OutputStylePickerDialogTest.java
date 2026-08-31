package com.claudecode.ui.lanterna.dialog;

import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** State-machine coverage for the Config output-style managed picker. */
class OutputStylePickerDialogTest {

    @Test
    void idle_collapsesToZeroSize() {
        OutputStylePickerDialog dialog = new OutputStylePickerDialog();
        assertFalse(dialog.isActive());
        assertEquals(new TerminalSize(0, 0), dialog.calculatePreferredSize());
    }

    @Test
    void arrowAndEnter_chooseBuiltInStyle() {
        AtomicReference<String> result = new AtomicReference<>();
        OutputStylePickerDialog dialog = new OutputStylePickerDialog();
        dialog.show("default", result::set);

        dialog.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals("Explanatory", result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void escape_cancelsWithoutChangingStyle() {
        AtomicReference<String> result = new AtomicReference<>("unset");
        OutputStylePickerDialog dialog = new OutputStylePickerDialog();
        dialog.show("Learning", result::set);

        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertNull(result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void unknownInitialStyle_fallsBackToDefault() {
        AtomicReference<String> result = new AtomicReference<>();
        OutputStylePickerDialog dialog = new OutputStylePickerDialog();
        dialog.show("missing-style", result::set);

        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals("default", result.get());
    }

    @Test
    void digitSelect_matchesTsSelectBehavior() {
        AtomicReference<String> result = new AtomicReference<>();
        OutputStylePickerDialog dialog = new OutputStylePickerDialog();
        dialog.show("default", result::set);

        dialog.handleKey(new KeyStroke('3', false, false), new AtomicBoolean(true));

        assertEquals("Learning", result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void catalogStylesAreSelectableInSourceOrder() {
        OutputStyleCatalog catalog = _ -> List.of(
            new OutputStyleCatalog.Entry("default", "Default", "default description"),
            new OutputStyleCatalog.Entry("plugin:mentor", "plugin:mentor", "plugin description"));
        AtomicReference<String> result = new AtomicReference<>();
        OutputStylePickerDialog dialog = new OutputStylePickerDialog(catalog, Path.of("."));
        dialog.show("default", result::set);

        dialog.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals("plugin:mentor", result.get());
    }
}
