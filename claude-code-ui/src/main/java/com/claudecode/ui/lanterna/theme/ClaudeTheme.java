package com.claudecode.ui.lanterna.theme;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.SimpleTheme;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;

/**
 * Dark terminal theme — overrides Lanterna's default blue/colored backgrounds.
 * <p>
 * Lanterna's default theme applies blue backgrounds to TextBox, focused borders
 * to windows, etc. This theme makes everything use terminal default colors so
 * the UI feels like a native terminal app, not a dialog box.
 */
public final class ClaudeTheme {

    private ClaudeTheme() {}

    /**
     * Build a Lanterna SimpleTheme that:
     * - Uses terminal default background (transparent) everywhere
     * - No blue highlights on TextBox
     * - No window borders (we draw our own)
     * - Consistent text color matching LanternaTheme
     */
    public static SimpleTheme build() {
        TextColor fg = LanternaTheme.inputText();
        TextColor bg = TextColor.ANSI.DEFAULT;

        SimpleTheme theme = new SimpleTheme(fg, bg);

        // TextBox: remove blue background completely AND let the typed text use
        // the terminal's default foreground color instead of a fixed white.
        // Official Claude Code's TextInput renders the entered text with
        // chalk.inverse (which swaps fg/bg but never sets a foreground), so it
        // inherits the terminal's default foreground — e.g. a green prompt in an
        // iTerm2 green theme. Forcing rgb(255,255,255) here made the input text
        // always white regardless of the user's terminal theme. DEFAULT emits the
        // terminal default foreground (Lanterna resets with \E[39m), matching it.
        //
        // NB: SimpleTheme.getDefinition() performs an exact-key lookup with no
        // superclass fallback. InputPanel's prompt is a private inner class
        // PromptTextBox extends HighlightedTextBox; its getClass() is that inner
        // class, which no override key matches. The fork's SimpleTheme extends
        // this with an inheritance fallback (see the lanterna fork), so registering
        // the override against the base TextBox/HighlightedTextBox class lets the
        // terminal-default foreground propagate to such concrete subclasses.
        theme.addOverride(TextBox.class, TextColor.ANSI.DEFAULT, bg);
        theme.addOverride(HighlightedTextBox.class, TextColor.ANSI.DEFAULT, bg);

        // Panel: transparent background
        theme.addOverride(Panel.class, fg, bg);

        // Label: standard text
        theme.addOverride(Label.class, fg, bg);

        // Window: transparent background, no special decorations
        theme.addOverride(BasicWindow.class, fg, bg);

        // Buttons: keep minimal styling
        theme.addOverride(Button.class,
            LanternaTheme.suggestion(), bg);

        return theme;
    }
}
