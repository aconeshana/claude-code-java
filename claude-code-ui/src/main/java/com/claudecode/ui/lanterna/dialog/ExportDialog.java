package com.claudecode.ui.lanterna.dialog;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.OSC52Helper;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.text.FormatUtils;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline {@code /export} picker — sits in the SmartLayout stack just above {@link InputPanel},
 * occupying zero rows when idle.
 */
public final class ExportDialog extends Panel implements InlineOverlay {

    @FunctionalInterface
    interface ExportFileWriter {
        void write(Path path, String content) throws Exception;
    }

    /** Cells from the left edge of the body to the first label cell. */
    private static final int LEFT_PAD = 2;

    /**
     * Result kinds returned to the host.
     */
    public record Result(String message) {}

    enum State { PICKER, FILENAME }
    enum Option { CLIPBOARD, FILE }

    private final Picker picker;
    private final FilenameRow filenameRow;

    private boolean active;
    private State state;
    private Option focused;

    /** Original conversation text (passed in via {@link #show}). Held for the
     *  duration of the dialog session, cleared on hide(). */
    private String content;
    /** Default filename derived from the conversation; pre-filled on entry to FILENAME state. */
    private String defaultFilename;
    /** Live filename buffer while in FILENAME state. */
    private StringBuilder filenameBuf;
    /** Cursor position within {@link #filenameBuf}; updated by typing/backspace. */
    private int caret;

    /** Working directory base for relative filenames. Set on {@link #show}. */
    private String cwd;

    /**
     * Result consumer wired by the host (LanternaReplScreen) — receives the
     * outcome string the dispatcher should render as a transcript line.
     */
    private BiConsumer<Result, /*saved=*/ Boolean> onResult;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    private ExportFileWriter fileWriter = FileUtils::writeString;
    private Consumer<Runnable> guiInvoker = Runnable::run;
    private boolean saving;
    private long epoch;

    public ExportDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.picker = new Picker();
        this.filenameRow = new FilenameRow();
        picker.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        filenameRow.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(picker);
        addComponent(filenameRow);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    void setFileWriter(ExportFileWriter writer) {
        fileWriter = writer != null ? writer : FileUtils::writeString;
    }

    public void setGuiInvoker(Consumer<Runnable> invoker) {
        guiInvoker = invoker != null ? invoker : Runnable::run;
    }

    /**
     * Activate the dialog. Must run on the GUI thread.
     *
     * @param content          plain-text conversation to export (pre-rendered)
     * @param defaultFilename  default filename to pre-fill in FILENAME state
     * @param cwd              base dir for relative file paths
     * @param onResult         result callback ({@code (Result, saved)}). {@code saved=true} for
     *                         successful clipboard/file write, {@code false} for cancellation.
     */
    public synchronized void show(String content, String defaultFilename, String cwd,
                                  BiConsumer<Result, Boolean> onResult) {
        epoch++;
        this.content = content;
        this.defaultFilename = defaultFilename;
        this.cwd = cwd != null ? cwd : System.getProperty("user.dir");
        this.onResult = onResult;
        this.state = State.PICKER;
        this.focused = Option.CLIPBOARD;
        this.saving = false;
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }


    public static String buildDefaultFilename(String firstPromptOrEmpty) {
        String timestamp = FormatUtils.formatExportTimestamp(Instant.now());
        if (StringUtils.isBlank(firstPromptOrEmpty)) {
            return "conversation-" + timestamp + ".txt";
        }
        String sanitized = firstPromptOrEmpty.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9 -]", "")
            .replaceAll(" +", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        return sanitized.isEmpty()
            ? "conversation-" + timestamp + ".txt"
            : timestamp + "-" + sanitized + ".txt";
    }

    /**
     * Intercept a key while active.
     */
    @Override public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (saving) {
            deliver.set(false);
            return;
        }
        if (state == State.PICKER) {
            handlePickerKey(key, deliver);
        } else {
            handleFilenameKey(key, deliver);
        }
    }

    private void handlePickerKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean handled = switch (value) {
                case "select:previous", "select:next" -> {
                    focused = focused == Option.CLIPBOARD ? Option.FILE : Option.CLIPBOARD;
                    invalidate();
                    yield true;
                }
                case "select:accept" -> { selectFocused(); yield true; }
                case "select:cancel" -> {
                    resolve(new Result("Export cancelled"), false);
                    yield true;
                }
                default -> false;
            };
            if (handled) {
                deliver.set(false);
                return;
            }
        }
        if (t == KeyType.ARROW_UP || t == KeyType.ARROW_DOWN) {
            focused = focused == Option.CLIPBOARD ? Option.FILE : Option.CLIPBOARD;
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            selectFocused();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            resolve(new Result("Export cancelled"), false);
            deliver.set(false);
        }
    }

    private void selectFocused() {
        if (focused == Option.CLIPBOARD) {
            try {
                OSC52Helper.copyToClipboard(content);
                resolve(new Result("Conversation copied to clipboard"), true);
            } catch (Exception e) {
                resolve(new Result("Failed to copy to clipboard: " + e.getMessage()), false);
            }
        } else {
            filenameBuf = new StringBuilder(defaultFilename);
            caret = filenameBuf.length();
            state = State.FILENAME;
            invalidate();
        }
    }

    private void handleFilenameKey(KeyStroke key, AtomicBoolean deliver) {
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Settings", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            state = State.PICKER;
            filenameBuf = null;
            invalidate();
            deliver.set(false);
            return;
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ENTER) {
            saveToFile();
            deliver.set(false);
            return;
        }
        if (t == KeyType.BACKSPACE) {
            if (caret > 0) {
                filenameBuf.deleteCharAt(caret - 1);
                caret--;
                invalidate();
            }
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_LEFT) {
            if (caret > 0) { caret--; invalidate(); }
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_RIGHT) {
            if (caret < filenameBuf.length()) { caret++; invalidate(); }
            deliver.set(false);
            return;
        }
        if (t == KeyType.HOME) { caret = 0; invalidate(); deliver.set(false); return; }
        if (t == KeyType.END)  { caret = filenameBuf.length(); invalidate(); deliver.set(false); return; }
        if (t == KeyType.PASTE && key instanceof PasteKeyStroke pks) {
            // Same fix as AddDirDialog: this dialog has no real Interactable to
            // hold Lanterna's GUI focus, so an unhandled PASTE silently leaks
            // through to the main chat input underneath instead of landing in
            // filenameBuf — must consume it here.
            String pasted = pks.getPastedText();
            if (StringUtils.isNotEmpty(pasted)) {
                String clean = pasted.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                filenameBuf.insert(caret, clean);
                caret += clean.length();
                invalidate();
            }
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            filenameBuf.insert(caret, key.getCharacter());
            caret++;
            invalidate();
            deliver.set(false);
        }
    }

    private void saveToFile() {
        String name = filenameBuf.toString().trim();
        if (name.isEmpty()) {

// we match by treating it as no-op to keep the user in the input).
            return;
        }

        String finalName = Strings.CS.endsWith(name, ".txt") ? name : name.replaceAll("\\.[^.]+$", "") + ".txt";
        Path filePath = Path.of(finalName);
        Path path = filePath.isAbsolute() ? filePath : Path.of(cwd, finalName);
        long ticket = epoch;
        String contentSnapshot = content;
        saving = true;
        invalidate();
        Thread.ofVirtual().name("export-file-save").start(() -> {
            Exception failure = null;
        try {
                fileWriter.write(path, contentSnapshot);
            } catch (Exception e) {
                failure = e;
            }
            Exception finalFailure = failure;
            guiInvoker.accept(() -> {
                if (!active || epoch != ticket) return;
                saving = false;
                if (finalFailure == null) {
            resolve(new Result("Conversation exported to: " + path.toAbsolutePath()), true);
                } else {
                    resolve(new Result("Failed to export conversation: "
                        + finalFailure.getMessage()), false);
        }
            });
        });
    }

    private synchronized void resolve(Result r, boolean saved) {
        if (!active) return;
        BiConsumer<Result, Boolean> cb = onResult;
        hide();
        if (cb != null) cb.accept(r, saved);
    }

    State stateForTest() { return state; }

    private synchronized void hide() {
        epoch++;
        active = false;
        saving = false;
        state = null;
        content = null;
        filenameBuf = null;
        onResult = null;
        invalidate();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        // PICKER: divider + title + subtitle + 2 options × (label + description)
        //         + blank + footer = 9 rows. FILENAME keeps the original 6.
        int rows = state == State.PICKER ? 9 : 6;
        int cols = Math.max(60, parent.getColumns());
        return new TerminalSize(cols, rows);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }
    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Renderers
    // ──────────────────────────────────────────────────────────────────────

    /** Renders either the option list or the filename input, based on state. */
    private abstract static class StatefulArea<T extends AbstractComponent<T>> extends AbstractComponent<T> {
        @SuppressWarnings("unchecked")
        protected T self() { return (T) this; }
    }

    private final class Picker extends StatefulArea<Picker> {
        @Override protected ComponentRenderer<Picker> createDefaultRenderer() {
            return new PickerRenderer();
        }
    }

    private final class FilenameRow extends StatefulArea<FilenameRow> {
        @Override protected ComponentRenderer<FilenameRow> createDefaultRenderer() {
            return new FilenameRenderer();
        }
    }

    private final class PickerRenderer implements ComponentRenderer<Picker> {
        @Override public TerminalSize getPreferredSize(Picker c) {
            // Visible only in PICKER state; collapse otherwise.
            return active && state == State.PICKER
                ? new TerminalSize(60, 9)
                : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Picker c) {
            if (!active || state != State.PICKER) return;
            g.fill(' ');
            TerminalSize size = g.getSize();


            // color to the Divider (same rule confirmed for /theme's Pane).
            g.setForegroundColor(LanternaTheme.permission());
            g.putString(0, 0, "─".repeat(Math.max(0, size.getColumns())));


            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Export Conversation");
            g.disableModifiers(SGR.BOLD);

            // Row 2: dim subtitle.
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, "Select export method:");


            // suggestion-colored focused label (not bold), dim description
            // line indented past the index column.
            drawOption(g, 3, Option.CLIPBOARD, 1, "Copy to clipboard",
                "Copy the conversation to your system clipboard");
            drawOption(g, 5, Option.FILE, 2, "Save to file",
                "Save the conversation to a file in the current directory");


            // in the picker state (the Select itself carries no arrow hint).
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 8, "Esc to cancel");
        }

        private void drawOption(TextGUIGraphics g, int y, Option opt, int index,
                                String label, String description) {
            boolean isFocused = focused == opt;
            g.setForegroundColor(isFocused
                ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, y, isFocused ? "❯ " : "  ");
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 2, y, index + ".");
            g.setForegroundColor(isFocused
                ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            g.putString(LEFT_PAD + 5, y, label);
            g.setForegroundColor(isFocused
                ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 6, y + 1, description);
        }
    }

    private final class FilenameRenderer implements ComponentRenderer<FilenameRow> {
        @Override public TerminalSize getPreferredSize(FilenameRow c) {
            return active && state == State.FILENAME
                ? new TerminalSize(60, 6)
                : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, FilenameRow c) {
            if (!active || state != State.FILENAME) return;
            g.fill(' ');
            TerminalSize size = g.getSize();

            g.setForegroundColor(LanternaTheme.permission());
            g.putString(0, 0, "─".repeat(Math.max(0, size.getColumns())));

            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Export Conversation");
            g.disableModifiers(SGR.BOLD);


            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 2, "Enter filename:");

            // Filename input row with caret marker.
            String buf = filenameBuf == null ? "" : filenameBuf.toString();
            g.setForegroundColor(LanternaTheme.inputText());
            String shown = "> " + buf;
            // Bound to available width — back off from the right edge.
            int maxLen = Math.max(0, size.getColumns() - LEFT_PAD - 1);
            if (shown.length() > maxLen) {
                shown = shown.substring(0, maxLen);
            }
            g.putString(LEFT_PAD, 3, shown);
// Caret: reverse video on the cell at "> ".length + caret.
            int caretX = LEFT_PAD + 2 + Math.min(caret, buf.length());
            if (caretX < size.getColumns()) {
                char under = caret < buf.length() ? buf.charAt(caret) : ' ';
                g.setForegroundColor(LanternaTheme.inputText());
                g.enableModifiers(SGR.REVERSE);
                g.putString(caretX, 3, String.valueOf(under));
                g.disableModifiers(SGR.REVERSE);
            }

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 5,
                "Enter to save · Esc to go back");
        }
    }

    // ── Unused helper kept for compile-time linkage to the divider color helper ──
    @SuppressWarnings("unused")
    private static TextColor unused() { return LanternaTheme.divider(); }
}
