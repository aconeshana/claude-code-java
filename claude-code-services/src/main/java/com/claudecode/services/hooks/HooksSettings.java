package com.claudecode.services.hooks;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.claudecode.core.engine.HookDispatcher;

import java.util.*;

/**
 * Hooks configuration.
 */
public record HooksSettings(
    Map<HookEvent, List<HookMatcher>> eventHooks
) implements HookDispatcher.InvocationHooks {

    public static final HooksSettings EMPTY = new HooksSettings(Map.of());

    /**
     * Returns matchers for the given event, or empty list if none configured.
     */
    public List<HookMatcher> getMatchers(HookEvent event) {
        return eventHooks.getOrDefault(event, List.of());
    }

    @Override
    public boolean isEmpty() {
        return eventHooks.isEmpty();
    }

    /**
     * Loads HooksSettings from a JSON node (the "hooks" section of settings).
     *
     * @param hooksNode the JSON node representing hooks config
     * @return parsed HooksSettings
     */
    public static HooksSettings fromJson(JsonNode hooksNode) {
        if (hooksNode == null || !hooksNode.isObject()) {
            return EMPTY;
        }

        Map<HookEvent, List<HookMatcher>> eventHooks = new EnumMap<>(HookEvent.class);

        Iterator<Map.Entry<String, JsonNode>> fields = hooksNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            try {
                HookEvent event = HookEvent.fromConfigKey(entry.getKey());
                List<HookMatcher> matchers = parseMatchers(entry.getValue());
                if (!matchers.isEmpty()) {
                    eventHooks.put(event, matchers);
                }
            } catch (IllegalArgumentException _) {
                // Skip unknown event types
            }
        }

        return new HooksSettings(Map.copyOf(eventHooks));
    }

    /**
     * Parses the YAML value nested below a frontmatter {@code hooks:} key.
     * Invalid frontmatter is deliberately ignored, matching the tolerant
     * skill discovery path.
     */
    public static HooksSettings fromYaml(String rawYaml) {
        if (StringUtils.isBlank(rawYaml)) {
            return EMPTY;
        }
        try {
            return fromJson(new YAMLMapper().readTree(rawYaml.stripLeading()));
        } catch (Exception _) {
            return EMPTY;
        }
    }

    private static List<HookMatcher> parseMatchers(JsonNode matchersNode) {
        if (matchersNode == null || !matchersNode.isArray()) {
            return List.of();
        }

        List<HookMatcher> matchers = new ArrayList<>();
        for (JsonNode matcherNode : matchersNode) {
            Optional<String> pattern = matcherNode.has("matcher")
                ? Optional.of(matcherNode.get("matcher").asText())
                : Optional.empty();

            List<HookCommand> hooks = parseHookCommands(matcherNode.get("hooks"));
            if (!hooks.isEmpty()) {
                matchers.add(new HookMatcher(pattern, hooks));
            }
        }
        return List.copyOf(matchers);
    }

    private static List<HookCommand> parseHookCommands(JsonNode hooksNode) {
        if (hooksNode == null || !hooksNode.isArray()) {
            return List.of();
        }

        List<HookCommand> commands = new ArrayList<>();
        for (JsonNode hookNode : hooksNode) {
            String type = hookNode.has("type") ? hookNode.get("type").asText("") : "";
            Optional<String> ifCond = optionalText(hookNode, "if");
            Optional<Integer> timeout = optionalInt(hookNode, "timeout");
            Optional<String> statusMsg = optionalText(hookNode, "statusMessage");
            boolean once = hookNode.has("once") && hookNode.get("once").asBoolean(false);

            switch (type) {
                case "command" -> {
                    String command = hookNode.has("command") ? hookNode.get("command").asText("") : "";
                    if (!StringUtils.isBlank(command)) {
                        commands.add(new BashCommandHook(
                            command, ifCond,
                            optionalText(hookNode, "shell"),
                            timeout, statusMsg, once,
                            hookNode.has("async") && hookNode.get("async").asBoolean(false),
                            hookNode.has("asyncRewake") && hookNode.get("asyncRewake").asBoolean(false)
                        ));
                    }
                }
                case "prompt" -> {
                    String prompt = hookNode.has("prompt") ? hookNode.get("prompt").asText("") : "";
                    if (!StringUtils.isBlank(prompt)) {
                        commands.add(new PromptHook(
                            prompt, ifCond, timeout,
                            optionalText(hookNode, "model"),
                            statusMsg, once
                        ));
                    }
                }
                case "http" -> {
                    String url = hookNode.has("url") ? hookNode.get("url").asText("") : "";
                    if (!StringUtils.isBlank(url)) {
                        Map<String, String> headers = new HashMap<>();
                        if (hookNode.has("headers") && hookNode.get("headers").isObject()) {
                            hookNode.get("headers").fields().forEachRemaining(
                                e -> headers.put(e.getKey(), e.getValue().asText("")));
                        }
                        List<String> allowedEnvVars = new ArrayList<>();
                        if (hookNode.has("allowedEnvVars") && hookNode.get("allowedEnvVars").isArray()) {
                            hookNode.get("allowedEnvVars").forEach(
                                n -> allowedEnvVars.add(n.asText("")));
                        }
                        commands.add(new HttpHook(
                            url, ifCond, timeout, Map.copyOf(headers),
                            List.copyOf(allowedEnvVars), statusMsg, once
                        ));
                    }
                }
                case "agent" -> {
                    String prompt = hookNode.has("prompt") ? hookNode.get("prompt").asText("") : "";
                    if (!StringUtils.isBlank(prompt)) {
                        commands.add(new AgentHook(
                            prompt, ifCond, timeout,
                            optionalText(hookNode, "model"),
                            statusMsg, once
                        ));
                    }
                }
                default -> { /* skip unknown types */ }
            }
        }
        return List.copyOf(commands);
    }

    private static Optional<String> optionalText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull()
            ? Optional.of(node.get(field).asText())
            : Optional.empty();
    }

    private static Optional<Integer> optionalInt(JsonNode node, String field) {
        return node.has(field) && node.get(field).isNumber()
            ? Optional.of(node.get(field).asInt())
            : Optional.empty();
    }
}
