package com.claudecode.core.engine;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Message-level aggregate tool-result budget.
 */
public final class ToolResultBudget {

    /** Serializable decision record persisted with a session transcript. */
    public record Replacement(String toolUseId, String replacement) {
        public Replacement {
            if (StringUtils.isBlank(toolUseId)) {
                throw new IllegalArgumentException("toolUseId must not be blank");
            }
            if (replacement == null) {
                throw new NullPointerException("replacement");
            }
        }
    }

    /** Persists one candidate and returns the exact model-visible replacement. */
    @FunctionalInterface
    public interface Persister {
        Optional<String> persist(String toolName, String toolUseId, List<ContentBlock> content);
    }

    /** Stable per-conversation decision state. */
    public static final class State {
        private final Set<String> seenIds = new HashSet<>();
        private final Map<String, String> replacements = new HashMap<>();
        private final List<Replacement> newlyReplaced = new ArrayList<>();

        public Set<String> seenIds() { return Set.copyOf(seenIds); }
        public Map<String, String> replacements() { return Map.copyOf(replacements); }

        /** Returns and clears replacement decisions made since the last drain. */
        public List<Replacement> drainNewReplacements() {
            if (newlyReplaced.isEmpty()) return List.of();
            List<Replacement> copy = List.copyOf(newlyReplaced);
            newlyReplaced.clear();
            return copy;
        }

        private boolean hasSeen(String id) { return seenIds.contains(id); }
        private String replacement(String id) { return replacements.get(id); }
        private void markSeen(String id) { seenIds.add(id); }
        private void replace(String id, String content) {
            seenIds.add(id);
            replacements.put(id, content);
        }

        private void recordReplacement(String id, String content) {
            replace(id, content);
            newlyReplaced.add(new Replacement(id, content));
        }
    }

    private record Candidate(String id, String toolName, List<ContentBlock> content, int size) {}
    private record Group(List<Candidate> candidates) {}

    private ToolResultBudget() {}

    /** Creates fresh state for a conversation thread. */
    public static State newState() {
        return new State();
    }

    /**
     * Restores the cache-stability state for messages already visible in a resumed/forked conversation.
     */
    public static State restore(List<Message> messages, List<Replacement> records) {
        State state = newState();
        if (messages == null || messages.isEmpty()) return state;
        Map<String, String> names = toolNames(messages);
        Set<String> candidateIds = new HashSet<>();
        for (Group group : collectGroups(messages, names)) {
            for (Candidate candidate : group.candidates()) {
                candidateIds.add(candidate.id());
                state.markSeen(candidate.id());
            }
        }
        if (records != null) {
            for (Replacement record : records) {
                if (record != null && candidateIds.contains(record.toolUseId())) {
                    state.replace(record.toolUseId(), record.replacement());
                }
            }
        }
        return state;
    }

    /**
     * Applies the aggregate budget and returns a request-only message view.
     * Original transcript messages are never mutated.
     */
    public static List<Message> apply(
            List<Message> messages,
            State state,
            int limit,
            Predicate<String> skipTool,
            Persister persister) {
        if (messages == null || messages.isEmpty() || state == null || limit <= 0
                || persister == null) return messages;

        Map<String, String> names = toolNames(messages);
        List<Group> groups = collectGroups(messages, names);
        Map<String, String> replacementMap = new LinkedHashMap<>();

        for (Group group : groups) {
            List<Candidate> mustReapply = new ArrayList<>();
            List<Candidate> frozen = new ArrayList<>();
            List<Candidate> fresh = new ArrayList<>();
            for (Candidate candidate : group.candidates()) {
                String previous = state.replacement(candidate.id());
                if (previous != null) mustReapply.add(candidate);
                else if (state.hasSeen(candidate.id())) frozen.add(candidate);
                else fresh.add(candidate);
            }
            for (Candidate candidate : mustReapply) {
                replacementMap.put(candidate.id(), state.replacement(candidate.id()));
            }
            if (fresh.isEmpty()) {
                group.candidates().forEach(c -> state.markSeen(c.id()));
                continue;
            }

            List<Candidate> eligible = new ArrayList<>();
            for (Candidate candidate : fresh) {
                if (skipTool != null && skipTool.test(candidate.toolName())) {
                    state.markSeen(candidate.id());
                } else {
                    eligible.add(candidate);
                }
            }
            int frozenSize = frozen.stream().mapToInt(Candidate::size).sum();
            int freshSize = eligible.stream().mapToInt(Candidate::size).sum();
            if (frozenSize + freshSize <= limit) {
                group.candidates().forEach(c -> state.markSeen(c.id()));
                continue;
            }

            List<Candidate> ordered = new ArrayList<>(eligible);
            ordered.sort(Comparator.comparingInt(Candidate::size).reversed());
            int remaining = frozenSize + freshSize;
            List<Candidate> selected = new ArrayList<>();
            Set<String> selectedIds = new HashSet<>();
            for (Candidate candidate : ordered) {
                if (remaining <= limit) break;
                selectedIds.add(candidate.id());
                selected.add(candidate);
                remaining -= candidate.size();
            }
            for (Candidate candidate : group.candidates()) {
                if (!selectedIds.contains(candidate.id())) state.markSeen(candidate.id());
            }
            for (Candidate candidate : selected) {
                Optional<String> replacement;
                try {
                    replacement = persister.persist(candidate.toolName(), candidate.id(), candidate.content());
                } catch (RuntimeException _) {
                    replacement = Optional.empty();
                }
                // The original content has now been sent (or a persistence
                // attempt failed), so freeze the decision either way.
                if (replacement.isPresent()) {
                    state.recordReplacement(candidate.id(), replacement.get());
                    replacementMap.put(candidate.id(), replacement.get());
                } else {
                    state.markSeen(candidate.id());
                }
            }
        }

        if (replacementMap.isEmpty()) return messages;
        return replace(messages, replacementMap);
    }

    private static Map<String, String> toolNames(List<Message> messages) {
        Map<String, String> names = new HashMap<>();
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.message() == null || assistant.message().content() == null) continue;
            for (ContentBlock block : assistant.message().content()) {
                if (block instanceof ToolUseBlock toolUse) names.put(toolUse.id(), toolUse.name());
            }
        }
        return names;
    }

    private static List<Group> collectGroups(List<Message> messages, Map<String, String> names) {
        List<Group> groups = new ArrayList<>();
        List<Candidate> current = new ArrayList<>();
        Set<String> seenAssistantIds = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof UserMessage user && user.message() != null
                    && user.message().blocks() != null) {
                for (ContentBlock block : user.message().blocks()) {
                    if (!(block instanceof ToolResultBlock result) || result.content() == null
                            || result.content().isEmpty() || containsImage(result.content())) continue;
                    int size = result.content().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .mapToInt(text -> text.text() == null ? 0 : text.text().length())
                        .sum();
                    if (size == 0 || isCompacted(result.content())) continue;
                    String id = result.toolUseId();
                    if (StringUtils.isBlank(id)) continue;
                    current.add(new Candidate(id, names.getOrDefault(id, ""), result.content(), size));
                }
            } else if (message instanceof AssistantMessage assistant) {
                String id = assistant.uuid();
                if (id != null && seenAssistantIds.add(id)) {
                    if (!current.isEmpty()) groups.add(new Group(List.copyOf(current)));
                    current.clear();
                }
            }
        }
        if (!current.isEmpty()) groups.add(new Group(List.copyOf(current)));
        return groups;
    }

    private static boolean containsImage(List<ContentBlock> blocks) {
        return blocks.stream().anyMatch(ImageBlock.class::isInstance);
    }

    private static boolean isCompacted(List<ContentBlock> blocks) {
        if (blocks.size() != 1 || !(blocks.getFirst() instanceof TextBlock text)) return false;
        return text.text() != null &&Strings.CS.startsWith( text.text(), "<persisted-output>");
    }

    private static List<Message> replace(List<Message> messages, Map<String, String> replacements) {
        List<Message> out = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Message message : messages) {
            if (!(message instanceof UserMessage user) || user.message() == null
                    || user.message().blocks() == null) {
                out.add(message);
                continue;
            }
            List<ContentBlock> blocks = user.message().blocks();
            List<ContentBlock> next = new ArrayList<>(blocks.size());
            boolean messageChanged = false;
            for (ContentBlock block : blocks) {
                if (block instanceof ToolResultBlock result && replacements.containsKey(result.toolUseId())) {
                    next.add(new ToolResultBlock(result.toolUseId(),
                        List.of(new TextBlock(replacements.get(result.toolUseId()))),
                        result.isError(), result.includeIsErrorField(), false));
                    messageChanged = true;
                } else {
                    next.add(block);
                }
            }
            if (!messageChanged) {
                out.add(message);
            } else {
                out.add(new UserMessage(user.uuid(),
                    MessageContent.ofBlocks(List.copyOf(next)),
                    user.isMeta(), user.isCompactSummary(), user.toolUseResult(), user.origin(),
                    user.parentUuidValue(), user.timestampValue(), user.imagePasteIds(),
                    user.permissionMode(), user.sessionIdValue(), user.sourceToolAssistantUUID(),
                    user.sourceToolUseID(), user.isVirtual(), user.mcpMeta(),
                    user.isVisibleInTranscriptOnly(), user.planContent(),
                    user.summarizeMetadata()));
                changed = true;
            }
        }
        return changed ? List.copyOf(out) : messages;
    }
}
