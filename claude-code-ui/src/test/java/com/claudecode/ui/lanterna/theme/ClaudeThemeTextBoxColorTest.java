package com.claudecode.ui.lanterna.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the input-field text color behavior of {@link ClaudeTheme}.
 *
 * <p>Official Claude Code renders the prompt's typed text with chalk.inverse, which
 * swaps foreground/background but never sets an explicit foreground — so the entered
 * text inherits the terminal's default foreground (e.g. bright green in an iTerm2
 * green theme). The Java theme used to force a fixed white ({@link LanternaTheme#inputText()}),
 * making the input always white regardless of the user's terminal theme. This suite
 * pins the TextBox override to {@link TextColor.ANSI#DEFAULT} and guards the other
 * components from accidental color drift.
 */
class ClaudeThemeTextBoxColorTest {

    @AfterEach
    void resetScheme() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
    }

    @Test
    void textBoxForeground_isTerminalDefault() {
        SimpleTheme theme = ClaudeTheme.build();
        var definition = theme.getDefinition(TextBox.class);

        assertEquals(TextColor.ANSI.DEFAULT, definition.getNormal().getForeground());
        assertEquals(TextColor.ANSI.DEFAULT, definition.getActive().getForeground());
    }

    @Test
    void highlightedTextBoxForeground_isTerminalDefault() {
        // InputPanel's prompt is a HighlightedTextBox. SimpleTheme.getDefinition is
        // an exact-class lookup (no superclass fallback), so the terminal-default
        // override must reach the concrete subclass or the input text falls back
        // to the fixed-white default foreground.
        SimpleTheme theme = ClaudeTheme.build();
        var definition = theme.getDefinition(HighlightedTextBox.class);

        assertEquals(TextColor.ANSI.DEFAULT, definition.getNormal().getForeground());
        assertEquals(TextColor.ANSI.DEFAULT, definition.getActive().getForeground());
    }

    @Test
    void textBoxForeground_differsFromFixedWhiteInputText() {
        SimpleTheme theme = ClaudeTheme.build();
        var definition = theme.getDefinition(TextBox.class);

        TextColor fixedWhite = LanternaTheme.inputText();
        assertNotEquals(fixedWhite, definition.getNormal().getForeground());
        assertNotEquals(fixedWhite, definition.getActive().getForeground());
    }

    @Test
    void highlightedTextBoxSubclass_inheritsTerminalDefaultOverride() {
        // InputPanel's prompt is a private inner class PromptTextBox extends
        // HighlightedTextBox; its getClass() is that inner class, which the
        // override map never keys directly. This pins the fork's superclass
        // fallback in SimpleTheme.getDefinition so the terminal-default override
        // registered for HighlightedTextBox reaches such a concrete subclass.
        SimpleTheme theme = ClaudeTheme.build();

        var subclass = new HighlightedTextBox(
                new TerminalSize(40, 1),
                TextBox.Style.MULTI_LINE,
                () -> List.of()) {
            // no additions — mirrors PromptTextBox merely being a HighlightedTextBox subclass
        };

        var definition = theme.getDefinition(subclass.getClass());
        assertEquals(TextColor.ANSI.DEFAULT, definition.getNormal().getForeground());
        assertEquals(TextColor.ANSI.DEFAULT, definition.getActive().getForeground());
    }

    @Test
    void otherComponents_keepThemeInputTextForeground() {
        SimpleTheme theme = ClaudeTheme.build();
        TextColor fg = LanternaTheme.inputText();

        // The input-text fix is scoped to TextBox only; labels/panels/windows still
        // use the theme's regular foreground (matching official theme.text).
        assertEquals(fg, theme.getDefinition(Label.class).getNormal().getForeground());
        assertEquals(fg, theme.getDefinition(Panel.class).getNormal().getForeground());
        assertEquals(fg, theme.getDefinition(BasicWindow.class).getNormal().getForeground());
    }
}