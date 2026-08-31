package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.TerminalDetector;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

/**
 * Owns all terminal escape-sequence I/O for the REPL: window title (static + animated), OSC 9;4
 * progress reporting, OSC 21337 tab status, extended-key capability detection, and the non-TTY
 * screen dump.
 */
final class TerminalController {

    private static final Logger log = LoggerFactory.getLogger(TerminalController.class);

    private final Terminal terminal;
    private final Screen screen;

    TerminalController(Terminal terminal, Screen screen) {
        this.terminal = terminal;
        this.screen = screen;
    }

    // ── Terminal title ──────────────────────────────────────────────────────

/**
     * Animation frames for terminal title.
     */
    private static final String[] TITLE_ANIMATION_FRAMES = {"⠂", "⠐"};
    private static final String TITLE_STATIC_PREFIX = "✳";
    private final AtomicInteger titleAnimationFrame = new AtomicInteger(0);
    private volatile boolean titleAnimating = false;
    private volatile String title = "Claude Code";
    private ScheduledExecutorService titleAnimScheduler;
    private ScheduledFuture<?> titleAnimFuture;


    void setStaticTitle() {
        emitTitle(TITLE_STATIC_PREFIX);
    }

    /** Applies a generated, renamed, or restored session title immediately. */
    void setTitle(String title) {
        this.title = StringUtils.isBlank(title) ? "Claude Code" : title.trim();
        updateAnimatedTitle();
    }

    private void emitTitle(String prefix) {
        try {
            terminal.emitOSC("0", prefix + " " + title);
            terminal.flush();
        } catch (Exception _) {
            // Non-fatal.
        }
    }


    void startTitleAnimation() {
        titleAnimating = true;
        titleAnimationFrame.set(0);
        stopTitleAnimationScheduler();

        titleAnimScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        titleAnimFuture = titleAnimScheduler.scheduleAtFixedRate(() -> {
            if (!titleAnimating) return;
            updateAnimatedTitle();
            titleAnimationFrame.incrementAndGet();
        }, 0, 960, TimeUnit.MILLISECONDS);
    }

/** Stop the loading title animation — matches isAnimating=false when isLoading clears. */
    void stopTitleAnimation() {
        titleAnimating = false;
        stopTitleAnimationScheduler();
        // Restore static prefix on stop
        updateAnimatedTitle();
    }

    private void stopTitleAnimationScheduler() {
        if (titleAnimFuture != null) {
            titleAnimFuture.cancel(true);
            titleAnimFuture = null;
        }
        if (titleAnimScheduler != null) {
            titleAnimScheduler.shutdownNow();
            titleAnimScheduler = null;
        }
    }

    /** Update terminal title with animation prefix when loading. */
    private void updateAnimatedTitle() {
        String prefix = titleAnimating
            ? TITLE_ANIMATION_FRAMES[titleAnimationFrame.get() % TITLE_ANIMATION_FRAMES.length]
            : TITLE_STATIC_PREFIX;
        emitTitle(prefix);
    }

    // ── OSC 9;4 progress ────────────────────────────────────────────────────

    /**
     * Emit OSC 9;4 indeterminate progress — terminals that support it show a spinner in the title
     * bar/tab.
     */
    void progressIndeterminate() {
        try {

            terminal.emitOSC("9;4", "3;");
            terminal.flush();
        } catch (Exception _) {
            // Non-fatal — progress indication is best-effort.
        }
    }

    /** Clear OSC 9;4 progress bar — opcode 0 = CLEAR. */
    void progressClear() {
        try {

            terminal.emitOSC("9;4", "0;");
            terminal.flush();
        } catch (Exception _) {
            // Non-fatal.
        }
    }

    // ── OSC 21337 tab status ────────────────────────────────────────────────

    /** Set a single OSC 21337 tab-status field (e.g. state=busy, color=rgb(...)). */
    void setTabStatus(String key, String value) {
        if (key == null || value == null) return;
        try {
            terminal.emitOSC("21337", key + "=" + value);
            terminal.flush();
        } catch (Exception _) {
            // Non-fatal.
        }
    }

    /** Clear all OSC 21337 tab-status fields. */
    void clearTabStatus() {
        try {
            terminal.emitOSC("21337", "");
            terminal.flush();
        } catch (Exception _) {
            // Non-fatal.
        }
    }

    // ── Misc terminal I/O ───────────────────────────────────────────────────

    // OSC 133 shell-integration prompt marks (PromptMarker / 133;A…D) are
    // intentionally NOT emitted. A brief inline emission once lived in
    // LanternaReplScreen.initTerminal() and was removed: this is a full-screen
    // alt-screen TUI with no "prompt row" concept, and iTerm2's click-to-jump
    // highlight on the mark flashed a stray yellow/gray border on window-edge
    // clicks. If a future terminal makes it worthwhile, add an emit method here.

    /**
     * Check if the terminal supports extended key reporting (Kitty keyboard protocol + xterm
     * modifyOtherKeys).
     */
    static boolean supportsExtendedKeys() {

        String terminal = TerminalDetector.getTerminal();
        if (terminal == null) return false;
        return switch (terminal) {
            case "iTerm.app", "kitty", "WezTerm", "ghostty", "tmux",
                 "windows-terminal" -> true;
            default -> false;
        };
    }

    /**
     * Dump the screen's front buffer to stdout — used for visual verification
     * in non-TTY environments where the VirtualTerminal can't display directly.
     */
    void dumpScreenToStdout() {
        try {
            var size = screen.getTerminalSize();
            for (int y = 0; y < size.getRows(); y++) {
                StringBuilder line = new StringBuilder();
                for (int x = 0; x < size.getColumns(); x++) {
                    // getFrontCharacter never returns null in Lanterna 3.x — empty
                    // cells come back as TextCharacter.DEFAULT whose character is a
                    // blank ' ', so getCharacterString() already yields the space.
                    // No null guard is needed (and the prior `else` was dead code).
                    var tc = screen.getFrontCharacter(x, y);
                    // Apply ANSI colors
                    var fg = tc.getForegroundColor();
                    var bg = tc.getBackgroundColor();
                    var mods = tc.getModifiers();
                    // getForegroundColor()/getBackgroundColor() are @NotNull in
                    // Lanterna 3.x — they return TextColor.DEFAULT (== ANSI.DEFAULT)
                    // for unchanged cells and never null. So the only meaningful
                    // guard is "is this a non-default color?".
                    if (fg != TextColor.ANSI.DEFAULT) {
                        line.append("[3").append(colorIndex(fg)).append('m');
                    }
                    if (bg != TextColor.ANSI.DEFAULT) {
                        line.append("[4").append(colorIndex(bg)).append('m');
                    }
                    for (var sgr : mods) {
                        switch (sgr) {
                            case BOLD -> line.append("[1m");
                            case ITALIC -> line.append("[3m");
                            case UNDERLINE -> line.append("[4m");
                            case BLINK -> line.append("[5m");
                            case REVERSE -> line.append("[7m");
                            default -> {}
                        }
                    }
                    line.append(tc.getCharacterString());
                    line.append("[0m");
                }
                // Trim trailing spaces
                String trimmed = line.toString().replaceAll("\\s+$", "");
                if (!trimmed.isEmpty()) {
                    TuiOutputGuard.writeToTerminal(trimmed + System.lineSeparator());
                }
            }
        } catch (Exception e) {
            log.warn("[LANTERNA] Failed to dump screen", e);
        }
    }

    private static int colorIndex(TextColor color) {
        if (color instanceof TextColor.ANSI ansi) {
            return ansi.ordinal();
        }
        return 9; // default
    }
}
