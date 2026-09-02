package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.ui.lanterna.dialog.AddDirDialog;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline {@code /permissions} rule-management panel — sits above {@link InputPanel} in the
 * SmartLayout stack, occupying zero rows when idle.
 */
public final class PermissionsPanel extends Panel implements InlineOverlay {

    enum Tab { RECENTLY_DENIED, ALLOW, ASK, DENY, WORKSPACE }

    private static final int LEFT_PAD = 2;

    private boolean active;
    private Tab selectedTab = Tab.ALLOW;
    private boolean headerFocused;

    private final RecentDenialsTab recentDenialsTab;
    private final PermissionRulesTab allowTab;
    private final PermissionRulesTab askTab;
    private final PermissionRulesTab denyTab;
    private final WorkspaceTab workspaceTab;

    private Runnable onClose;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    PermissionsPanel() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));

        HeaderArea header = new HeaderArea();
        header.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(header);

        recentDenialsTab = new RecentDenialsTab();
        allowTab = new PermissionRulesTab(PermissionBehavior.ALLOW);
        askTab = new PermissionRulesTab(PermissionBehavior.ASK);
        denyTab = new PermissionRulesTab(PermissionBehavior.DENY);
        workspaceTab = new WorkspaceTab();

        for (Panel p : new Panel[] {
                recentDenialsTab, allowTab, askTab, denyTab, workspaceTab}) {
            p.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
            addComponent(p);
        }

        Runnable focusHeader = () -> {
            headerFocused = true;
            syncHeaderFocus();
            invalidate();
        };
        Runnable closePanel = this::closeWithoutChanges;
        allowTab.setOnFocusHeaderRequest(focusHeader);
        askTab.setOnFocusHeaderRequest(focusHeader);
        denyTab.setOnFocusHeaderRequest(focusHeader);
        workspaceTab.setOnFocusHeaderRequest(focusHeader);
        allowTab.setOnCloseRequest(closePanel);
        askTab.setOnCloseRequest(closePanel);
        denyTab.setOnCloseRequest(closePanel);
        workspaceTab.setOnCloseRequest(closePanel);
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
        allowTab.setKeybindingsStore(store);
        askTab.setKeybindingsStore(store);
        denyTab.setKeybindingsStore(store);
        workspaceTab.setKeybindingsStore(store);
    }

    void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        allowTab.setGuiInvoker(guiInvoker);
        askTab.setGuiInvoker(guiInvoker);
        denyTab.setGuiInvoker(guiInvoker);
        workspaceTab.setGuiInvoker(guiInvoker);
    }

    /**
     * Activates the panel.
     */
    synchronized void show(Tab defaultTab,
                           Supplier<PermissionGate> gateSupplier,
                           Supplier<String> cwdSupplier,
                           Function<String, AddDirDialog.ValidationOutcome> dirValidator,
                           BiConsumer<String, Boolean> onAddDirectoryResult,
                           BiConsumer<String, TextColor> changeRecorder,
                           Runnable onClose) {
        this.selectedTab = defaultTab;
        this.headerFocused = true;
        this.onClose = onClose;
        this.active = true;

        allowTab.bind(gateSupplier, cwdSupplier);
        askTab.bind(gateSupplier, cwdSupplier);
        denyTab.bind(gateSupplier, cwdSupplier);
        workspaceTab.bind(gateSupplier, cwdSupplier, dirValidator, onAddDirectoryResult);
        allowTab.setChangeRecorder(changeRecorder);
        askTab.setChangeRecorder(changeRecorder);
        denyTab.setChangeRecorder(changeRecorder);
        workspaceTab.setChangeRecorder(changeRecorder);

        syncHeaderFocus();
        syncTabVisibility();
        invalidate();
    }

    private void closeWithoutChanges() {
        active = false;
        invalidate();
        Runnable cb = onClose;
        onClose = null;
        if (cb != null) cb.run();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;

        if (!headerFocused) {
            if (contentAllowsTabSwitch() && key.getKeyType() == KeyType.ARROW_LEFT) {
                switchTab(-1);
                return;
            }
            if (contentAllowsTabSwitch() && key.getKeyType() == KeyType.ARROW_RIGHT) {
                switchTab(1);
                return;
            }
            dispatchToContent(key, deliver);
            return;
        }

        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();
        deliver.set(false);

        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve(List.of("Tabs", "Settings"), key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            switch (value) {
                case "tabs:previous" -> { switchTab(-1); return; }
                case "tabs:next" -> { switchTab(1); return; }
                case "confirm:no" -> { closeWithoutChanges(); return; }
                default -> { }
            }
        }

        if (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null
                && (Character.toLowerCase(ch) == 'c' || Character.toLowerCase(ch) == 'd')) {
            closeWithoutChanges();
            return;
        }
        if (t == KeyType.ARROW_LEFT)  { switchTab(-1); return; }
        if (t == KeyType.ARROW_RIGHT) { switchTab(1);  return; }
        if (t == KeyType.ARROW_DOWN) {
            if (selectedTab == Tab.RECENTLY_DENIED) return;
            headerFocused = false;
            syncHeaderFocus();
            invalidate();
        }
    }

    private boolean contentAllowsTabSwitch() {
        return switch (selectedTab) {
            case RECENTLY_DENIED -> false;
            case ALLOW -> allowTab.mode() == PermissionRulesTab.Mode.LIST;
            case ASK -> askTab.mode() == PermissionRulesTab.Mode.LIST;
            case DENY -> denyTab.mode() == PermissionRulesTab.Mode.LIST;
            case WORKSPACE -> workspaceTab.mode() == WorkspaceTab.Mode.LIST;
        };
    }

    private void dispatchToContent(KeyStroke key, AtomicBoolean deliver) {
        switch (selectedTab) {
            case RECENTLY_DENIED -> { }
            case ALLOW -> allowTab.handleKey(key, deliver);
            case ASK -> askTab.handleKey(key, deliver);
            case DENY -> denyTab.handleKey(key, deliver);
            case WORKSPACE -> workspaceTab.handleKey(key, deliver);
        }
    }

    private void switchTab(int delta) {
        Tab[] tabs = Tab.values();
        selectedTab = tabs[InlineOverlay.cycleIndex(selectedTab.ordinal(), delta, tabs.length)];
        headerFocused = true;
        syncHeaderFocus();
        syncTabVisibility();
        invalidate();
    }

    private void syncTabVisibility() {
        recentDenialsTab.setTabVisible(selectedTab == Tab.RECENTLY_DENIED);
        allowTab.setTabVisible(selectedTab == Tab.ALLOW);
        askTab.setTabVisible(selectedTab == Tab.ASK);
        denyTab.setTabVisible(selectedTab == Tab.DENY);
        workspaceTab.setTabVisible(selectedTab == Tab.WORKSPACE);
        switch (selectedTab) {
            case RECENTLY_DENIED -> recentDenialsTab.reload();
            case ALLOW -> allowTab.reload();
            case ASK -> askTab.reload();
            case DENY -> denyTab.reload();
            case WORKSPACE -> workspaceTab.reload();
        }
    }

    private void syncHeaderFocus() {
        allowTab.setHeaderFocused(headerFocused);
        askTab.setHeaderFocused(headerFocused);
        denyTab.setHeaderFocused(headerFocused);
        workspaceTab.setHeaderFocused(headerFocused);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Sizing / focus
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test accessors (package-private)
    // ──────────────────────────────────────────────────────────────────────────

    Tab selectedTab() { return selectedTab; }
    boolean headerFocused() { return headerFocused; }
    PermissionRulesTab allowTab() { return allowTab; }
    PermissionRulesTab askTab() { return askTab; }
    PermissionRulesTab denyTab() { return denyTab; }
    WorkspaceTab workspaceTab() { return workspaceTab; }

    // ──────────────────────────────────────────────────────────────────────────
    // Header renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class HeaderArea extends AbstractComponent<HeaderArea> {
        @Override protected ComponentRenderer<HeaderArea> createDefaultRenderer() {
            return new HeaderRenderer();
        }
    }

    private final class HeaderRenderer implements ComponentRenderer<HeaderArea> {

        @Override
        public TerminalSize getPreferredSize(HeaderArea c) {
            if (!active) return new TerminalSize(0, 0);
            return new TerminalSize(64, 3);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, HeaderArea c) {
            if (!active) return;
            g.fill(' ');
            int columns = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, columns)));

            int col = LEFT_PAD;
            g.setForegroundColor(LanternaTheme.professionalBlue());
            g.enableModifiers(SGR.BOLD);
            g.putString(col, 1, "Permissions");
            g.disableModifiers(SGR.BOLD);
            col += "Permissions".length() + 1;
            for (Tab tab : Tab.values()) {
                boolean isCurrent = tab == selectedTab;
                String label = " " + tabLabel(tab) + " ";
                if (isCurrent && headerFocused) {
                    g.setBackgroundColor(LanternaTheme.claude());
                    g.setForegroundColor(LanternaTheme.inverseText());
                } else {
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(isCurrent ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                }
                if (isCurrent) g.enableModifiers(SGR.BOLD);
                g.putString(col, 1, label);
                g.disableModifiers(SGR.BOLD);
                col += label.length() + 1;
            }
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    private static String tabLabel(Tab tab) {
        return switch (tab) {
            case RECENTLY_DENIED -> "Recently denied";
            case ALLOW -> "Allow";
            case ASK -> "Ask";
            case DENY -> "Deny";
            case WORKSPACE -> "Workspace";
        };
    }

    private static final class RecentDenialsTab extends Panel {
        private boolean tabVisible;

        private RecentDenialsTab() {
            super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
            Body body = new Body();
            body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
            addComponent(body);
        }

        private void setTabVisible(boolean visible) {
            tabVisible = visible;
            invalidate();
        }

        private void reload() {
            invalidate();
        }

        @Override
        public synchronized TerminalSize calculatePreferredSize() {
            return tabVisible ? super.calculatePreferredSize() : new TerminalSize(0, 0);
        }

        private final class Body extends AbstractComponent<Body> {
            @Override
            protected ComponentRenderer<Body> createDefaultRenderer() {
                return new ComponentRenderer<>() {
                    @Override
                    public TerminalSize getPreferredSize(Body component) {
                        return tabVisible ? new TerminalSize(78, 4) : new TerminalSize(0, 0);
                    }

                    @Override
                    public void drawComponent(TextGUIGraphics g, Body component) {
                        if (!tabVisible) return;
                        g.fill(' ');
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.putString(LEFT_PAD, 0,
                            "No recent denials. Commands denied by the auto mode classifier will appear");
                        g.putString(LEFT_PAD, 1, "here.");
                        g.putString(LEFT_PAD, 3,
                            "←/→ to switch · ↓ to select · Esc to cancel");
                    }
                };
            }
        }
    }
}
