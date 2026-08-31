package com.claudecode.tools.skills;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.SessionIdentity;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Injects shell variables into skill content.
 * Supported variables: ${CLAUDE_SKILL_DIR}, ${CLAUDE_SESSION_ID}.
 */
public class ShellVariableInjector {

    private final Map<String, String> variables;
    // Live reference — CLAUDE_SESSION_ID must reflect the CURRENT session at
// inject time (a resume/branch mid-run changes it), so it can't be
    // baked into `variables` like the other, write-once entries.
    private final SessionIdentity sessionIdentity;

    public ShellVariableInjector() {
        this(SessionIdentity.newRandom());
    }

    public ShellVariableInjector(SessionIdentity sessionIdentity) {
        this.variables = new LinkedHashMap<>();
        this.sessionIdentity = sessionIdentity != null ? sessionIdentity : SessionIdentity.newRandom();
    }

    /**
     * Set the skill directory variable.
     */
    public void setSkillDir(Path skillDir) {
        variables.put("CLAUDE_SKILL_DIR", skillDir.toAbsolutePath().toString());
    }

    /**
     * Add a custom variable.
     */
    public void setVariable(String name, String value) {
        variables.put(name, value != null ? value : "");
    }

    /**
     * Inject all configured variables into the given content.
     * Replaces ${VAR_NAME} patterns with their values.
     *
     * @param content the content to process
     * @return content with variables replaced
     */
    public String inject(String content) {
        if (StringUtils.isEmpty(content)) {
            return content;
        }

        String result = content;
        for (var entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        result = result.replace("${CLAUDE_SESSION_ID}", sessionIdentity.get());
        return result;
    }

    /**
     * Returns an unmodifiable view of the current variables.
     */
    public Map<String, String> getVariables() {
        return Map.copyOf(variables);
    }
}
