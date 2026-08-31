package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.TerminalResizeListener;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * ANSI output compactor for Lanterna's delta renderer.
 */
final class CompactAnsiTerminal implements ExtendedTerminal {

    private static final int MIN_COMPACT_RUN = 8;
    private static final String ERASE_TO_LINE_END = "\033[K";
    private static final long WINDOWS_SIZE_POLL_INTERVAL_NANOS =
        TimeUnit.MILLISECONDS.toNanos(100);

    private final ExtendedTerminal delegate;
    private final FastTerminalInputDecoder inputDecoder;
    private final boolean pollTerminalSize;
    private final LongSupplier nanoTime;
    private boolean terminalSizePolled;
    private long lastTerminalSizePollNanos;
    private MouseCaptureMode mouseCaptureMode;
    private int cursorColumn;
    private int cursorRow;
    private Boolean cursorVisible;
    /**
     * ANSI EL leaves the physical cursor at the start of the erased run while
     * Lanterna's screen buffer has already advanced past every logical blank.
     * Delay that reconciliation: the renderer commonly issues an explicit
     * move for the next changed row, making an eager CUP pure output overhead.
     */
    private boolean cursorSyncPending;

    CompactAnsiTerminal(ExtendedTerminal delegate) {
        this(delegate, null);
    }

    CompactAnsiTerminal(ExtendedTerminal delegate, FastTerminalInputDecoder inputDecoder) {
        this(delegate, inputDecoder,
            Platform.IS_WINDOWS && inputDecoder != null, System::nanoTime);
    }

    CompactAnsiTerminal(ExtendedTerminal delegate, FastTerminalInputDecoder inputDecoder,
                        boolean pollTerminalSize, LongSupplier nanoTime) {
        this.delegate = delegate;
        this.inputDecoder = inputDecoder;
        this.pollTerminalSize = pollTerminalSize && inputDecoder != null;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void putString(String string) throws IOException {
        if (isCompactableBlankRun(string)) {
            delegate.putString(ERASE_TO_LINE_END);
            cursorColumn += string.length();
            cursorSyncPending = true;
            return;
        }
        syncCursorIfNeeded();
        delegate.putString(string);
        if (string != null && string.indexOf('\033') < 0) cursorColumn += string.length();
    }

    private static boolean isCompactableBlankRun(String value) {
        if (value == null || value.length() < MIN_COMPACT_RUN) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != ' ') return false;
        }
        return true;
    }

    @Override public void setCursorPosition(int column, int row) throws IOException {
        cursorColumn = column;
        cursorRow = row;
        cursorSyncPending = false;
        delegate.setCursorPosition(column, row);
    }
    @Override public void setCursorPosition(TerminalPosition position) throws IOException {
        setCursorPosition(position.getColumn(), position.getRow());
    }
    @Override public void putCharacter(char character) throws IOException {
        syncCursorIfNeeded();
        delegate.putCharacter(character);
        cursorColumn++;
    }
    @Override public void clearScreen() throws IOException {
        delegate.clearScreen();
        cursorColumn = 0;
        cursorRow = 0;
        cursorSyncPending = false;
    }

    @Override public void enterPrivateMode() throws IOException { delegate.enterPrivateMode(); }
    @Override public void exitPrivateMode() throws IOException { delegate.exitPrivateMode(); }
    @Override public TerminalPosition getCursorPosition() throws IOException {
        syncCursorIfNeeded();
        TerminalPosition position = delegate.getCursorPosition();
        cursorColumn = position.getColumn();
        cursorRow = position.getRow();
        return position;
    }
    @Override public void setCursorVisible(boolean visible) throws IOException {
        if (visible) syncCursorIfNeeded();
        if (cursorVisible != null && visible == cursorVisible) return;
        delegate.setCursorVisible(visible);
        cursorVisible = visible;
    }
    @Override public TextGraphics newTextGraphics() throws IOException {
        syncCursorIfNeeded();
        return delegate.newTextGraphics();
    }
    @Override public void enableSGR(SGR sgr) throws IOException { delegate.enableSGR(sgr); }
    @Override public void disableSGR(SGR sgr) throws IOException { delegate.disableSGR(sgr); }
    @Override public void resetColorAndSGR() throws IOException { delegate.resetColorAndSGR(); }
    @Override public void setForegroundColor(TextColor color) throws IOException { delegate.setForegroundColor(color); }
    @Override public void setBackgroundColor(TextColor color) throws IOException { delegate.setBackgroundColor(color); }
    @Override public void addResizeListener(TerminalResizeListener listener) { delegate.addResizeListener(listener); }
    @Override public void removeResizeListener(TerminalResizeListener listener) { delegate.removeResizeListener(listener); }
    @Override public TerminalSize getTerminalSize() throws IOException { return delegate.getTerminalSize(); }
    @Override public byte[] enquireTerminal(int timeout, TimeUnit unit) throws IOException {
        return delegate.enquireTerminal(timeout, unit);
    }
    @Override public void bell() throws IOException { delegate.bell(); }
    @Override public void flush() throws IOException { delegate.flush(); }
    @Override public void close() throws IOException { delegate.close(); }
    @Override public KeyStroke pollInput() throws IOException {
        pollTerminalSizeIfNeeded();
        return filterMouseEvents(inputDecoder != null
            ? inputDecoder.pollInput() : delegate.pollInput());
    }
    @Override public KeyStroke readInput() throws IOException {
        pollTerminalSizeIfNeeded();
        KeyStroke key;
        do {
            key = filterMouseEvents(inputDecoder != null
                ? inputDecoder.readInput() : delegate.readInput());
        } while (key == null);
        return key;
    }
    @Override public void setTerminalSize(int columns, int rows) throws IOException {
        delegate.setTerminalSize(columns, rows);
    }
    @Override public void setTitle(String title) throws IOException { delegate.setTitle(title); }
    @Override public void pushTitle() throws IOException { delegate.pushTitle(); }
    @Override public void popTitle() throws IOException { delegate.popTitle(); }
    @Override public void iconify() throws IOException { delegate.iconify(); }
    @Override public void deiconify() throws IOException { delegate.deiconify(); }
    @Override public void maximize() throws IOException { delegate.maximize(); }
    @Override public void unmaximize() throws IOException { delegate.unmaximize(); }
    @Override public void setMouseCaptureMode(MouseCaptureMode mode) throws IOException {
        delegate.setMouseCaptureMode(mode);
        mouseCaptureMode = mode;
    }
    @Override public void scrollLines(int firstLine, int lastLine, int distance) throws IOException {
        delegate.scrollLines(firstLine, lastLine, distance);
    }
    @Override public void enableSynchronizedOutput() throws IOException { delegate.enableSynchronizedOutput(); }
    @Override public void disableSynchronizedOutput() throws IOException { delegate.disableSynchronizedOutput(); }
    @Override public void enableBracketedPaste() throws IOException { delegate.enableBracketedPaste(); }
    @Override public void disableBracketedPaste() throws IOException { delegate.disableBracketedPaste(); }
    @Override public void enableFocusReporting() throws IOException { delegate.enableFocusReporting(); }
    @Override public void disableFocusReporting() throws IOException { delegate.disableFocusReporting(); }
    @Override public void enableKittyKeyboard() throws IOException { delegate.enableKittyKeyboard(); }
    @Override public void disableKittyKeyboard() throws IOException { delegate.disableKittyKeyboard(); }
    @Override public void setCursorStyle(CursorStyle style) throws IOException { delegate.setCursorStyle(style); }
    @Override public void emitOSC(String code, String payload) throws IOException { delegate.emitOSC(code, payload); }
    @Override public void setClipboardOSC52(String base64) throws IOException { delegate.setClipboardOSC52(base64); }
    @Override public void queryDecMode(int mode) throws IOException { delegate.queryDecMode(mode); }
    @Override public void queryDeviceAttributes() throws IOException { delegate.queryDeviceAttributes(); }

    private void syncCursorIfNeeded() throws IOException {
        if (!cursorSyncPending) return;
        delegate.setCursorPosition(cursorColumn, cursorRow);
        cursorSyncPending = false;
    }

    @Explanation("Keeps Windows ConPTY resize events visible when fast input bypasses Lanterna's native input stream")
    private void pollTerminalSizeIfNeeded() {
        if (!pollTerminalSize) return;
        long now = nanoTime.getAsLong();
        if (terminalSizePolled
                && now - lastTerminalSizePollNanos < WINDOWS_SIZE_POLL_INTERVAL_NANOS) {
            return;
        }
        terminalSizePolled = true;
        lastTerminalSizePollNanos = now;
        try {
            delegate.getTerminalSize();
        } catch (IOException _) {
            // Best effort: a transient console query failure must not stop input.
        }
    }

    private KeyStroke filterMouseEvents(KeyStroke key) {
        if (!(key instanceof MouseAction mouse)) return key;
        MouseActionType action = mouse.getActionType();
        if (action == MouseActionType.CLICK_RELEASE
                && mouseCaptureMode == MouseCaptureMode.CLICK) {
            return null;
        }
        if (action == MouseActionType.DRAG
                && (mouseCaptureMode == MouseCaptureMode.CLICK
                    || mouseCaptureMode == MouseCaptureMode.CLICK_RELEASE)) {
            return null;
        }
        if (action == MouseActionType.MOVE
                && (mouseCaptureMode == MouseCaptureMode.CLICK
                    || mouseCaptureMode == MouseCaptureMode.CLICK_RELEASE
                    || mouseCaptureMode == MouseCaptureMode.CLICK_RELEASE_DRAG)) {
            return null;
        }
        return key;
    }

}
