package com.claudecode.ui.lanterna.plugin;

import com.claudecode.runtime.plugins.PluginMarketplacePort.ErrorView;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ErrorAction;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.List;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.bold;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import static com.claudecode.ui.lanterna.components.StyledText.seg;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Errors tab: the current session's plugin errors with per-error remediation guidance.
 */
final class PluginErrorsTab {

    private final PluginPanelServices services;
    private final PluginPanelHost host;

    private List<ErrorView> errors = List.of();
    private int selectedIndex;
    private boolean processing;
    private String processError;

    PluginErrorsTab(PluginPanelServices services, PluginPanelHost host) {
        this.services = services;
        this.host = host;
    }

    void open() {
        errors = services.plugins().errors();
        if (errors == null) {
            errors = List.of();
        }
        selectedIndex = 0;
        processing = false;
        processError = null;
    }

    boolean allowsTabSwitch() {
        return true;
    }

    synchronized void handleKey(KeyStroke key) {
        if (processing) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            host.closePanel();
            return;
        }
        if (isUp(key)) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return;
        }
        if (isDown(key)) {
            selectedIndex = Math.min(Math.max(0, errors.size() - 1), selectedIndex + 1);
            return;
        }
        if (t == KeyType.ENTER && selectedIndex >= 0 && selectedIndex < errors.size()) {
            ErrorView error = errors.get(selectedIndex);
            if (error.target() != null) {
                switch (error.action()) {
                    case UNINSTALL_PLUGIN -> host.switchToInstalled(error.target(), null, "uninstall");
                    case REMOVE_EXTRA_MARKETPLACE -> {
                        processing = true;
                        processError = null;
                        services.background().execute(() -> {
                            try {
                        services.plugins().removeExtraMarketplace(error.target());
                                host.postToGui(() -> {
                                    synchronized (PluginErrorsTab.this) {
                                        processing = false;
                        errors = new ArrayList<>(errors);
                                        errors.remove(error);
                                        selectedIndex = Math.min(selectedIndex,
                                            Math.max(0, errors.size() - 1));
                                    }
                                    host.refresh();
                                });
                            } catch (RuntimeException failure) {
                                host.postToGui(() -> {
                                    synchronized (PluginErrorsTab.this) {
                                        processing = false;
                                        processError = failure.getMessage();
                                    }
                                    host.refresh();
                                });
                            }
                        });
                    }
                    case REMOVE_INSTALLED_MARKETPLACE ->
                        host.switchToMarketplaces(error.target(), "remove");
                    default -> { }
                }
            }
        }
    }

    synchronized List<StyledText.Line> buildLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        if (processing) lines.add(line("Processing…", LanternaTheme.welcomeDim()));
        if (processError != null) lines.add(line(processError, LanternaTheme.toolError()));
        if (errors.isEmpty()) {
            lines.add(line("No plugin errors", LanternaTheme.welcomeDim()));
            lines.add(blank());
            lines.add(line("Esc to go back", LanternaTheme.welcomeDim()));
            return lines;
        }
        for (int i = 0; i < errors.size(); i++) {
            ErrorView error = errors.get(i);
            boolean selected = i == selectedIndex;
            lines.add(line(
                seg(selected ? "❯ " : "✖ ",
                    selected ? LanternaTheme.suggestion() : LanternaTheme.toolError()),
                selected
                    ? bold(error.source(), LanternaTheme.inputText())
                    : seg(error.source(), LanternaTheme.inputText())));
            lines.add(line("   " + error.message(), LanternaTheme.toolError()));
            String guidance = guidanceFor(error);
            if (guidance != null) {
                lines.add(line("   " + guidance, LanternaTheme.welcomeDim()));
            }
            lines.add(blank());
        }
        boolean canResolve = errors.stream().anyMatch(error -> error.action() != ErrorAction.NONE
            && error.action() != ErrorAction.MANAGED_ONLY);
        lines.add(line(canResolve ? "↑ to navigate · Enter to resolve · Esc to go back"
            : "↑ to navigate · Esc to go back", LanternaTheme.welcomeDim()));
        return lines;
    }


    static String guidanceFor(ErrorView error) {
        return error.guidance();
    }

    private static boolean isUp(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_UP
            || (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'k' && !key.isCtrlDown() && !key.isAltDown());
    }

    private static boolean isDown(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_DOWN
            || (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() == 'j' && !key.isCtrlDown() && !key.isAltDown());
    }

    // ── test accessors ────────────────────────────────────────────────────────

    int selectedIndex() {
        return selectedIndex;
    }

    List<ErrorView> errors() {
        return errors;
    }
}
