package com.claudecode.cli;

import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.agent.SubAgentModelPolicy;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * CLI composition of organization allowlisting and route capability.
 */
final class CliSubAgentModelPolicy implements SubAgentModelPolicy {

    private final ModelAvailability availability;
    private final Predicate<String> allowlist;

    CliSubAgentModelPolicy(ModelAvailability availability, Predicate<String> allowlist) {
        this.availability = availability;
        this.allowlist = allowlist != null ? allowlist : _ -> true;
    }

    @Override
    public Decision resolve(String requestedModel, String parentModel) {
        boolean inherit = StringUtils.isBlank(requestedModel)
            || Strings.CI.equals("inherit", requestedModel);
        String requested = inherit ? parentModel : requestedModel;
        String resolved = resolveAlias(requested);
        String resolvedParent = resolveAlias(parentModel);

        if (!inherit && !isAllowed(requestedModel)) {
            if (availability.canCall(resolvedParent)) {
                return Decision.inherit(resolvedParent,
                    "Subagent model \"" + requestedModel
                        + "\" is not in the availableModels allowlist; inheriting the parent model instead");
            }
            return Decision.reject(resolvedParent,
                "The requested sub-agent model is restricted and the parent model is not callable");
        }
        if (!availability.canCall(resolved)) {
            return Decision.reject(resolved,
                "Sub-agent model is not available with the current model provider and authentication: "
                    + resolved);
        }
        return Decision.use(resolved);
    }

    @Override
    @Explanation("Explore uses the preferred lightweight model when available; otherwise it inherits the session model unless the user explicitly requested an unavailable model")
    public Decision resolveAgent(
            AgentDefinition definition, String toolSpecifiedModel, String parentModel) {
        String globalOverride = SubprocessEnvironment.get("CLAUDE_CODE_SUBAGENT_MODEL");
        boolean explicit = StringUtils.isNotBlank(globalOverride)
            || StringUtils.isNotBlank(toolSpecifiedModel);
        String requested = StringUtils.isNotBlank(globalOverride)
            ? globalOverride
            : StringUtils.isNotBlank(toolSpecifiedModel)
                ? toolSpecifiedModel
                : definition != null ? definition.model() : null;
        Decision decision = resolve(requested, parentModel);

        if (explicit) {
            return decision.outcome() == Outcome.USE_REQUESTED
                ? decision
                : Decision.reject(decision.model(), decision.message());
        }
        boolean builtInExploreDefault = definition != null
            && definition.source() == AgentSource.BUILT_IN
            && Strings.CS.equals("Explore", definition.agentType())
            && Strings.CI.equals("haiku", definition.model());
        if (builtInExploreDefault && decision.outcome() == Outcome.REJECT) {
            Decision parent = resolve(null, parentModel);
            if (parent.outcome() != Outcome.REJECT) {
                return Decision.inherit(parent.model(),
                    "Explore's default Haiku route is unavailable; inheriting the current session model");
            }
        }
        return decision;
    }

    @Override
    public List<String> advertisedModels() {
        return availability.agentOverrideModels(this::isAllowed);
    }

    private boolean isAllowed(String model) {
        try {
            return allowlist.test(model);
        } catch (RuntimeException _) {
            return false;
        }
    }

    private static String resolveAlias(String model) {
        if (StringUtils.isBlank(model)) return model;
        return switch (model.trim().toLowerCase(Locale.ROOT)) {
            case "sonnet", "opus", "haiku", "fable", "sol", "luna" ->
                ModelNames.parseUserSpecifiedModel(model);
            default -> model;
        };
    }
}
