package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.dialog.BypassPermissionsModeDialog;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import java.util.List;
import com.googlecode.lanterna.gui2.Component;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;


public final class BypassPermissionsStartupGate implements ReplFeature {

    public interface View {
        void prompt(Runnable onAccept, Runnable onDecline, Runnable onEscape);
    }

    private final BooleanSupplier bypassRequested;
    private final BooleanSupplier acknowledgementPresent;
    private final Runnable persistAcknowledgement;
    private final BypassPermissionsModeDialog dialog;
    private final View view;

    /**
     * Builds the gate with a self-constructed {@link BypassPermissionsModeDialog}, so callers no
     * longer hold the dialog field directly. Extracted from {@code LanternaReplScreen}.
     */
    public static BypassPermissionsStartupGate standard(BooleanSupplier bypassRequested,
                                 BooleanSupplier acknowledgementPresent,
                                 Runnable persistAcknowledgement,
                                 int terminalRows,
                                 UserKeybindingsStore keybindingsStore) {
        BypassPermissionsModeDialog dialog = new BypassPermissionsModeDialog(terminalRows);
        dialog.setKeybindingsStore(keybindingsStore);
        return new BypassPermissionsStartupGate(
            bypassRequested, acknowledgementPresent, persistAcknowledgement, dialog, dialog::prompt);
    }

    public BypassPermissionsStartupGate(BooleanSupplier bypassRequested,
                                 BooleanSupplier acknowledgementPresent,
                                 Runnable persistAcknowledgement,
                                 View view) {
        this(bypassRequested, acknowledgementPresent, persistAcknowledgement, null, view);
    }

    private BypassPermissionsStartupGate(BooleanSupplier bypassRequested,
                                 BooleanSupplier acknowledgementPresent,
                                 Runnable persistAcknowledgement,
                                 BypassPermissionsModeDialog dialog,
                                 View view) {
        this.bypassRequested = bypassRequested;
        this.acknowledgementPresent = acknowledgementPresent;
        this.persistAcknowledgement = persistAcknowledgement;
        this.dialog = dialog;
        this.view = view;
    }

    @Override public List<InlineOverlay> overlays() {
        return List.of(dialog);
    }

    public Component view() {
        return dialog;
    }

    public void start(Runnable onReady, BiConsumer<String, Integer> onExit) {
        if (!bypassRequested.getAsBoolean()
                || acknowledgementPresent.getAsBoolean()) {
            onReady.run();
            return;
        }
        view.prompt(
            () -> {
                persistAcknowledgement.run();
                onReady.run();
            },
            () -> onExit.accept("Bypass permissions declined by user", 1),
            () -> onExit.accept("Bypass permissions dialog cancelled by user", 0));
    }
}
