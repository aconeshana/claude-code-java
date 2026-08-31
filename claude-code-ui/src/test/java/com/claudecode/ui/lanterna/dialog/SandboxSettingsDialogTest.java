package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxConfig.SandboxFilesystemConfig;
import com.claudecode.core.engine.SandboxConfig.SandboxNetworkConfig;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;


class SandboxSettingsDialogTest {

    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke RIGHT = new KeyStroke(KeyType.ARROW_RIGHT);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);

    @AfterEach
    void resetBackend() {
        UiSettings.configure(null);
    }

    @Test
    void idleChildRendererCanBeMeasuredBeforeFirstPrompt() {
        SandboxSettingsDialog dialog = new SandboxSettingsDialog();

        assertFalse(dialog.isActive());
        assertEquals(new TerminalSize(0, 0), dialog.calculatePreferredSize());
        TerminalSize childSize = assertDoesNotThrow(
            () -> dialog.getChildren().iterator().next().getPreferredSize(),
            "Lanterna measures child renderers while the overlay is still idle");
        assertEquals(new TerminalSize(0, 0), childSize);
    }

    @Test
    void dependencyErrorsExposeOnlyDependenciesTab() {
        TestBackend backend = new TestBackend(disabled(),
            new UiSettings.SandboxDependencyStatus(List.of("ripgrep not found"), List.of()), false);
        UiSettings.configure(backend);
        SandboxSettingsDialog dialog = opened();

        assertEquals(List.of(SandboxSettingsDialog.Tab.DEPENDENCIES), dialog.tabs());
        assertEquals(SandboxSettingsDialog.Tab.DEPENDENCIES, dialog.selectedTab());
        send(dialog, DOWN);
        assertTrue(dialog.headerFocused(), "static dependency content does not opt into content focus");
    }

    @Test
    void warningsInsertDependenciesBetweenModeAndOverrides() {
        TestBackend backend = new TestBackend(disabled(),
            new UiSettings.SandboxDependencyStatus(List.of(), List.of("seccomp filter unavailable")), false);
        UiSettings.configure(backend);
        SandboxSettingsDialog dialog = opened();

        assertEquals(List.of(SandboxSettingsDialog.Tab.MODE,
            SandboxSettingsDialog.Tab.DEPENDENCIES,
            SandboxSettingsDialog.Tab.OVERRIDES,
            SandboxSettingsDialog.Tab.CONFIG), dialog.tabs());
        assertTrue(dialog.headerFocused());
        send(dialog, RIGHT);
        assertEquals(SandboxSettingsDialog.Tab.DEPENDENCIES, dialog.selectedTab());
        send(dialog, LEFT);
        assertEquals(SandboxSettingsDialog.Tab.MODE, dialog.selectedTab());
    }

    @Test
    void modeSelectionPersistsThroughUiSettingsPortAndCompletes() {
        TestBackend backend = new TestBackend(disabled(), UiSettings.SandboxDependencyStatus.READY, false);
        UiSettings.configure(backend);
        AtomicReference<String> result = new AtomicReference<>();
        SandboxSettingsDialog dialog = new SandboxSettingsDialog();
        dialog.prompt(result::set);

        send(dialog, DOWN);  // header -> mode selector
        assertFalse(dialog.headerFocused());
        send(dialog, ENTER); // auto-allow (first option)

        assertEquals(Boolean.TRUE, backend.enabled);
        assertEquals(Boolean.TRUE, backend.autoAllow);
        assertEquals("✓ Sandbox enabled with auto-allow for bash commands", result.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void overridesSelectWritesStrictModeWhenEnabledAndUnlocked() {
        TestBackend backend = new TestBackend(enabled(true, true),
            UiSettings.SandboxDependencyStatus.READY, false);
        UiSettings.configure(backend);
        SandboxSettingsDialog dialog = opened();

        send(dialog, RIGHT); // Overrides (no Dependencies tab when ready)
        assertEquals(SandboxSettingsDialog.Tab.OVERRIDES, dialog.selectedTab());
        send(dialog, DOWN);  // content focus, current=open index 0
        send(dialog, DOWN);  // strict index 1
        send(dialog, ENTER);

        assertEquals(Boolean.FALSE, backend.allowUnsandboxed);
    }

    @Test
    void policyLockedOverridesRemainStatic() {
        TestBackend backend = new TestBackend(enabled(true, true),
            UiSettings.SandboxDependencyStatus.READY, true);
        UiSettings.configure(backend);
        SandboxSettingsDialog dialog = opened();

        send(dialog, RIGHT);
        send(dialog, DOWN);

        assertTrue(dialog.headerFocused());
        assertNull(backend.allowUnsandboxed);
        assertTrue(dialog.contentLines().stream()
            .anyMatch(line -> Strings.CS.contains(line, "managed by a higher-priority configuration")));
    }

    @Test
    void configTabReportsExcludedFilesystemAndNetworkSettings() {
        SandboxConfig cfg = new SandboxConfig(true, false, true, false, false, false,
            List.of("docker *"), null, Map.of(),
            new SandboxNetworkConfig(List.of("api.example.com"), List.of("blocked.example.com"),
                List.of("/tmp/agent.sock"), false, false, false, null, null),
            new SandboxFilesystemConfig(List.of("/readonly"), List.of("/workspace"),
                List.of("/secret"), List.of("/tmp"), false), null);
        UiSettings.configure(new TestBackend(cfg, UiSettings.SandboxDependencyStatus.READY, false));
        SandboxSettingsDialog dialog = opened();

        send(dialog, RIGHT); // Overrides
        send(dialog, RIGHT); // Config

        assertEquals(SandboxSettingsDialog.Tab.CONFIG, dialog.selectedTab());
        List<String> lines = dialog.contentLines();
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, "Excluded Commands: docker *")));
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, "Filesystem Read Restrictions")));
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, "Network Restrictions")));
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, "Allowed Unix Sockets")));
    }

    private static SandboxSettingsDialog opened() {
        SandboxSettingsDialog dialog = new SandboxSettingsDialog();
        dialog.prompt(_ -> { });
        return dialog;
    }

    private static void send(SandboxSettingsDialog dialog, KeyStroke key) {
        dialog.handleKey(key, new AtomicBoolean(true));
    }

    private static SandboxConfig disabled() {
        return SandboxConfig.disabled();
    }

    private static SandboxConfig enabled(boolean autoAllow, boolean allowUnsandboxed) {
        SandboxConfig d = SandboxConfig.disabled();
        return new SandboxConfig(true, false, autoAllow, allowUnsandboxed, false, false,
            List.of(), null, Map.of(), d.network(), d.filesystem(), null);
    }

    private static final class TestBackend implements UiSettings.Backend {
        private final SandboxConfig config;
        private final UiSettings.SandboxDependencyStatus dependencies;
        private final boolean locked;
        private Boolean enabled;
        private Boolean autoAllow;
        private Boolean allowUnsandboxed;

        private TestBackend(SandboxConfig config,
                            UiSettings.SandboxDependencyStatus dependencies,
                            boolean locked) {
            this.config = config;
            this.dependencies = dependencies;
            this.locked = locked;
        }

        @Override public boolean globalBoolean(String key, boolean defaultValue) { return defaultValue; }
        @Override public String globalString(String key, String defaultValue) { return defaultValue; }
        @Override public int globalInt(String key, int defaultValue) { return defaultValue; }
        @Override public void setGlobal(String key, Object value) { }
        @Override public boolean spinnerTipsEnabled() { return true; }
        @Override public boolean prefersReducedMotion() { return false; }
        @Override public Boolean policyBoolean(String key) { return null; }
        @Override public SandboxConfig sandboxConfig() { return config; }
        @Override public UiSettings.SandboxDependencyStatus sandboxDependencyStatus() { return dependencies; }
        @Override public boolean sandboxSettingsLockedByPolicy() { return locked; }
        @Override public void setSandboxSettings(Boolean enabled, Boolean autoAllow,
                                                 Boolean allowUnsandboxed) {
            this.enabled = enabled;
            this.autoAllow = autoAllow;
            this.allowUnsandboxed = allowUnsandboxed;
        }
        @Override public void addPermissionRule(String cwd, PermissionBehavior behavior,
                                                String ruleString, RuleSource tier) { }
        @Override public void removePermissionRule(String cwd, PermissionBehavior behavior,
                                                   String ruleString, RuleSource tier) { }
    }
}
