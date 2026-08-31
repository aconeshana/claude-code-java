package com.googlecode.lanterna.gui2;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Package bridge for constructing Lanterna's package-private graphics adapter.
 */
public final class TextGUIGraphicsBridge {

    private TextGUIGraphicsBridge() {}

    public static TextGUIGraphics wrap(TextGUI gui, TextGraphics graphics) {
        return new DefaultTextGUIGraphics(gui, graphics);
    }

    /**
     * Wraps retained window graphics while suppressing exactly its first
     * {@code fill(char)} call. For a decoration-free full-screen window that
     * call is AbstractBasePane's unconditional clear; all component fills and
     * the rest of the BasePane draw/focus-map protocol remain unchanged.
     */
    public static TextGUIGraphics wrapSkippingInitialFill(TextGUI gui, TextGraphics graphics) {
        return new InitialFillSkippingGraphics(gui, graphics, new AtomicBoolean(true));
    }

    private static final class InitialFillSkippingGraphics extends DefaultTextGUIGraphics {
        private final TextGUI gui;
        private final TextGraphics backend;
        private final AtomicBoolean skip;

        private InitialFillSkippingGraphics(TextGUI gui, TextGraphics backend,
                                            AtomicBoolean skip) {
            super(gui, backend);
            this.gui = gui;
            this.backend = backend;
            this.skip = skip;
        }

        @Override
        public DefaultTextGUIGraphics fill(char character) {
            if (skip.compareAndSet(true, false)) return this;
            return super.fill(character);
        }

        @Override
        public DefaultTextGUIGraphics newTextGraphics(
                TerminalPosition position,
                TerminalSize size) {
            return new InitialFillSkippingGraphics(
                gui, backend.newTextGraphics(position, size), skip);
        }
    }
}
