package com.claudecode.cli;

import com.claudecode.runtime.query.AutoDreamEngine;
import com.claudecode.core.engine.StreamingClient;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Immutable hand-off from Picocli parsing to CLI startup.
 *
 * <ul>
 *   <li>freezes parsed CLI arguments
 *       before delegating into the application entry point.</li>
 *   <li>carries launch flags through setup and
 *       selects SDK, headless, or interactive execution without rereading
 *       mutable argv state.</li>
 *   <li>derives the structured-input and
 *       non-interactive output modes from the parsed print flags.</li>
 * </ul>
 */
record CliLaunchRequest(
        ModelOptions model,
        OutputOptions output,
        SessionOptions session,
        WorkspaceOptions workspace,
        PermissionOptions permissions,
        TestOverrides testOverrides,
        Mode mode) {

    CliLaunchRequest {
        model = model != null ? model : ModelOptions.EMPTY;
        output = output != null ? output : OutputOptions.EMPTY;
        session = session != null ? session : SessionOptions.EMPTY;
        workspace = workspace != null ? workspace : WorkspaceOptions.EMPTY;
        permissions = permissions != null ? permissions : PermissionOptions.EMPTY;
        testOverrides = testOverrides != null ? testOverrides : TestOverrides.EMPTY;
        mode = mode != null ? mode : Mode.from(output, session);
    }

    record ModelOptions(
            String effort,
            String thinkingMode,
            String thinkingDisplay,
            Integer maxThinkingTokens,
            Integer taskBudget,
            List<String> betas,
            String fallbackModel,
            String model,
            String apiKey,
            String systemPrompt,
            String systemPromptFile,
            String appendSystemPrompt,
            String appendSystemPromptFile,
            String planModeInstructions,
            Integer maxTokens,
            int maxTurns,
            double maxBudgetUsd,
            String jsonSchemaRaw,
            String baseUrl) {
        ModelOptions {
            betas = immutableCopy(betas);
        }

        static final ModelOptions EMPTY = new ModelOptions(
            null, null, null, null, null, List.of(), null, null, null, null, null, null,
            null, null, null, 0, 0.0, null, null);
    }

    record OutputOptions(
            String outputFormat,
            String inputFormat,
            boolean includePartialMessages,
            boolean includeHookEvents,
            boolean replayUserMessages,
            boolean sessionMirror,
            String permissionPromptToolName,
            String debugFile,
            boolean noInteractive,
            boolean verbose,
            boolean debug,
            boolean printMode) {
        static final OutputOptions EMPTY = new OutputOptions(
            "text", "text", false, false, false, false, null, null,
            false, false, false, false);
    }

    record SessionOptions(
            boolean continueLastSession,
            String resumeSession,
            String sessionId,
            String name,
            String theme,
            String initialPrompt,
            boolean forkSession,
            String resumeSessionAt,
            String rewindFiles,
            boolean noSessionPersistence,
            boolean init,
            boolean initOnly,
            boolean maintenance) {
        SessionOptions {
            name = StringUtils.trimToNull(name);
        }

        static final SessionOptions EMPTY = new SessionOptions(
            false, null, null, null, null, null, false, null, null, false,
            false, false, false);

        String setupTrigger() {
            if (maintenance) return "maintenance";
            return init || initOnly ? "init" : null;
        }
    }

    record WorkspaceOptions(
            String settingsFileOrJson,
            String settingSourcesRaw,
            List<String> mcpConfig,
            boolean strictMcpConfig,
            List<String> additionalDirectories,
            List<String> pluginDirectories,
            List<String> pluginDirectoriesNoMcp,
            String managedSettings,
            String agent,
            String agentsJson,
            String cwdOverride,
            String worktreeName,
            boolean disableSlashCommands) {
        WorkspaceOptions {
            mcpConfig = immutableCopy(mcpConfig);
            additionalDirectories = immutableCopy(additionalDirectories);
            pluginDirectories = immutableCopy(pluginDirectories);
            pluginDirectoriesNoMcp = immutableCopy(pluginDirectoriesNoMcp);
        }

        static final WorkspaceOptions EMPTY = new WorkspaceOptions(
            null, null, List.of(), false, List.of(), List.of(), List.of(), null,
            null, null,
            null, null, false);
    }

    record PermissionOptions(
            boolean dangerouslySkipPermissions,
            boolean allowDangerouslySkipPermissions,
            String permissionMode,
            List<String> allowedTools,
            List<String> disallowedTools,
            List<String> baseTools) {
        PermissionOptions {
            allowedTools = immutableCopy(allowedTools);
            disallowedTools = immutableCopy(disallowedTools);
            // Null means the --tools option was omitted. An explicit empty
            // collection has different filtering semantics, so retain that
            // Picocli distinction while still defensively copying present data.
            baseTools = baseTools == null ? null : immutableCopy(baseTools);
        }

        static final PermissionOptions EMPTY = new PermissionOptions(
            false, false, null, List.of(), List.of(), List.of());
    }

    /** Explicit test seams are captured with the same immutable launch snapshot. */
    record TestOverrides(
            StreamingClient streamingClient,
            AutoDreamEngine autoDreamEngine,
            CliOutput output,
            CliOutput errorOutput) {
        static final TestOverrides EMPTY = new TestOverrides(null, null, null, null);
    }

    /** Derived routing state; no execution component needs to reread raw flags. */
    record Mode(
            boolean sdkStreamJson,
            boolean headless,
            boolean interactive,
            boolean formattedOutput) {
        static Mode from(OutputOptions output, SessionOptions session) {
            boolean sdkStreamJson = Strings.CS.equals("stream-json", output.inputFormat());
            boolean headless = sdkStreamJson || output.printMode() || output.noInteractive()
                || session.rewindFiles() != null;
            return new Mode(
                sdkStreamJson,
                headless,
                !headless,
                Strings.CI.equals(output.outputFormat(), "json")
                    || Strings.CI.equals(output.outputFormat(), "stream-json"));
        }
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
