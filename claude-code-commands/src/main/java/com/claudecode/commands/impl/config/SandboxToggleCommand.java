package com.claudecode.commands.impl.config;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.runtime.settings.SettingsManagementPort;
import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.core.platform.Platform;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * /sandbox — configure command sandboxing.
 */
public class SandboxToggleCommand implements Command {

    private final ToolingCommandPorts.Sandbox sandbox;
    private final Supplier<SandboxConfig> configSupplier;
    private final BooleanSupplier policyLocked;

    public SandboxToggleCommand() {
        this(SettingsManagementPort.none().sandbox(), ToolingCommandPorts.none().sandbox());
    }

    public SandboxToggleCommand(SettingsManagementPort.Sandbox settings) {
        this(settings, ToolingCommandPorts.none().sandbox());
    }

    public SandboxToggleCommand(SettingsManagementPort.Sandbox settings,
                                ToolingCommandPorts.Sandbox sandbox) {
        this(sandbox, settings::config, settings::lockedByPolicy);
    }

    SandboxToggleCommand(ToolingCommandPorts.Sandbox sandbox,
                         Supplier<SandboxConfig> configSupplier,
                         BooleanSupplier policyLocked) {
        this.sandbox = sandbox;
        this.configSupplier = configSupplier;
        this.policyLocked = policyLocked;
    }

    @Override
    public CommandMetadata metadata() {
        SandboxConfig cfg = configSupplier.get();
        ToolingCommandPorts.Sandbox.Status sandboxStatus = sandbox.status(cfg);
        boolean dependenciesReady = sandboxStatus.dependenciesAvailable();
        boolean currentlyEnabled = cfg.enabled()
            && sandboxStatus.platformSupported()
            && dependenciesReady;
        String icon = dependenciesReady ? (currentlyEnabled ? "✓" : "○") : "⚠";
        String status = "sandbox disabled";
        if (currentlyEnabled) {
            status = cfg.autoAllowBashIfSandboxed()
                ? "sandbox enabled (auto-allow)" : "sandbox enabled";
            if (cfg.allowUnsandboxedCommands()) status += ", fallback allowed";
        }
        if (policyLocked.getAsBoolean()) status += " (managed)";
        return new CommandMetadata("sandbox", icon + " " + status + " (⏎ to configure)");
    }

    @Override
    public String argumentHint() {
        return "exclude \"command pattern\"";
    }

    @Override
    public boolean isImmediate() {
        return true;
    }

    @Override
    public boolean isHidden() {
        SandboxConfig cfg = configSupplier.get();
        return !isNativePlatformSupported() || !isPlatformInEnabledList(cfg);
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        SandboxConfig cfg = configSupplier.get();
        if (!isNativePlatformSupported()) {
            String message = Platform.IS_WSL
                ? "Error: Sandboxing requires WSL2. WSL1 is not supported."
                : "Error: Sandboxing is currently only supported on macOS, Linux, and WSL2.";
            return CommandResult.of(message);
        }
        if (!isPlatformInEnabledList(cfg)) {
            return CommandResult.of("Error: Sandboxing is disabled for this platform ("
                + platformName() + ") via the enabledPlatforms setting.");
        }
        if (policyLocked.getAsBoolean()) {
            return CommandResult.of("Error: Sandbox settings are overridden by a higher-priority "
                + "configuration and cannot be changed locally.");
        }

        String raw = (args == null) ? "" : args.trim();
        if (raw.isEmpty()) {
            Runnable launcher = context.presentation().sandboxDialogLauncher();
            if (launcher != null) {
                launcher.run();
                return CommandResult.skip();
            }
            return CommandResult.of("The sandbox settings panel requires the interactive REPL.");
        }
        String[] parts = raw.split(" ", 2);
        String subcommand = parts[0];
        if (Strings.CS.equals("exclude", subcommand)) {
            String pattern = raw.substring("exclude".length()).trim();
            if (pattern.isEmpty()) {
                return CommandResult.of("Error: Please provide a command pattern to exclude "
                    + "(e.g., /sandbox exclude \"npm run test:*\")");
            }
            pattern = stripEdgeQuotes(pattern);
            String cwd = StringUtils.isBlank(context.session().workingDirectory())
                ? System.getProperty("user.dir") : context.session().workingDirectory();
            String relative = context.application().settings().sandbox()
                .addExcludedCommand(cwd, pattern);
            return CommandResult.of("Added \"" + pattern
                + "\" to excluded commands in " + relative);
        }
        return CommandResult.of("Error: Unknown subcommand \"" + subcommand
            + "\". Available subcommand: exclude");
    }

    private static String stripEdgeQuotes(String value) {
        String out = value;
        if (!out.isEmpty() && (out.charAt(0) == '\'' || out.charAt(0) == '"')) {
            out = out.substring(1);
        }
        if (!out.isEmpty() && (out.charAt(out.length() - 1) == '\''
                || out.charAt(out.length() - 1) == '"')) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String platformName() {
        if (Platform.IS_WSL) return "wsl";
        return switch (Platform.CURRENT) {
            case DARWIN -> "macos";
            case LINUX -> "linux";
            case WIN32 -> "windows";
            default -> "other";
        };
    }

    private static boolean isNativePlatformSupported() {
        if (Platform.CURRENT == Platform.OTHER) return false;
        return !Platform.IS_WSL || Platform.WSL_VERSION >= 2;
    }

    private static boolean isPlatformInEnabledList(SandboxConfig cfg) {
        if (cfg.enabledPlatforms() == null) return true;
        if (cfg.enabledPlatforms().isEmpty()) return false;
        Set<String> names = switch (Platform.CURRENT) {
            case DARWIN -> Set.of("darwin", "macos");
            case LINUX -> Platform.IS_WSL ? Set.of("linux", "wsl") : Set.of("linux");
            case WIN32 -> Set.of("win32", "windows");
            default -> Set.of("other");
        };
        return cfg.enabledPlatforms().stream().anyMatch(names::contains);
    }

}
