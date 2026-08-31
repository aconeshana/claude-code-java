package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.claudecode.core.io.PathUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.TextInputs;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline {@code /add-dir} dialog — sits above {@link InputPanel} in the SmartLayout stack,
 * occupying zero rows when idle.
 */
public final class AddDirDialog extends Panel implements InlineOverlay {

    enum Phase { INPUT, CONFIRM }

    /** One directory-completion candidate. */
    record DirSuggestion(String absolutePath, String displayName) {}

    /** One "remember?" choice. {@code remember == null} means "No" / cancel. */
    record RememberOption(String label, Boolean remember) {}

    /**
     * Result of validating a path typed into the input field — a
     * dialog-local match of {@code CommandContext.AddDirValidationOutcome}
     * so this class stays decoupled from {@code claude-code-commands}
     * concrete types, matching every other picker dialog's convention.
     */
    public record ValidationOutcome(String resolvedPath, String errorMessage) {
        public boolean isValid() { return errorMessage == null; }
    }

    private static final List<RememberOption> REMEMBER_OPTIONS = List.of(
        new RememberOption("Yes, for this session", Boolean.FALSE),
        new RememberOption("Yes, and remember this directory", Boolean.TRUE),
        new RememberOption("No", null)
    );

    private static final int MAX_SUGGESTIONS = 10;
    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 70;
    private static final long SUGGESTION_DEBOUNCE_MS = 100L;
    private static final ScheduledExecutorService COMPLETION_EXECUTOR =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "add-dir-completion");
            thread.setDaemon(true);
            return thread;
        });
    private static final String TITLE = "Add directory to workspace";
    private static final String PERMISSION_DESCRIPTION =
        "Claude Code will be able to read files in this directory and make edits when auto-accept edits is on.";

    private boolean active;
    private Phase phase;
    private StringBuilder input;
    private List<DirSuggestion> suggestions = List.of();
    private int suggestionIdx;
    private String inputError;
    private boolean validationInFlight;
    private String confirmedPath;
    private int rememberIdx;
    private Function<String, ValidationOutcome> validator;
    private BiConsumer<String, Boolean> onResult;
    private Consumer<Runnable> guiInvoker;
    private ScheduledFuture<?> pendingSuggestionTask;
    private long suggestionGeneration;
    private long validationGeneration;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public AddDirDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /** Wires the Lanterna GUI queue; enabling non-blocking completion and validation. */
    public synchronized void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
    }

    /**
     * Activate the dialog. Must run on the GUI thread.
     *
     * @param initialPath {@code null} opens the input field; a pre-validated
     *                     absolute path skips straight to the remember choice.
     * @param validator    validates a typed path (input phase only); ignored
     *                     when {@code initialPath} is non-null.
     * @param onResult     see class Javadoc for the {@code (path, remember)} contract.
     */
    public synchronized void show(String initialPath, Function<String, ValidationOutcome> validator,
                                   BiConsumer<String, Boolean> onResult) {
        this.validator = validator;
        this.onResult = onResult;
        cancelBackgroundWorkLocked();
        if (initialPath == null) {
            this.phase = Phase.INPUT;
            this.input = new StringBuilder();
            this.suggestions = List.of();
            this.suggestionIdx = 0;
            this.inputError = null;
            this.validationInFlight = false;
            this.confirmedPath = null;
        } else {
            this.phase = Phase.CONFIRM;
            this.confirmedPath = initialPath;
            this.rememberIdx = 0;
        }
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (phase == Phase.INPUT) {
            handleInputKey(key, deliver);
        } else {
            handleConfirmKey(key, deliver);
        }
    }

    // ── INPUT phase ──────────────────────────────────────────────────────────

    private void handleInputKey(KeyStroke key, AtomicBoolean deliver) {
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Settings", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            cancel();
            deliver.set(false);
            return;
        }
        KeyType t = key.getKeyType();
        if (validationInFlight && t != KeyType.ESCAPE) {
            deliver.set(false);
            return;
        }
        // Dragging files/folders onto the terminal (or a clipboard paste) arrives as a
        // single bracketed-paste burst; this hand-drawn Panel has no real GUI focus, so an
        // unhandled KeyType would fall through to the main chat input. Must consume it.
        if (TextInputs.tryApplyKey(input, key, false)) {
            refreshSuggestions();
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (ch == 'c' || ch == 'd') {
                cancel();
                deliver.set(false);
                return;
            }

            // "up" || (ctrl && key === 'p') / "down" || (ctrl && key === 'n').
            if (!suggestions.isEmpty() && (ch == 'p' || ch == 'n')) {
                suggestionIdx = InlineOverlay.cycleIndex(suggestionIdx, ch == 'p' ? -1 : 1, suggestions.size());
                invalidate();
                deliver.set(false);
                return;
            }
            return;
        }
        if (!suggestions.isEmpty()) {
            if (t == KeyType.TAB) {
                applySuggestion(suggestions.get(suggestionIdx));
                deliver.set(false);
                return;
            }
            if (t == KeyType.ENTER) {
                submit(suggestions.get(suggestionIdx).absolutePath() + "/");
                deliver.set(false);
                return;
            }
            if (t == KeyType.ARROW_UP) {
                suggestionIdx = InlineOverlay.cycleIndex(suggestionIdx, -1, suggestions.size());
                invalidate();
                deliver.set(false);
                return;
            }
            if (t == KeyType.ARROW_DOWN) {
                suggestionIdx = InlineOverlay.cycleIndex(suggestionIdx, 1, suggestions.size());
                invalidate();
                deliver.set(false);
                return;
            }
        }
        if (t == KeyType.ENTER) {
            submit(input.toString());
            deliver.set(false);
            return;
        }
        if (TextInputs.tryApplyKey(input, key, false)) {
            refreshSuggestions();
            deliver.set(false);
        }
    }

    private void applySuggestion(DirSuggestion suggestion) {
        input = new StringBuilder(suggestion.absolutePath() + "/");
        inputError = null;
        refreshSuggestions();
    }

    private void refreshSuggestions() {
        validationGeneration++;
        validationInFlight = false;
        inputError = null;
        String query = input.toString();
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null) {
            suggestions = computeSuggestions(query);
            suggestionIdx = 0;
            invalidate();
            return;
        }

        long generation = ++suggestionGeneration;
        if (pendingSuggestionTask != null) pendingSuggestionTask.cancel(false);
        if (query.isEmpty()) {
            suggestions = List.of();
            suggestionIdx = 0;
            pendingSuggestionTask = null;
            invalidate();
            return;
        }
        pendingSuggestionTask = COMPLETION_EXECUTOR.schedule(() -> {
            Thread.ofVirtual().name("add-dir-completion-scan").start(() -> {
                List<DirSuggestion> computed = computeSuggestions(query);
                invoker.accept(() -> applySuggestions(generation, query, computed));
            });
        }, SUGGESTION_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        invalidate();
    }

    private synchronized void applySuggestions(long generation, String query,
                                               List<DirSuggestion> computed) {
        if (!active || phase != Phase.INPUT || generation != suggestionGeneration
                || !input.toString().equals(query)) return;
        suggestions = computed;
        suggestionIdx = 0;
        pendingSuggestionTask = null;
        invalidate();
    }

    private void submit(String path) {
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null) {
            applyValidation(validate(path));
            return;
        }
        validationInFlight = true;
        inputError = null;
        long generation = ++validationGeneration;
        invalidate();
        Thread.ofVirtual().name("add-dir-validation").start(() -> {
            ValidationOutcome outcome = validate(path);
            invoker.accept(() -> applyValidation(generation, outcome));
        });
    }

    private ValidationOutcome validate(String path) {
        try {
            return validator != null ? validator.apply(path)
                : new ValidationOutcome(null, "No validator wired.");
        } catch (RuntimeException e) {
            return new ValidationOutcome(null,
                e.getMessage() != null ? e.getMessage() : "Unable to validate directory.");
        }
    }

    private synchronized void applyValidation(long generation, ValidationOutcome outcome) {
        if (!active || phase != Phase.INPUT || generation != validationGeneration) return;
        validationInFlight = false;
        applyValidation(outcome);
    }

    private void applyValidation(ValidationOutcome outcome) {
        if (outcome.isValid()) {
            resolve(outcome.resolvedPath(), false);
        } else {
            inputError = outcome.errorMessage();
            invalidate();
        }
    }

    // ── CONFIRM phase ────────────────────────────────────────────────────────

    private void handleConfirmKey(KeyStroke key, AtomicBoolean deliver) {
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean handled = switch (value) {
                case "select:previous" -> {
                    rememberIdx = InlineOverlay.cycleIndex(rememberIdx, -1, REMEMBER_OPTIONS.size());
                    invalidate();
                    yield true;
                }
                case "select:next" -> {
                    rememberIdx = InlineOverlay.cycleIndex(rememberIdx, 1, REMEMBER_OPTIONS.size());
                    invalidate();
                    yield true;
                }
                case "select:accept" -> { acceptRememberOption(); yield true; }
                case "select:cancel" -> { cancel(); yield true; }
                default -> false;
            };
            if (handled) {
                deliver.set(false);
                return;
            }
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ARROW_UP) {
            rememberIdx = InlineOverlay.cycleIndex(rememberIdx, -1, REMEMBER_OPTIONS.size());
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            rememberIdx = InlineOverlay.cycleIndex(rememberIdx, 1, REMEMBER_OPTIONS.size());
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            acceptRememberOption();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            cancel();
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (ch == 'c' || ch == 'd') {
                cancel();
                deliver.set(false);
            }
        }
    }

    private void acceptRememberOption() {
        RememberOption opt = REMEMBER_OPTIONS.get(rememberIdx);
        if (opt.remember() == null) cancel();
        else resolve(confirmedPath, opt.remember());
    }

    // ── shared ───────────────────────────────────────────────────────────────

    private synchronized void resolve(String path, Boolean remember) {
        if (!active) return;
        BiConsumer<String, Boolean> cb = onResult;
        hide();
        if (cb != null) cb.accept(path, remember);
    }

    /** Esc / Ctrl+C/D / "No" — cancel, carrying the confirmed path if the dialog got that far. */
    private void cancel() {
        resolve(confirmedPath, null);
    }

    private synchronized void hide() {
        cancelBackgroundWorkLocked();
        active = false;
        validator = null;
        onResult = null;
        invalidate();
    }

    private void cancelBackgroundWorkLocked() {
        suggestionGeneration++;
        validationGeneration++;
        validationInFlight = false;
        if (pendingSuggestionTask != null) {
            pendingSuggestionTask.cancel(false);
            pendingSuggestionTask = null;
        }
    }



    private static List<DirSuggestion> computeSuggestions(String rawInput) {
        if (StringUtils.isEmpty(rawInput)) return List.of();
        String expanded = PathUtils.expandTilde(rawInput);
        Path resolved = Path.of(expanded).isAbsolute()
            ? Path.of(expanded)
            : Path.of(System.getProperty("user.dir")).resolve(expanded);

        Path directory;
        String prefix;
        if (Strings.CS.endsWith(rawInput, "/")) {
            directory = resolved;
            prefix = "";
        } else {
            directory = resolved.getParent();
            if (directory == null) directory = Path.of(System.getProperty("user.dir"));
            Path fileName = resolved.getFileName();
            prefix = fileName != null ? fileName.toString() : "";
        }
        if (!Files.isDirectory(directory)) return List.of();

        String prefixLower = prefix.toLowerCase(Locale.ROOT);
        List<DirSuggestion> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isDirectory)
                .filter(p -> !Strings.CS.startsWith(p.getFileName().toString(), "."))
                .filter(p -> Strings.CI.startsWith(p.getFileName().toString(), prefixLower))
                .sorted()
                .limit(MAX_SUGGESTIONS)
                .forEach(p -> out.add(new DirSuggestion(
                    p.toAbsolutePath().normalize().toString(), p.getFileName().toString() + "/")));
        } catch (Exception _) {}
        return out;
    }

    // ── sizing ───────────────────────────────────────────────────────────────

    private int totalRows() {
        if (phase == Phase.INPUT) {
            return 8 + suggestions.size() + (inputError != null || validationInFlight ? 1 : 0);
        }
        return 11;
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()), totalRows());
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class DialogArea extends AbstractComponent<DialogArea> {
        @Override protected ComponentRenderer<DialogArea> createDefaultRenderer() {
            return new DialogRenderer();
        }
    }

    private final class DialogRenderer implements ComponentRenderer<DialogArea> {

        @Override
        public TerminalSize getPreferredSize(DialogArea c) {
            return new TerminalSize(LEFT_PAD * 2 + MIN_WIDTH, totalRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, DialogArea c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();

            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, TITLE);
            g.disableModifiers(SGR.BOLD);

            if (phase == Phase.INPUT) {
                drawInputPhase(g, cols);
            } else {
                drawConfirmPhase(g, cols);
            }
        }

        private void drawInputPhase(TextGUIGraphics g, int cols) {
            g.setForegroundColor(LanternaTheme.welcomeDim());

            g.putString(LEFT_PAD, 3, InlineOverlay.clip(PERMISSION_DESCRIPTION, cols - LEFT_PAD));

            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 5, "Enter the path to the directory:");

            g.setForegroundColor(LanternaTheme.inputText());
            if (input.isEmpty()) {
                g.putString(LEFT_PAD, 6, InlineOverlay.clip("› █", cols - LEFT_PAD));
                g.setForegroundColor(LanternaTheme.ghostText());
                g.putString(LEFT_PAD + 3, 6, InlineOverlay.clip("Directory path…", cols - LEFT_PAD - 3));
            } else {
                g.putString(LEFT_PAD, 6, InlineOverlay.clip("› " + input + "█", cols - LEFT_PAD));
            }

            int row = 7;
            for (int i = 0; i < suggestions.size(); i++) {
                DirSuggestion s = suggestions.get(i);
                boolean selected = i == suggestionIdx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.ghostText());
                g.putString(LEFT_PAD, row, (selected ? "❯ " : "  ") + s.displayName());
                row++;
            }

            if (inputError != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, row, InlineOverlay.clip(inputError, cols - LEFT_PAD));
                row++;
            } else if (validationInFlight) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "Validating directory…");
                row++;
            }

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row, "Tab to complete · Enter to add · Esc to cancel");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawConfirmPhase(TextGUIGraphics g, int cols) {
            g.setForegroundColor(LanternaTheme.permission());
            g.putString(LEFT_PAD, 3, InlineOverlay.clip(confirmedPath, cols - LEFT_PAD));

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 4, InlineOverlay.clip(PERMISSION_DESCRIPTION, cols - LEFT_PAD));

            for (int i = 0; i < REMEMBER_OPTIONS.size(); i++) {
                int row = 6 + i;
                RememberOption opt = REMEMBER_OPTIONS.get(i);
                boolean selected = i == rememberIdx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, (selected ? "❯ " : "  ") + opt.label());
            }

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, 10, "Enter to confirm · Esc to cancel");
            g.disableModifiers(SGR.ITALIC);
        }

    }
}
