package com.claudecode.tools.agent;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.ToolTexts;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Map;

/**
 * AgentTool description text.
 */
final class AgentToolPrompt {

    private AgentToolPrompt() {}

    /**
     * Renders the AgentTool prompt.
     */
    static String getPrompt(List<String> agentList) {
        return getPrompt(agentList, false);
    }

    @Explanation("When the active catalogue lacks the example custom agent, non-Anthropic providers substitute an available agent so literal imitation does not fail.")
    static String getPrompt(List<String> agentList, boolean protectMissingExample) {
        String baseline = protectMissingExample
            ? compatibleExamples(ToolTexts.prompt("Agent"), agentList)
            : ToolTexts.prompt("Agent");
        if (shouldInjectAgentListInMessages()) {
            return baseline;
        }
        int headEnd = baseline.indexOf("\n\n") + 2;
        int attachmentEnd = baseline.indexOf("\n\n", headEnd) + 2;
        if (headEnd < 2 || attachmentEnd < 2) {
            throw new IllegalStateException("Agent prompt resource is missing its introduction sections");
        }
        return baseline.substring(0, headEnd)
            + inlineAgentListing(agentList)
            + baseline.substring(attachmentEnd);
    }

    private static String compatibleExamples(String baseline, List<String> agentList) {
        boolean hasCodeReviewer = agentList != null && agentList.stream()
            .map(String::trim)
            .anyMatch(line -> Strings.CS.startsWith(line, "- code-reviewer:")
                || Strings.CS.startsWith(line, "code-reviewer:"));
        return hasCodeReviewer ? baseline : baseline.replace("code-reviewer", "general-purpose");
    }

    private static String inlineAgentListing(List<String> agentList) {
        StringBuilder listing = new StringBuilder();
        if (agentList == null || agentList.isEmpty()) {
            listing.append(ToolTexts.prompt("Agent", "no-agents"));
        } else {
            for (String line : agentList) {
                if (!Strings.CS.startsWith(line, "- ")) listing.append("- ");
                listing.append(line).append('\n');
            }
        }
        return ToolTexts.render(ToolTexts.prompt("Agent", "inline-intro"),
            Map.of("AGENT_LISTING", listing.toString()));
    }


    private static boolean shouldInjectAgentListInMessages() {
        String value = SubprocessEnvironment.get("CLAUDE_CODE_AGENT_LIST_IN_MESSAGES");
        if (EnvUtils.isEnvTruthy(value)) return true;
        return !EnvUtils.isEnvDefinedFalsy(value);
    }
}
