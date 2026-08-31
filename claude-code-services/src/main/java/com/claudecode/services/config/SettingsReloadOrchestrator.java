package com.claudecode.services.config;

import java.util.Locale;

import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Wires {@link SettingsHotReloader} to the live {@link PermissionGate} and {@link HookEngine} so
 * external edits to (or its project / local siblings) take effect in the current session within ~1s
 * without a restart.
 */
public final class SettingsReloadOrchestrator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsReloadOrchestrator.class);

    private final PermissionGate permissionGate;
    private final HookEngine hookEngine;
    private volatile Consumer<String> uiSink;
    private final String cwd;
    private final boolean trustImplicitlyEstablished;
    private final SettingsHotReloader reloader;
    private final AutoCloseable flagSettingsSubscription;
    private final CopyOnWriteArrayList<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    /**
     * @param permissionGate live gate — regime updated via {@link PermissionGate#syncFromDisk}
     * @param hookEngine     live hook engine — base settings updated via {@link HookEngine#replaceSettings}
     * @param cwd            working directory (drives project/local settings paths)
     * @param uiSink         optional callback for user-visible notification;
     *                       pass {@code null} for CLI/headless mode (log-only)
     */
    public SettingsReloadOrchestrator(PermissionGate permissionGate,
                                       HookEngine hookEngine,
                                       String cwd,
                                       Consumer<String> uiSink) {
        this(permissionGate, hookEngine, cwd, uiSink, false);
    }

    /**
     * Constructs a reload orchestrator with an explicit non-interactive trust state.
     */
    public SettingsReloadOrchestrator(PermissionGate permissionGate,
                                       HookEngine hookEngine,
                                       String cwd,
                                       Consumer<String> uiSink,
                                       boolean trustImplicitlyEstablished) {
        this.permissionGate = permissionGate;
        this.hookEngine = hookEngine;
        this.cwd = cwd;
        this.uiSink = uiSink;
        this.trustImplicitlyEstablished = trustImplicitlyEstablished;
        this.reloader = new SettingsHotReloader(
            SettingsPaths.userSettingsPath(),
            SettingsPaths.sessionProjectSettingsPath(cwd),
            SettingsPaths.sessionLocalSettingsPath(cwd),
            SettingsPaths.policySettingsPath(),
            new SettingsChangeListener() {
                @Override
                public void onChange(RuleSource source) {
                    reload(source);
                }

                @Override
                public void onChange(RuleSource source, Path path) {
// A null path is emitted by the MDM/registry poll, which.
                    if (path == null) {
                        reload(source, null, false);
                    } else {
                        reload(source, path);
                    }
                }
        });
        this.flagSettingsSubscription = SettingsSources.subscribeFlagSettingsChanged(

            // which fans out directly and does not run ConfigChange hooks. The
            // same rule applies to the process-local flag overlay here.
            () -> reload(RuleSource.FLAG_SETTINGS, null, false));
    }

    /** Starts the underlying file watcher. Safe to call multiple times. */
    public void start() throws IOException {
        reloader.start();
    }

    /**
     * Installs a UI sink after construction — used by the Lanterna wiring
     * where the {@code LanternaReplScreen} is only ready after the
     * orchestrator has already been built and started. Passing {@code null}
     * clears the sink (falls back to log-only).
     */
    public void setUiSink(Consumer<String> sink) {
        this.uiSink = sink;
    }

    /**
     * Subscribes to successful reload events.
     */
    public AutoCloseable subscribeReload(Runnable listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        reloadListeners.add(listener);
        return () -> reloadListeners.remove(listener);
    }

    @Override
    public void close() {
        reloader.close();
        try {
            flagSettingsSubscription.close();
        } catch (Exception e) {
            LOG.debug("Failed to unsubscribe flag settings listener: {}", e.getMessage());
        }
    }

    /**
     * Re-reads the layered settings sources and applies the merged view to the
     * permission gate + hook engine. Exposed as package-private + also used as
     * the reloader's {@link SettingsChangeListener} callback.
     */
    void reload(RuleSource source) {
        String path = sourcePath(source);
        reload(source, path == null ? null : Path.of(path));
    }

    private void reload(RuleSource source, Path changedPath) {
        reload(source, changedPath, true);
    }

    private void reload(RuleSource source, Path changedPath, boolean runConfigChangeHook) {
        try {
            String configSource = configChangeSource(source);
            if (runConfigChangeHook && hookEngine.dispatchConfigChange(configSource,
                    changedPath == null ? sourcePath(source) : changedPath.toString())) {
                LOG.info("ConfigChange hook blocked settings reload ({})", configSource);
                notifyUi("⚠ Settings reload blocked by ConfigChange hook");
                return;
            }

            // hooks allow the update. Do this for file and process-local flags.
            SettingsSnapshots.invalidateForReload();

            // env application path. Before trust only the safe allowlist may
            // be applied; this matters because the watcher can start before
            // the interactive trust/managed-settings dialogs finish.
            if (trustImplicitlyEstablished || TrustConfigStore.isTrustAccepted(Path.of(cwd))) {
                ManagedEnvironmentApplier.applyConfigEnvironmentVariables();
            } else {
                ManagedEnvironmentApplier.applySafeConfigEnvironmentVariables(cwd);
            }

// loadAllPermissionRulesFromDisk then skips that source and
            // continues with the remaining enabled tiers.  Keep strict
// loadPermissionRules for callers that need diagnostics, but
            // use the per-source lenient path for live change notifications.
            List<PermissionRule> diskRules = PermissionSettings.loadPermissionRulesForReload(cwd);
            // Enterprise managed mode: when allowManagedPermissionRulesOnly is on,
            // strip non-policy sources (cliArg / session / disk) so only policy

            boolean managedPermissionRulesOnly =
                PermissionSettings.shouldAllowManagedPermissionRulesOnly();
            permissionGate.syncFromDisk(diskRules, managedPermissionRulesOnly);


            // setting later does not re-enable bypass in this process. A new
            // process recomputes the capability from its startup settings.
            permissionGate.setBypassPermissionsModeDisabled(
                PermissionSettings.isBypassPermissionsModeDisabled());

            // Recompute the complete effective hooks snapshot for every source
            // change.  A project/local edit must not discard hooks supplied by
            // the other tiers, and --settings/SDK flag updates must be visible

            HooksSettings newHooks = HookSettings.loadHooksSettings();
            hookEngine.replaceSettings(newHooks);
            hookEngine.replaceHttpHookPolicy(HookSettings.loadHttpHookPolicy());

            int hookCount = countHooks(newHooks);
            String label = friendlySource(source);
            LOG.info("Settings reloaded ({}): {} permission rules, {} hooks",
                label, diskRules.size(), hookCount);
            notifyUi(String.format(
                "⚙ Settings reloaded (%s): %d permission rules, %d hooks",
                label, diskRules.size(), hookCount));
            fireReloadListeners();
        } catch (Exception e) {
            LOG.warn("Failed to reload settings from {}: {}. Keeping previous state.",
                source, e.getMessage(), e);
            notifyUi("⚠ Settings reload failed: " + e.getMessage()
                + ". Previous configuration retained.");
        }
    }

    private void notifyUi(String message) {
        if (uiSink == null) return;
        try {
            uiSink.accept(message);
        } catch (Exception e) {
            LOG.debug("UI sink threw for reload notification: {}", e.getMessage());
        }
    }

    private void fireReloadListeners() {
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.debug("Reload listener threw: {}", e.getMessage());
            }
        }
    }

    private static int countHooks(HooksSettings hooks) {
        if (hooks == null) return 0;
        return hooks.eventHooks().values().stream()
            .mapToInt(List::size)
            .sum();
    }

    private static String friendlySource(RuleSource source) {
        return switch (source) {
            case USER_SETTINGS    -> "user settings";
            case PROJECT_SETTINGS -> "project settings";
            case LOCAL_SETTINGS   -> "local settings";
            default               -> source.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private String sourcePath(RuleSource source) {
        return switch (source) {
            case USER_SETTINGS -> SettingsPaths.userSettingsPath().toString();
            case PROJECT_SETTINGS -> SettingsPaths.sessionProjectSettingsPath(cwd).toString();
            case LOCAL_SETTINGS -> SettingsPaths.sessionLocalSettingsPath(cwd).toString();
            case POLICY_SETTINGS -> SettingsPaths.policySettingsPath().toString();
            default -> null;
        };
    }

    private static String configChangeSource(RuleSource source) {
        return switch (source) {
            case USER_SETTINGS -> "user_settings";
            case PROJECT_SETTINGS -> "project_settings";
            case LOCAL_SETTINGS -> "local_settings";
            case POLICY_SETTINGS, FLAG_SETTINGS -> "policy_settings";
            default -> source.name().toLowerCase(Locale.ROOT);
        };
    }
}
