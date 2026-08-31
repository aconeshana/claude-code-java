package com.claudecode.tools.skills;

import com.claudecode.core.prompt.ArgumentSubstitutor;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Represents a loaded skill with its metadata and content.
 */
public record Skill(
    String name,
    String description,
    List<String> allowedTools,
    String content,
    Path sourceFile,
    SkillSource source,

    String model,

    String effort,

    String context,

    Map<String, Object> frontmatter
) {

    /**
     * Original leaf name for a dynamically scoped nested skill.
     */
    public String unqualifiedName() {
        if (frontmatter == null) return name;
        Object value = frontmatter.get("unqualifiedName");
        return value instanceof String text && !StringUtils.isBlank(text) ? text : name;
    }


    public boolean disableModelInvocation() {
        return booleanFrontmatter("disableModelInvocation")
            || booleanFrontmatter("disable-model-invocation");
    }

/** Aliases on the established prompt-command surface, such as {@code proactive} for /loop. */
    public List<String> aliases() {
        if (frontmatter == null) return List.of();
        Object value = frontmatter.get("aliases");
        if (value instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance)
                .map(String.class::cast).filter(StringUtils::isNotBlank).toList();
        }
        if (value instanceof String text && !StringUtils.isBlank(text)) return List.of(text);
        return List.of();
    }

    /** Static argument hint exposed by the user-invocable slash-command projection. */
    public String argumentHint() {
        if (frontmatter == null) return null;
        Object value = frontmatter.get("argumentHint");
        return value instanceof String text && !StringUtils.isBlank(text) ? text : null;
    }

    /** User-facing command description, separate from the model listing's when-to-use suffix. */
    public String commandDescription() {
        if (frontmatter != null) {
            Object value = frontmatter.get("commandDescription");
            if (value instanceof String text && !StringUtils.isBlank(text)) return text;
        }
        return description;
    }

    /** Menu-only description used by the slash-command typeahead projection. */
    public String menuDescription() {
        if (frontmatter != null) {
            Object value = frontmatter.get("menuDescription");
            if (value instanceof String text && !StringUtils.isBlank(text)) return text;
        }
        return commandDescription();
    }


    public boolean userInvocable() {
        if (frontmatter == null || !frontmatter.containsKey("userInvocable")) return true;
        return booleanFrontmatter("userInvocable");
    }

    /** Whether this skill is projected into Java's CommandRegistry for slash/headless dispatch. */
    public boolean commandProjection() {
        return booleanFrontmatter("commandProjection");
    }


    public List<String> argumentNames() {
        if (frontmatter == null) return List.of();
        Object names = frontmatter.containsKey("argNames")
            ? frontmatter.get("argNames") : frontmatter.get("arguments");
        return ArgumentSubstitutor.parseArgumentNames(names);
    }

    /** True when this is a parsed plugin {@code commands/} entry, not a skill file. */
    public boolean isPluginCommand() {
        return booleanFrontmatter("pluginCommand");
    }

    private boolean booleanFrontmatter(String key) {
        if (frontmatter == null) return false;
        Object value = frontmatter.get(key);
        return value instanceof Boolean b ? b
            : value instanceof String s && Strings.CI.equals("true", s.trim());
    }

    public enum SkillSource {
        MANAGED,
        USER,
        PROJECT,

        BUILTIN,
        BUNDLED,
        MCP,

        PLUGIN
    }
}
