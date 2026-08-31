package com.claudecode.tools.agent;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Session policy for advertising and resolving sub-agent model overrides.
 */
public interface SubAgentModelPolicy {

    enum Outcome { USE_REQUESTED, INHERIT_PARENT, REJECT }

    record Decision(Outcome outcome, String model, String message) {
        public static Decision use(String model) {
            return new Decision(Outcome.USE_REQUESTED, model, null);
        }

        public static Decision inherit(String model, String message) {
            return new Decision(Outcome.INHERIT_PARENT, model, message);
        }

        public static Decision reject(String model, String message) {
            return new Decision(Outcome.REJECT, model, message);
        }
    }

    Decision resolve(String requestedModel, String parentModel);

    /**
     * Resolves the complete Agent-tool precedence chain shared by listings and
     * execution: process/settings override, per-call override, definition, then
     * the owning session model.
     */
    default Decision resolveAgent(
            AgentDefinition definition, String toolSpecifiedModel, String parentModel) {
        String globalOverride = SubprocessEnvironment.get("CLAUDE_CODE_SUBAGENT_MODEL");
        String requested = StringUtils.isNotBlank(globalOverride)
            ? globalOverride
            : StringUtils.isNotBlank(toolSpecifiedModel)
                ? toolSpecifiedModel
                : definition != null ? definition.model() : null;
        return resolve(requested, parentModel);
    }

    List<String> advertisedModels();

    static SubAgentModelPolicy permissive() {
        return new SubAgentModelPolicy() {
            @Override
            public Decision resolve(String requestedModel, String parentModel) {
                String selected = StringUtils.isBlank(requestedModel)
                    || Strings.CI.equals("inherit", requestedModel) ? parentModel : requestedModel;
                return Decision.use(selected);
            }

            @Override
            public List<String> advertisedModels() {
                return List.of("sonnet", "opus", "haiku", "fable");
            }
        };
    }
}
