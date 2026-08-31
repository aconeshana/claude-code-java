package com.claudecode.runtime.hooks;

import java.util.*;

/**
 * Presentation-neutral snapshot of the configured hook graph.
 */
public record HookConfigurationSnapshot(
    List<HookEntry> hooks,
    Map<HookEvent, HookEventMetadata> metadata
) {
    public HookConfigurationSnapshot {
        hooks = List.copyOf(hooks);
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public List<String> sortedMatchers(HookEvent event) {
        return hooks.stream()
            .filter(hook -> hook.event() == event)
            .map(HookEntry::matcher)
            .distinct()
            .sorted(Comparator.nullsLast(Comparator.naturalOrder()))
            .toList();
    }

    public List<HookEntry> hooksFor(HookEvent event, String matcher) {
        return hooks.stream()
            .filter(hook -> hook.event() == event && Objects.equals(hook.matcher(), matcher))
            .toList();
    }

    public List<HookEntry> hooksFor(HookEvent event) {
        return hooks.stream().filter(hook -> hook.event() == event).toList();
    }

    public int hookCount(HookEvent event) {
        return (int) hooks.stream().filter(hook -> hook.event() == event).count();
    }

    public enum HookEvent {
        PRE_TOOL_USE,
        POST_TOOL_USE,
        POST_TOOL_USE_FAILURE,
        POST_TOOL_BATCH,
        NOTIFICATION,
        USER_PROMPT_SUBMIT,
        USER_PROMPT_EXPANSION,
        SESSION_START,
        SESSION_END,
        STOP,
        STOP_FAILURE,
        SUBAGENT_START,
        SUBAGENT_STOP,
        PRE_COMPACT,
        POST_COMPACT,
        PERMISSION_REQUEST,
        PERMISSION_DENIED,
        SETUP,
        TEAMMATE_IDLE,
        TASK_CREATED,
        TASK_COMPLETED,
        ELICITATION,
        ELICITATION_RESULT,
        CONFIG_CHANGE,
        WORKTREE_CREATE,
        WORKTREE_REMOVE,
        INSTRUCTIONS_LOADED,
        CWD_CHANGED,
        FILE_CHANGED,
        MESSAGE_DISPLAY;

        public String displayName() {
            StringBuilder out = new StringBuilder();
            for (String part : name().split("_")) {
                if (!part.isEmpty()) {
                    out.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1).toLowerCase(Locale.ROOT));
                }
            }
            return out.toString();
        }
    }

    public enum HookKind {
        COMMAND("command", "Command"),
        PROMPT("prompt", "Prompt"),
        HTTP("http", "URL"),
        AGENT("agent", "Prompt");

        private final String typeName;
        private final String contentLabel;

        HookKind(String typeName, String contentLabel) {
            this.typeName = typeName;
            this.contentLabel = contentLabel;
        }

        public String typeName() {
            return typeName;
        }

        public String contentLabel() {
            return contentLabel;
        }
    }

    public record HookEntry(
        HookEvent event,
        HookKind kind,
        String matcher,
        String sourceHeader,
        String sourceInline,
        String sourceDescription,
        String displayText,
        String rawContent
    ) {
    }

    public record HookEventMetadata(
        String summary,
        String description,
        MatcherMetadata matcherMetadata
    ) {
        public record MatcherMetadata(String matcherPlaceholder, String matcherType) {
        }
    }
}
