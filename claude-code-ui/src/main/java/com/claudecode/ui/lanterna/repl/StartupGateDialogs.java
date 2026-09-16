package com.claudecode.ui.lanterna.repl;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.dialog.ClaudeMdExternalIncludesDialog;
import com.claudecode.ui.lanterna.dialog.ManagedSettingsSecurityDialog;
import com.claudecode.ui.lanterna.dialog.TrustFolderDialog;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.gui2.Component;
import java.nio.file.Path;
import java.util.List;

/**
 * The three startup gate dialogs behind {@link StartupGateController.View}: trust-this-folder,
 * managed-settings security notice, and CLAUDE.md external-includes confirmation. Each collapses
 * to (0,0) when idle and is activated at most once per REPL startup by the controller.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code components/TrustDialog/TrustDialog.tsx} — workspace trust prompt.</li>
 *   <li>{@code components/ManagedSettingsSecurityDialog/} — managed-settings warning.</li>
 *   <li>{@code components/ClaudeMdExternalIncludesDialog.tsx} — external @-include approval.</li>
 * </ul>
 */
final class StartupGateDialogs implements StartupGateController.View, ReplFeature {

    private final TrustFolderDialog trust = new TrustFolderDialog();
    private final ManagedSettingsSecurityDialog managedSettings = new ManagedSettingsSecurityDialog();
    private final ClaudeMdExternalIncludesDialog externalIncludes = new ClaudeMdExternalIncludesDialog();

    StartupGateDialogs(UserKeybindingsStore keybindingsStore) {
        trust.setKeybindingsStore(keybindingsStore);
        managedSettings.setKeybindingsStore(keybindingsStore);
        externalIncludes.setKeybindingsStore(keybindingsStore);
    }

    @Override
    public List<InlineOverlay> overlays() {
        return List.of(trust, managedSettings, externalIncludes);
    }

    Component trustView() { return trust; }
    Component managedSettingsView() { return managedSettings; }
    Component externalIncludesView() { return externalIncludes; }

    @Override
    public void promptTrust(Path cwd, Runnable onAccept, Runnable onExit) {
        trust.prompt(cwd, onAccept, onExit);
    }

    @Override
    public void promptExternalIncludes(Path cwd, List<String> paths,
                                       Runnable onAllow, Runnable onDisable, Runnable onExit) {
        externalIncludes.prompt(cwd, paths, onAllow, onDisable, onExit);
    }

    @Override
    public void promptManagedSettings(Path cwd, List<String> items,
                                      Runnable onAccept, Runnable onExit) {
        managedSettings.prompt(cwd, items, onAccept, onExit);
    }
}
