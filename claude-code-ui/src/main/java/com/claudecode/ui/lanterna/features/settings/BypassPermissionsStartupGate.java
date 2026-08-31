package com.claudecode.ui.lanterna.features.settings;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;


public final class BypassPermissionsStartupGate {

    public interface View {
        void prompt(Runnable onAccept, Runnable onDecline, Runnable onEscape);
    }

    private final BooleanSupplier bypassRequested;
    private final BooleanSupplier acknowledgementPresent;
    private final Runnable persistAcknowledgement;
    private final View view;

    public BypassPermissionsStartupGate(BooleanSupplier bypassRequested,
                                 BooleanSupplier acknowledgementPresent,
                                 Runnable persistAcknowledgement,
                                 View view) {
        this.bypassRequested = bypassRequested;
        this.acknowledgementPresent = acknowledgementPresent;
        this.persistAcknowledgement = persistAcknowledgement;
        this.view = view;
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
