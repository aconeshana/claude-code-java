package com.claudecode.commands.impl.info;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.StatusProperty;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.settings.SettingsManagementPort;
import com.claudecode.core.config.ClaudePaths;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * /status — shows current session status.
 */
@SlashCommand(
    name = "status",
    description = "Show current session status"
)
public class StatusCommand implements AnnotatedCommand {

    @Override
    public boolean isImmediate() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().statusDialogLauncher() != null) {
            context.presentation().statusDialogLauncher().run();
            return CommandResult.skip();
        }
        StringBuilder sb = new StringBuilder("Session Status\n==============\n\n");
        for (StatusProperty p : buildProperties(context)) {
            if (p.label() != null) sb.append(p.label()).append(": ");
            sb.append(p.value()).append('\n');
        }
        return CommandResult.of(sb.toString().stripTrailing());
    }

    /**
     * Computes the status property list from {@code context} — shared by the text fallback above and
     * {@code StatusPane} (claude-code-ui).
     */
    public static List<StatusProperty> buildProperties(CommandContext context) {
        List<StatusProperty> props = new ArrayList<>();


        props.add(new StatusProperty("Version", VersionCommand.readVersion()));

        String sessionId = context.session().currentSessionId() != null ? context.session().currentSessionId().get() : null;
        String sessionName = sessionId != null
            ? context.application().sessions().readCustomTitle(sessionId) : null;
        props.add(new StatusProperty("Session name",
            sessionName != null ? sessionName : "/rename to add a name"));

        if (sessionId != null) {
            props.add(new StatusProperty("Session ID", sessionId));
        }

        props.add(new StatusProperty("cwd", context.session().workingDirectory()));

        if (context.session().statusRuntimePropertiesSupplier() != null) {
            List<StatusProperty> runtime = context.session().statusRuntimePropertiesSupplier().get();
            if (runtime != null) props.addAll(runtime);
        } else {
            addFallbackAccountProperties(props,
                context.application().settings().preferences());
            String baseUrl = context.session().apiBaseUrlSupplier() != null
                ? context.session().apiBaseUrlSupplier().get() : null;
            if (StringUtils.isNotBlank(baseUrl)) {
                props.add(new StatusProperty("Anthropic base URL", baseUrl));
            }
        }

        props.add(new StatusProperty("Model", context.session().model()));

        String mcpStatus = context.session().mcpStatusSupplier() != null ? context.session().mcpStatusSupplier().get() : null;
        if (StringUtils.isNotBlank(mcpStatus)) {
            props.add(new StatusProperty("MCP servers", mcpStatus));
        }

        props.add(new StatusProperty("Setting sources",
            String.join(", ", context.application().settings().preferences()
                .settingSourceLabels(context.session().workingDirectory()))));
        return props;
    }

    /**
     * Detects which credential source is active, matching {@code ConfigLoader.resolveApiKey}'s priority
     * order (that class lives in claude-code-cli, a module claude-code-commands can't depend on without
     * inverting the dependency chain — this re-checks the same environment variables/config file rather
     * than calling into it).
     */
    private static void addFallbackAccountProperties(
            List<StatusProperty> props,
            SettingsManagementPort.Preferences preferences) {
        if (isNonBlankEnv("ANTHROPIC_AUTH_TOKEN")) {
            props.add(new StatusProperty("Auth token", "ANTHROPIC_AUTH_TOKEN"));
        }
        if (isNonBlankEnv("ANTHROPIC_API_KEY")) {
            props.add(new StatusProperty("API key", "ANTHROPIC_API_KEY"));
        } else if (preferences.hasStoredApiKey()) {
            props.add(new StatusProperty("API key", ClaudePaths.GLOBAL_JSON.toString()));
        }
    }

    private static boolean isNonBlankEnv(String name) {
        String v = SubprocessEnvironment.get(name);
        return StringUtils.isNotBlank(v);
    }

}
