package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import java.io.IOException;

/**
 * Orders mouse-reporting changes around terminal ownership handoffs.
 */
final class TerminalMouseModeLifecycle {

    private static final MouseCaptureMode TUI_CAPTURE_MODE =
        MouseCaptureMode.CLICK_RELEASE_DRAG;

    private TerminalMouseModeLifecycle() {}

    static void enableForTui(ExtendedTerminal terminal) throws IOException {
        terminal.setMouseCaptureMode(TUI_CAPTURE_MODE);
        terminal.flush();
    }

    static void disableBeforeHandoff(ExtendedTerminal terminal) throws IOException {
        terminal.setMouseCaptureMode(null);
        terminal.flush();
    }
}
