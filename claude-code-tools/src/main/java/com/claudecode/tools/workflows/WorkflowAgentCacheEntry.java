package com.claudecode.tools.workflows;

import org.apache.commons.lang3.StringUtils;
/** One deterministic {@code agent} return value retained for workflow resume. */
public record WorkflowAgentCacheEntry(String prompt, String optionsJson, String output) {
    public WorkflowAgentCacheEntry {
        prompt = prompt == null ? "" : prompt;
        optionsJson = StringUtils.isBlank(optionsJson) ? "{}" : optionsJson;
        output = output == null ? "" : output;
    }

    boolean matches(String candidatePrompt, String candidateOptionsJson) {
        return prompt.equals(candidatePrompt)
            && optionsJson.equals(StringUtils.isBlank(candidateOptionsJson)
                ? "{}" : candidateOptionsJson);
    }
}
