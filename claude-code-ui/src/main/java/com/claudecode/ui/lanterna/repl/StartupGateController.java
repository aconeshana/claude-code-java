package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.memory.MemoryCatalog.Scope;
import com.claudecode.runtime.startup.StartupTrustPort;
import com.claudecode.ui.lanterna.dialog.ManagedSettingsUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.features.settings.UiSettings;

/**
 * Drives the ordered interactive startup security gates independently from the REPL view root.
 */
final class StartupGateController {

    interface View {
        void promptTrust(Path cwd, Runnable onAccept, Runnable onExit);
        void promptExternalIncludes(Path cwd, List<String> paths,
                                    Runnable onAllow, Runnable onDisable, Runnable onExit);
        void promptManagedSettings(Path cwd, List<String> items,
                                   Runnable onAccept, Runnable onExit);
    }

    private final StartupTrustPort trust;
    private final MemoryCatalog memoryCatalog;
    private final View view;
    private final Supplier<List<String>> dangerousManagedSettings;
    private final Consumer<String> warningSink;
    private final Consumer<String> trustedEnvironmentApplier;

    StartupGateController(StartupTrustPort trust, MemoryCatalog memoryCatalog, View view) {
        this(trust, memoryCatalog, view,
            StartupGateController::loadDangerousManagedSettings, _ -> {},
            UiSettings::applyTrustedEnvironment);
    }

    StartupGateController(StartupTrustPort trust,
                          MemoryCatalog memoryCatalog,
                          View view,
                          Supplier<List<String>> dangerousManagedSettings,
                          Consumer<String> warningSink) {
        this(trust, memoryCatalog, view, dangerousManagedSettings, warningSink,
            UiSettings::applyTrustedEnvironment);
    }

    StartupGateController(StartupTrustPort trust,
                          MemoryCatalog memoryCatalog,
                          View view,
                          Supplier<List<String>> dangerousManagedSettings,
                          Consumer<String> warningSink,
                          Consumer<String> trustedEnvironmentApplier) {
        this.trust = trust != null ? trust : StartupTrustPort.trustAll();
        this.memoryCatalog = memoryCatalog != null ? memoryCatalog : MemoryCatalog.empty();
        this.view = view;
        this.dangerousManagedSettings = dangerousManagedSettings != null
            ? dangerousManagedSettings : StartupGateController::loadDangerousManagedSettings;
        this.warningSink = warningSink != null ? warningSink : _ -> {};
        this.trustedEnvironmentApplier = trustedEnvironmentApplier != null
            ? trustedEnvironmentApplier : _ -> {};
    }

    void start(Path cwd, Runnable onReady, BiConsumer<String, Integer> onExit) {
        trust.migrateLegacyTrust();
        if (!trust.isTrustAccepted(cwd)) {
            view.promptTrust(cwd,
                () -> {
                    trust.acceptTrust(cwd);
                    continueAfterTrust(cwd, onReady, onExit);
                },
                () -> onExit.accept("Trust declined by user", 1));
            return;
        }
        continueAfterTrust(cwd, onReady, onExit);
    }

    private void continueAfterTrust(Path cwd, Runnable onReady,
                                    BiConsumer<String, Integer> onExit) {
        boolean decided = trust.hasExternalIncludesApproved(cwd)
            || trust.hasExternalIncludesWarningShown(cwd);
        if (decided) {
            showManagedSettings(cwd, onReady, onExit);
            return;
        }
        List<String> externals = externalIncludes(cwd);
        if (externals.isEmpty()) {
            showManagedSettings(cwd, onReady, onExit);
            return;
        }
        view.promptExternalIncludes(cwd, externals,
            () -> {
                trust.saveExternalIncludesDecision(cwd, true);
                showManagedSettings(cwd, onReady, onExit);
            },
            () -> {
                trust.saveExternalIncludesDecision(cwd, false);
                showManagedSettings(cwd, onReady, onExit);
            },
            () -> onExit.accept("External includes dialog aborted by user", 1));
    }

    private void showManagedSettings(Path cwd, Runnable onReady,
                                     BiConsumer<String, Integer> onExit) {
        Runnable applyTrustedEnvironment = () -> {

            // external-include, and managed-settings trust gates complete.
            trustedEnvironmentApplier.accept(cwd.toString());
            onReady.run();
        };
        List<String> items = dangerousManagedSettings.get();
        if (items == null || items.isEmpty()) {
            applyTrustedEnvironment.run();
            return;
        }
        view.promptManagedSettings(cwd, items, applyTrustedEnvironment,
            () -> onExit.accept("Managed settings not trusted by user", 1));
    }

    List<String> externalIncludes(Path cwd) {
        try {
            List<MemoryCatalog.File> files = memoryCatalog.scan(cwd);
            Path cwdNorm = cwd.toAbsolutePath().normalize();
            List<String> result = new ArrayList<>();
            for (MemoryCatalog.File file : files) {
                if (file.scope() != Scope.USER
                        && file.parent() != null
                        && !file.path().toAbsolutePath().normalize().startsWith(cwdNorm)) {
                    result.add(file.path().toString());
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            warningSink.accept(e.getMessage());
            return List.of();
        }
    }

    private static List<String> loadDangerousManagedSettings() {
        var dangerous = ManagedSettingsUtils.extractDangerousSettings(
            ManagedSettingsUtils.loadManagedSettings());
        return ManagedSettingsUtils.hasDangerousSettings(dangerous)
            ? ManagedSettingsUtils.formatDangerousSettingsList(dangerous)
            : List.of();
    }
}
