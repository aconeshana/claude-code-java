package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared two-choice confirmation state used by inline dialogs.
 */
final class ConfirmationPrompt {

    enum Choice { CONFIRM, CANCEL }

    record Option(Choice choice, String label) { }

    private final List<Option> options;
    private final Choice initialFocus;
    private final Runnable invalidator;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    private int focusedIndex;
    private boolean active;
    private boolean resolved;
    private Runnable onConfirm;
    private Runnable onCancel;
    private Runnable onDismiss;

    ConfirmationPrompt(String confirmLabel, String cancelLabel,
                       boolean cancelFirst, Choice initialFocus,
                       Runnable invalidator) {
        Objects.requireNonNull(confirmLabel, "confirmLabel");
        Objects.requireNonNull(cancelLabel, "cancelLabel");
        this.initialFocus = Objects.requireNonNull(initialFocus, "initialFocus");
        this.invalidator = invalidator != null ? invalidator : () -> { };
        Option confirm = new Option(Choice.CONFIRM, confirmLabel);
        Option cancel = new Option(Choice.CANCEL, cancelLabel);
        this.options = cancelFirst ? List.of(cancel, confirm) : List.of(confirm, cancel);
        this.focusedIndex = indexOf(initialFocus);
    }

    synchronized void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    synchronized void activate(Runnable onConfirm, Runnable onCancel) {
        activate(onConfirm, onCancel, onCancel);
    }

    synchronized void activate(Runnable onConfirm, Runnable onCancel,
                               Runnable onDismiss) {
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.onDismiss = onDismiss;
        this.focusedIndex = indexOf(initialFocus);
        this.resolved = false;
        this.active = true;
        invalidator.run();
    }

    synchronized void deactivate() {
        active = false;
        resolved = false;
        onConfirm = null;
        onCancel = null;
        onDismiss = null;
    }

    List<Option> options() {
        return options;
    }

    synchronized Choice focusedChoice() {
        return options.get(focusedIndex).choice();
    }

    synchronized boolean isFocused(Choice choice) {
        return focusedChoice() == choice;
    }

    void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!isActive()) return;
        ContextKeybindingDispatcher.Result result = keybindings.resolve("Select", key);
        if (result instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (result instanceof ContextKeybindingDispatcher.Result.Action action
                && dispatchSelectAction(action.value())) {
            deliver.set(false);
            return;
        }
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP || type == KeyType.ARROW_LEFT
                || isCharacter(key, 'k')) {
            moveFocus(-1);
            deliver.set(false);
            return;
        }
        if (type == KeyType.ARROW_DOWN || type == KeyType.ARROW_RIGHT
                || isCharacter(key, 'j')) {
            moveFocus(1);
            deliver.set(false);
            return;
        }
        if (type == KeyType.ENTER) {
            resolve(focusedChoice());
            deliver.set(false);
            return;
        }
        if (type == KeyType.ESCAPE) {
            dismiss();
            deliver.set(false);
        }
    }

    private synchronized boolean isActive() {
        return active;
    }

    private boolean dispatchSelectAction(String action) {
        return switch (action) {
            case "select:previous" -> { moveFocus(-1); yield true; }
            case "select:next" -> { moveFocus(1); yield true; }
            case "select:accept" -> { resolve(focusedChoice()); yield true; }
            case "select:cancel" -> { dismiss(); yield true; }
            default -> false;
        };
    }

    private synchronized void moveFocus(int delta) {
        if (!active || resolved) return;
        focusedIndex = InlineOverlay.cycleIndex(focusedIndex, delta, options.size());
        invalidator.run();
    }

    private void resolve(Choice choice) {
        Runnable callback;
        synchronized (this) {
            if (!active || resolved) return;
            resolved = true;
            callback = choice == Choice.CONFIRM ? onConfirm : onCancel;
            onConfirm = null;
            onCancel = null;
            onDismiss = null;
        }
        if (callback != null) callback.run();
    }

    private void dismiss() {
        Runnable callback;
        synchronized (this) {
            if (!active || resolved) return;
            resolved = true;
            callback = onDismiss;
            onConfirm = null;
            onCancel = null;
            onDismiss = null;
        }
        if (callback != null) callback.run();
    }

    private int indexOf(Choice choice) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).choice() == choice) return i;
        }
        throw new IllegalArgumentException("Choice is not present: " + choice);
    }

    private static boolean isCharacter(KeyStroke key, char expected) {
        return key.getKeyType() == KeyType.CHARACTER
            && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == expected;
    }
}
