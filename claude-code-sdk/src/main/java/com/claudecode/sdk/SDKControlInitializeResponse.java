package com.claudecode.sdk;

import java.util.List;
import java.util.Objects;

/** Strongly typed response to the SDK initialize control request. */
public record SDKControlInitializeResponse(List<SlashCommand> commands, List<AgentInfo> agents,
                                           String outputStyle, List<String> availableOutputStyles,
                                           List<ModelInfo> models, AccountInfo account,
                                           FastModeState fastModeState) {
    public SDKControlInitializeResponse {
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
        Objects.requireNonNull(outputStyle, "outputStyle");
        availableOutputStyles = List.copyOf(Objects.requireNonNull(
            availableOutputStyles, "availableOutputStyles"));
        models = List.copyOf(Objects.requireNonNull(models, "models"));
        Objects.requireNonNull(account, "account");
    }
}
