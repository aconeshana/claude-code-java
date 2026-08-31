package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Interactive {@code /sandbox} settings panel.
 */
public final class SandboxSettingsDialog extends Panel implements InlineOverlay {

    enum Tab {
        MODE("Mode"), DEPENDENCIES("Dependencies"), OVERRIDES("Overrides"), CONFIG("Config");

        private final String title;
        Tab(String title) { this.title = title; }
        String title() { return title; }
    }

    private enum Mode { AUTO_ALLOW, REGULAR, DISABLED }
    private enum OverrideMode { OPEN, CLOSED }

    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 78;
    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;
    private static final List<String> MODE_LABELS = List.of(
        "Sandbox BashTool, with auto-allow",
        "Sandbox BashTool, with regular permissions",
        "No Sandbox");
    private static final List<String> MODE_CONFIRM = List.of(
        "✓ Sandbox enabled with auto-allow for bash commands",
        "✓ Sandbox enabled with regular bash permissions",
        "○ Sandbox disabled");
    private static final List<String> OVERRIDE_LABELS = List.of(
        "Allow unsandboxed fallback", "Strict sandbox mode");

    private boolean active;
    private List<Tab> tabs = List.of();
    private int selectedTabIndex;
    private boolean headerFocused;
    private int selectedMode;
    private int currentMode;
    private int selectedOverride;
    private int currentOverride;
    private SandboxConfig config = SandboxConfig.disabled();
    private UiSettings.SandboxDependencyStatus dependencies =
        UiSettings.SandboxDependencyStatus.READY;
    private boolean policyLocked;
    private boolean commitInFlight;
    private Character pendingExitKey;
    private long pendingExitTime;
    private Consumer<String> onResult;
    private Consumer<Runnable> guiInvoker;

    public SandboxSettingsDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }


    public synchronized void prompt(Consumer<String> onResult) {
        SandboxConfig loadedConfig = UiSettings.readSandboxConfig();
        UiSettings.SandboxDependencyStatus loadedDependencies =
            UiSettings.readSandboxDependencyStatus();
        prompt(loadedConfig, loadedDependencies,
            UiSettings.areSandboxSettingsLockedByPolicy(), onResult);
    }

    public synchronized void prompt(SandboxConfig loadedConfig,
                                    UiSettings.SandboxDependencyStatus loadedDependencies,
                                    boolean loadedPolicyLocked,
                                    Consumer<String> onResult) {
        config = loadedConfig != null ? loadedConfig : SandboxConfig.disabled();
        dependencies = loadedDependencies != null
            ? loadedDependencies : UiSettings.SandboxDependencyStatus.READY;
        policyLocked = loadedPolicyLocked;
        tabs = buildTabs(dependencies);
        selectedTabIndex = 0;
        headerFocused = true;
        currentMode = computeCurrentMode(config);
        selectedMode = 0;
        currentOverride = config.allowUnsandboxedCommands()
            ? OverrideMode.OPEN.ordinal() : OverrideMode.CLOSED.ordinal();
        selectedOverride = 0;
        pendingExitKey = null;
        pendingExitTime = 0;
        commitInFlight = false;
        this.onResult = onResult;
        active = true;
        invalidate();
    }

    public synchronized void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
    }

    private static List<Tab> buildTabs(UiSettings.SandboxDependencyStatus status) {
        if (!status.errors().isEmpty()) return List.of(Tab.DEPENDENCIES);
        List<Tab> result = new ArrayList<>();
        result.add(Tab.MODE);
        if (!status.warnings().isEmpty()) result.add(Tab.DEPENDENCIES);
        result.add(Tab.OVERRIDES);
        result.add(Tab.CONFIG);
        return List.copyOf(result);
    }

    private static int computeCurrentMode(SandboxConfig cfg) {
        if (!cfg.enabled()) return Mode.DISABLED.ordinal();
        return cfg.autoAllowBashIfSandboxed()
            ? Mode.AUTO_ALLOW.ordinal() : Mode.REGULAR.ordinal();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        deliver.set(false);
        if (commitInFlight) return;
        KeyType type = key.getKeyType();

        if (type == KeyType.ESCAPE) {
            resolve(null);
            return;
        }
        if (type == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (ch == 'c' || ch == 'd') handleDoublePress(ch);
            return;
        }

        if (headerFocused) {
            handleHeaderKey(key);
        } else {
            handleContentKey(key);
        }
    }

    private void handleHeaderKey(KeyStroke key) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_LEFT) {
            selectTab(-1);
        } else if (type == KeyType.ARROW_RIGHT || type == KeyType.TAB) {
            selectTab(1);
        } else if (type == KeyType.ARROW_DOWN && selectedTabHasInteractiveContent()) {
            headerFocused = false;
            invalidate();
        }
    }

    private void selectTab(int delta) {
        selectedTabIndex = InlineOverlay.cycleIndex(selectedTabIndex, delta, tabs.size());
        headerFocused = true;
        pendingExitKey = null;
        invalidate();
    }

    private boolean selectedTabHasInteractiveContent() {
        return selectedTab() == Tab.MODE
            || (selectedTab() == Tab.OVERRIDES && config.enabled() && !policyLocked);
    }

    private void handleContentKey(KeyStroke key) {
        if (selectedTab() == Tab.MODE) {
            selectedMode = handleSelectorKey(key, selectedMode, MODE_LABELS.size(), this::commitMode);
        } else if (selectedTab() == Tab.OVERRIDES && config.enabled() && !policyLocked) {
            selectedOverride = handleSelectorKey(
                key, selectedOverride, OVERRIDE_LABELS.size(), this::commitOverride);
        } else {
            headerFocused = true;
        }
        invalidate();
    }

    private int handleSelectorKey(KeyStroke key, int selected, int size, Runnable commit) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP
                || isCharacter(key, 'k')) {
            if (selected == 0) {
                headerFocused = true;
                return selected;
            }
            return selected - 1;
        }
        if (type == KeyType.ARROW_DOWN || isCharacter(key, 'j')) {
            return InlineOverlay.cycleIndex(selected, 1, size);
        }
        if (type == KeyType.ENTER) commit.run();
        return selected;
    }

    private static boolean isCharacter(KeyStroke key, char expected) {
        return key.getKeyType() == KeyType.CHARACTER
            && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == expected;
    }

    private void commitMode() {
        Mode mode = Mode.values()[selectedMode];
        runCommit(() -> {
            switch (mode) {
                case AUTO_ALLOW -> UiSettings.writeSandboxSettings(true, true, null);
                case REGULAR -> UiSettings.writeSandboxSettings(true, false, null);
                case DISABLED -> UiSettings.writeSandboxSettings(false, false, null);
            }
        }, MODE_CONFIRM.get(mode.ordinal()));
    }

    private void commitOverride() {
        boolean allow = selectedOverride == OverrideMode.OPEN.ordinal();
        runCommit(() -> UiSettings.writeSandboxSettings(null, null, allow), allow
            ? "✓ Unsandboxed fallback allowed - commands can run outside sandbox when necessary"
            : "✓ Strict sandbox mode - all commands must run in sandbox or be excluded via the `excludedCommands` option");
    }

    private void runCommit(Runnable write, String successMessage) {
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null) {
            write.run();
            resolve(successMessage);
            return;
        }
        commitInFlight = true;
        invalidate();
        Thread.ofVirtual().name("sandbox-settings-write").start(() -> {
            try {
                write.run();
                invoker.accept(() -> resolve(successMessage));
            } catch (RuntimeException _) {
                invoker.accept(() -> {
                    commitInFlight = false;
                    invalidate();
                });
            }
        });
    }

    private void handleDoublePress(char ch) {
        if (pendingExitKey != null && pendingExitKey == ch
                && System.currentTimeMillis() - pendingExitTime <= DOUBLE_PRESS_TIMEOUT_MS) {
            resolve(null);
            return;
        }
        pendingExitKey = ch;
        pendingExitTime = System.currentTimeMillis();
        invalidate();
        long armedAt = pendingExitTime;
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(DOUBLE_PRESS_TIMEOUT_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (SandboxSettingsDialog.this) {
                if (active && pendingExitKey != null && pendingExitKey == ch
                        && pendingExitTime == armedAt) {
                    pendingExitKey = null;
                    invalidate();
                }
            }
        });
    }

    private synchronized void resolve(String message) {
        if (!active) return;
        Consumer<String> callback = onResult;
        active = false;
        commitInFlight = false;
        pendingExitKey = null;
        pendingExitTime = 0;
        onResult = null;
        invalidate();
        if (callback != null) callback.accept(message);
    }

    List<String> contentLines() {
        return switch (selectedTab()) {
            case MODE -> modeLines();
            case DEPENDENCIES -> dependencyLines();
            case OVERRIDES -> overrideLines();
            case CONFIG -> configLines();
        };
    }

    private List<String> modeLines() {
        List<String> lines = new ArrayList<>();
        if (!dependencies.warnings().isEmpty() && !config.network().allowAllUnixSockets()) {
            lines.add("⚠ Cannot block unix domain sockets (see Dependencies tab)");
        }
        lines.add("Configure Mode:");
        for (int i = 0; i < MODE_LABELS.size(); i++) {
            lines.add(optionLine(MODE_LABELS.get(i), i == selectedMode, i == currentMode));
        }
        lines.add("");
        lines.add("Auto-allow mode: Commands try the sandbox automatically; fallback uses regular permissions.");
        lines.add("Explicit ask/deny rules are always respected.");
        lines.add("Learn more: code.claude.com/docs/en/sandboxing");
        return List.copyOf(lines);
    }

    private List<String> dependencyLines() {
        List<String> lines = new ArrayList<>();
        for (String error : dependencies.errors()) lines.add("✗ " + error);
        for (String warning : dependencies.warnings()) lines.add("⚠ " + warning);
        if (dependencies.errors().isEmpty() && dependencies.warnings().isEmpty()) {
            lines.add("✓ Sandbox dependencies are available");
        }
        return List.copyOf(lines);
    }

    private List<String> overrideLines() {
        if (!config.enabled()) {
            return List.of("Sandbox is not enabled. Enable sandbox to configure override settings.");
        }
        if (policyLocked) {
            return List.of(
                "Override settings are managed by a higher-priority configuration and cannot be changed locally.",
                "Current setting: " + (config.allowUnsandboxedCommands()
                    ? "Allow unsandboxed fallback" : "Strict sandbox mode"));
        }
        List<String> lines = new ArrayList<>();
        lines.add("Configure Overrides:");
        for (int i = 0; i < OVERRIDE_LABELS.size(); i++) {
            lines.add(optionLine(OVERRIDE_LABELS.get(i), i == selectedOverride, i == currentOverride));
        }
        lines.add("");
        lines.add("Allow unsandboxed fallback: Claude may retry outside the sandbox with regular permissions.");
        lines.add("Strict sandbox mode: all model bash commands must be sandboxed or explicitly excluded.");
        lines.add("Learn more: code.claude.com/docs/en/sandboxing#configure-sandboxing");
        return List.copyOf(lines);
    }

    private List<String> configLines() {
        List<String> lines = new ArrayList<>();
        if (!config.enabled()) {
            lines.add("Sandbox is not enabled");
            dependencies.warnings().forEach(w -> lines.add("⚠ " + w));
            return List.copyOf(lines);
        }
        lines.add("Excluded Commands: " + (config.excludedCommands().isEmpty()
            ? "None" : String.join(", ", config.excludedCommands())));

        SandboxConfig.SandboxFilesystemConfig fs = config.filesystem();
        if (!fs.denyRead().isEmpty() || !fs.allowRead().isEmpty()) {
            lines.add("Filesystem Read Restrictions:");
            if (!fs.denyRead().isEmpty()) lines.add("  Denied: " + String.join(", ", fs.denyRead()));
            if (!fs.allowRead().isEmpty()) lines.add("  Allowed within denied: " + String.join(", ", fs.allowRead()));
        }
        if (!fs.allowWrite().isEmpty() || !fs.denyWrite().isEmpty()) {
            lines.add("Filesystem Write Restrictions:");
            if (!fs.allowWrite().isEmpty()) lines.add("  Allowed: " + String.join(", ", fs.allowWrite()));
            if (!fs.denyWrite().isEmpty()) lines.add("  Denied within allowed: " + String.join(", ", fs.denyWrite()));
        }

        SandboxConfig.SandboxNetworkConfig net = config.network();
        if (!net.allowedDomains().isEmpty() || !net.deniedDomains().isEmpty()) {
            lines.add("Network Restrictions" + (net.allowManagedDomainsOnly() ? " (Managed):" : ":"));
            if (!net.allowedDomains().isEmpty()) lines.add("  Allowed: " + String.join(", ", net.allowedDomains()));
            if (!net.deniedDomains().isEmpty()) lines.add("  Denied: " + String.join(", ", net.deniedDomains()));
        }
        if (!net.allowUnixSockets().isEmpty()) {
            lines.add("Allowed Unix Sockets: " + String.join(", ", net.allowUnixSockets()));
        }
        dependencies.warnings().forEach(w -> lines.add("⚠ " + w));
        return List.copyOf(lines);
    }

    private static String optionLine(String label, boolean selected, boolean current) {
        return (selected ? "❯ " : "  ") + label + (current ? " (current)" : "");
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()), totalRows());
    }

    private int totalRows() {
        return 4 + contentLines().size() + 1;
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    private final class DialogArea extends AbstractComponent<DialogArea> {
        @Override protected ComponentRenderer<DialogArea> createDefaultRenderer() {
            return new DialogRenderer();
        }
    }

    private final class DialogRenderer implements ComponentRenderer<DialogArea> {
        @Override public TerminalSize getPreferredSize(DialogArea component) {
            if (!active) return new TerminalSize(0, 0);
            return new TerminalSize(MIN_WIDTH, totalRows());
        }

        @Override public void drawComponent(TextGUIGraphics graphics, DialogArea component) {
            if (!active) return;
            graphics.fill(' ');
            int columns = graphics.getSize().getColumns();
            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(0, 0, "─".repeat(Math.max(0, columns)));

            drawTabs(graphics, columns);
            int row = 3;
            for (String line : contentLines()) {
                graphics.setForegroundColor(Strings.CS.startsWith(line, "✗")
                    ? LanternaTheme.toolError()
                    : Strings.CS.startsWith(line, "⚠") ? LanternaTheme.toolWarning()
                    : Strings.CS.startsWith(line, "❯") ? LanternaTheme.suggestion()
                    : LanternaTheme.inputText());
                graphics.putString(LEFT_PAD, row++, InlineOverlay.clip(line, columns - LEFT_PAD));
            }

            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.enableModifiers(SGR.ITALIC);
            graphics.putString(LEFT_PAD, row, InlineOverlay.clip(footerText(), columns - LEFT_PAD));
            graphics.disableModifiers(SGR.ITALIC);
        }

        private void drawTabs(TextGUIGraphics graphics, int columns) {
            int x = LEFT_PAD;
            graphics.setForegroundColor(LanternaTheme.permission());
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(x, 1, "Sandbox:");
            graphics.disableModifiers(SGR.BOLD);
            x += "Sandbox:".length() + 1;
            for (int i = 0; i < tabs.size(); i++) {
                String label = " " + tabs.get(i).title() + " ";
                boolean selected = i == selectedTabIndex;
                graphics.setForegroundColor(selected && headerFocused
                    ? LanternaTheme.inverseText() : LanternaTheme.inputText());
                if (selected && headerFocused) graphics.setBackgroundColor(LanternaTheme.permission());
                if (selected) graphics.enableModifiers(SGR.BOLD);
                graphics.putString(x, 1, InlineOverlay.clip(label, Math.max(0, columns - x)));
                graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
                if (selected) graphics.disableModifiers(SGR.BOLD);
                x += label.length() + 1;
            }
        }

        private String footerText() {
            if (pendingExitKey != null) {
                return "Press Ctrl+" + Character.toUpperCase(pendingExitKey) + " again to cancel";
            }
            if (headerFocused) {
                return selectedTabHasInteractiveContent()
                    ? "←/→ tabs · ↓ select · Esc cancel"
                    : "←/→ tabs · Esc cancel";
            }
            return "Enter to confirm · ↑ tabs · Esc cancel";
        }
    }

    List<Tab> tabs() { return tabs; }
    Tab selectedTab() { return tabs.get(selectedTabIndex); }
    boolean headerFocused() { return headerFocused; }
}
