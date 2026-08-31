package com.claudecode.session;

import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full transcript projection shared by resume, sidechain continuation and insights.
 */
public final class TranscriptLoader {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptLoader.class);
    private static final long MAX_JSON_TRANSCRIPT_BYTES = 256L * 1024 * 1024;
    private static final long TIMESTAMP_PARENT_FALLBACK_MILLIS = 5_000L;
    private static final Set<String> TRANSCRIPT_TYPES =
        Set.of("user", "assistant", "attachment", "system");

    private final ObjectMapper mapper;

    public TranscriptLoader() {
        this(JsonUtils.getMapper());
    }

    public TranscriptLoader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }


    public TranscriptFile loadTranscriptFile(Path file) {
        return loadTranscriptFile(file, false);
    }

    /**
     * Loads a complete transcript.
     */
    public TranscriptFile loadTranscriptFile(Path file, boolean keepAllLeaves) {
        MutableTranscript data = new MutableTranscript();
        if (file == null || !Files.exists(file)) return data.freeze(keepAllLeaves, null);

        try (BufferedReader reader = keepAllLeaves
                ? Files.newBufferedReader(file, StandardCharsets.UTF_8)
                : new BufferedReader(new InputStreamReader(new ByteArrayInputStream(
                    TranscriptReadService.readTranscriptForLoad(file)), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    line = JsonUtils.stripBom(line);
                    first = false;
                }
                if (StringUtils.isBlank(line)) continue;
                JsonNode parsed;
                try {
                    parsed = mapper.readTree(line);
                } catch (Exception malformed) {
                    LOG.debug("Skipping malformed transcript row in {}: {}",
                        file, malformed.getMessage());
                    continue;
                }
                if (!(parsed instanceof ObjectNode entry)) continue;
                indexEntry(entry.deepCopy(), data);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read transcript: " + file, failure);
        }

        String preservedTail = applyPreservedRelinks(data.messages, data.parents);
        applySnipRemovals(data.messages, data.parents);
        return data.freeze(keepAllLeaves, preservedTail);
    }

    /** Builds the same transformed graph from an external SessionStore entry stream. */
    public TranscriptFile loadTranscriptEntries(Iterable<? extends JsonNode> entries) {
        MutableTranscript data = new MutableTranscript();
        if (entries != null) for (JsonNode parsed : entries) {
            if (parsed instanceof ObjectNode entry) indexEntry(entry.deepCopy(), data);
        }
        String preservedTail = applyPreservedRelinks(data.messages, data.parents);
        applySnipRemovals(data.messages, data.parents);
        return data.freeze(false, preservedTail);
    }


    public LoadedTranscript loadTranscriptFromFile(Path file) {
        if (file == null) throw new IllegalArgumentException("file");
        if (Strings.CS.endsWith(file.toString(), ".jsonl")) {
            TranscriptFile loaded = loadTranscriptFile(file);
            if (loaded.messageEntries().isEmpty()) {
                if (loaded.clearedToEmpty()) return LoadedTranscript.empty(file);
                throw format("No messages found in JSONL file", "no_messages");
            }
            String leafUuid = latestUuid(loaded.messageEntries(), loaded.leafUuids());
            if (leafUuid == null && loaded.leafUuids().isEmpty() && !loaded.clearedToEmpty()) {
                leafUuid = latestUuid(loaded.messageEntries(), uuid ->
                    !loaded.messageEntries().get(uuid).path("isSidechain").asBoolean(false));
            }
            if (leafUuid == null) {
                if (loaded.clearedToEmpty()) return LoadedTranscript.empty(file);
                throw format("No valid conversation chain found in JSONL file", "no_chain");
            }
            List<JsonNode> rawChain = buildConversationChain(loaded, leafUuid);
            List<Message> messages = toMessages(rawChain);
            JsonNode leaf = loaded.messageEntries().get(leafUuid);
            String sessionId = text(leaf, "sessionId");
            String summary = loaded.summaries().get(leafUuid);
            String title = sessionId == null ? null : loaded.customTitles().get(sessionId);
            String tag = sessionId == null ? null : loaded.tags().get(sessionId);
            List<FileHistorySnapshot> snapshots = buildFileHistorySnapshotChain(
                loaded.fileHistorySnapshots(), rawChain);
            List<ToolResultBudget.Replacement> replacements = sessionId == null
                ? List.of() : loaded.contentReplacements().getOrDefault(sessionId, List.of());
            JsonNode worktree = sessionId == null ? null : loaded.worktreeStates().get(sessionId);
            return new LoadedTranscript(messages, sessionId, leafUuid, summary, title, tag,
                file, snapshots, replacements, nullIfJsonNull(worktree));
        }

        long size;
        try {
            size = Files.size(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to stat transcript: " + file, failure);
        }
        if (size > MAX_JSON_TRANSCRIPT_BYTES) {
            throw format("Transcript file too large to load as JSON (" + size + " bytes)",
                "too_large");
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException failure) {
            throw format("Invalid JSON in transcript file: " + failure, "invalid_json");
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read transcript: " + file, failure);
        }

        JsonNode array;
        if (parsed != null && parsed.isArray()) {
            array = parsed;
        } else if (parsed != null && parsed.isObject() && parsed.has("messages")) {
            array = parsed.get("messages");
            if (array == null || !array.isArray()) {
                throw format("Transcript messages must be an array", "bad_shape");
            }
        } else {
            throw format(
                "Transcript must be an array of messages or an object with a messages array",
                "bad_shape");
        }
        if (array.isEmpty()) throw format("No messages found in JSON file", "no_messages");
        List<JsonNode> raw = new ArrayList<>(array.size());
        array.forEach(node -> { if (node != null && node.isObject()) raw.add(node); });
        List<Message> messages = toMessages(raw);
        if (messages.isEmpty()) throw format("No messages found in JSON file", "no_messages");
        return new LoadedTranscript(messages, null,
            messages.getLast().uuid(), null, null, null, file,
            List.of(), List.of(), null);
    }

    public Optional<AgentTranscript> getAgentTranscript(Path agentFile, String agentId) {
        if (agentFile == null || StringUtils.isBlank(agentId)) return Optional.empty();
        try {
            TranscriptFile loaded = loadTranscriptFile(agentFile);
            List<JsonNode> agentMessages = loaded.messageEntries().values().stream()
                .filter(message -> Strings.CS.equals(agentId, text(message, "agentId")))
                .filter(message -> message.path("isSidechain").asBoolean(false))
                .toList();
            if (agentMessages.isEmpty()) return Optional.empty();

            Set<String> parents = new HashSet<>();
            for (JsonNode message : agentMessages) {
                String parent = text(message, "parentUuid");
                if (parent != null) parents.add(parent);
            }
            JsonNode leaf = latest(agentMessages, message -> {
                String uuid = text(message, "uuid");
                return uuid != null && !parents.contains(uuid) && !isCompactBoundary(message);
            });
            if (leaf == null) return Optional.empty();

            List<JsonNode> raw = buildConversationChain(loaded, text(leaf, "uuid")).stream()
                .filter(message -> Strings.CS.equals(agentId, text(message, "agentId")))
                .toList();
            List<Message> prefix = loadForkContextPrefix(
                agentFile, loaded.forkContextRefs().get(agentId));
            List<Message> sidechain = toMessages(raw);
            List<Message> messages = new ArrayList<>(prefix.size() + sidechain.size());
            messages.addAll(prefix);
            messages.addAll(sidechain);
            return Optional.of(new AgentTranscript(messages, sidechain,
                loaded.agentContentReplacements().getOrDefault(agentId, List.of())));
        } catch (RuntimeException failure) {
            LOG.debug("Failed to load agent transcript {}: {}", agentFile, failure.getMessage());
            return Optional.empty();
        }
    }

    /** Builds a root-to-leaf chain from an already transformed transcript graph. */
    public static List<JsonNode> buildConversationChain(TranscriptFile file, String leafUuid) {
        if (file == null || leafUuid == null) return List.of();
        List<JsonNode> reversed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String currentUuid = leafUuid;
        JsonNode current = file.messageEntries().get(currentUuid);
        while (current != null) {
            if (!seen.add(currentUuid)) {
                LOG.debug("Cycle detected in parentUuid chain at {}; returning partial transcript",
                    currentUuid);
                break;
            }
            reversed.add(current);
            String parent = file.parentByUuid().get(currentUuid);
            if (parent == null) break;
            JsonNode next = file.messageEntries().get(parent);
            if (next == null || seen.contains(parent)) {
                next = timestampParentFallback(file.messageEntries(), current, seen);
            }
            current = next;
            currentUuid = current == null ? null : text(current, "uuid");
        }
        Collections.reverse(reversed);
        List<JsonNode> recovered = recoverOrphanedParallelToolResults(
            file.messageEntries(), reversed, seen);
        return appendTerminalChildren(file.messageEntries(), leafUuid, recovered, seen);
    }

    private static void indexEntry(ObjectNode entry, MutableTranscript data) {
        String type = text(entry, "type");
        String uuid = text(entry, "uuid");
        if (Strings.CS.equals("progress", type) && uuid != null) {
            String parent = text(entry, "parentUuid");
            data.progressBridge.put(uuid,
                parent != null && data.progressBridge.containsKey(parent)
                    ? data.progressBridge.get(parent) : parent);
            return;
        }
        if (type != null && TRANSCRIPT_TYPES.contains(type) && uuid != null) {
            String parent = text(entry, "parentUuid");
            if (parent != null && data.progressBridge.containsKey(parent)) {
                parent = data.progressBridge.get(parent);
                setParent(entry, parent);
            }
            data.messages.put(uuid, entry);
            data.parents.put(uuid, parent);
            if (!entry.path("isSidechain").asBoolean(false)) {
                data.lastNonSidechainUuid = uuid;
                data.explicitLeaf = false;
                data.clearedToEmpty = false;
                data.rewound = false;
            }
            return;
        }
        if (Strings.CS.equals("summary", type)) {
            putText(data.summaries, entry, "leafUuid", "summary");
        } else if (Strings.CS.equals("last-prompt", type)) {
            indexLastPrompt(entry, data);
        } else if (Strings.CS.equals("custom-title", type)) {
            putText(data.customTitles, entry, "sessionId", "customTitle");
        } else if (Strings.CS.equals("ai-title", type)) {
            putText(data.aiTitles, entry, "sessionId", "aiTitle");
        } else if (Strings.CS.equals("tag", type)) {
            putText(data.tags, entry, "sessionId", "tag");
        } else if (Strings.CS.equals("relocated", type)) {
            putText(data.relocatedCwds, entry, "sessionId", "relocatedCwd");
        } else if (Strings.CS.equals("agent-name", type)) {
            putText(data.agentNames, entry, "sessionId", "agentName");
        } else if (Strings.CS.equals("agent-color", type)) {
            putText(data.agentColors, entry, "sessionId", "agentColor");
        } else if (Strings.CS.equals("agent-setting", type)) {
            putText(data.agentSettings, entry, "sessionId", "agentSetting");
        } else if (Strings.CS.equals("mode", type)) {
            putText(data.modes, entry, "sessionId", "mode");
        } else if (Strings.CS.equals("permission-mode", type)) {
            putText(data.permissionModes, entry, "sessionId", "permissionMode");
        } else if (Strings.CS.equals("isolation-latch", type)) {
            putText(data.isolationLatches, entry, "sessionId", "side");
        } else if (Strings.CS.equals("ended-by-model", type)) {
            String sessionId = text(entry, "sessionId");
            if (sessionId != null) data.endedSessions.add(sessionId);
        } else if (Strings.CS.equals("worktree-state", type)) {
            String sessionId = text(entry, "sessionId");
            if (sessionId != null) data.worktreeStates.put(sessionId,
                copyOrNullNode(entry.get("worktreeSession")));
        } else if (Strings.CS.equals("pr-link", type)) {
            String sessionId = text(entry, "sessionId");
            if (sessionId != null) {
                if (entry.hasNonNull("prNumber")) data.prNumbers.put(sessionId,
                    entry.path("prNumber").asInt());
                putText(data.prUrls, entry, "sessionId", "prUrl");
                putText(data.prRepositories, entry, "sessionId", "prRepository");
            }
        } else if (Strings.CS.equals("file-history-snapshot", type)) {
            String messageId = text(entry, "messageId");
            if (messageId != null) data.fileHistorySnapshots.put(messageId, entry);
        } else if (Strings.CS.equals("content-replacement", type)) {
            indexContentReplacements(entry, data);
        } else if (Strings.CS.equals("fork-context-ref", type)) {
            String agentId = text(entry, "agentId");
            String parentSessionId = text(entry, "parentSessionId");
            String parentLastUuid = text(entry, "parentLastUuid");
            if (agentId != null && parentSessionId != null && parentLastUuid != null) {
                data.forkContextRefs.put(agentId, new ForkContextRef(
                    agentId, parentSessionId, parentLastUuid,
                    entry.path("contextLength").asInt(0)));
            }
        }
    }

    private static void indexLastPrompt(ObjectNode entry, MutableTranscript data) {
        JsonNode leafNode = entry.get("leafUuid");
        if (leafNode != null && leafNode.isTextual()) {
            String leaf = leafNode.asText();
            data.explicitLeaf = entry.path("explicit").asBoolean(false)
                || data.explicitLeaf && Strings.CS.equals(leaf, data.lastPromptLeafUuid);
            data.rewound = entry.path("rewound").asBoolean(false)
                || data.rewound && Strings.CS.equals(leaf, data.lastPromptLeafUuid);
            data.lastPromptLeafUuid = leaf;
            data.clearedToEmpty = false;
        } else if (leafNode != null && leafNode.isNull()
                && entry.path("explicit").asBoolean(false)) {
            data.clearedToEmpty = true;
            data.lastPromptLeafUuid = null;
            data.explicitLeaf = false;
            data.rewound = false;
        }
    }

    private static void indexContentReplacements(ObjectNode entry, MutableTranscript data) {
        JsonNode items = entry.get("replacements");
        if (items == null || !items.isArray()) return;
        List<ToolResultBudget.Replacement> replacements = new ArrayList<>();
        for (JsonNode item : items) {
            if (!Strings.CS.equals("tool-result", text(item, "kind"))) continue;
            String toolUseId = text(item, "toolUseId");
            String replacement = text(item, "replacement");
            if (toolUseId != null && replacement != null) {
                replacements.add(new ToolResultBudget.Replacement(toolUseId, replacement));
            }
        }
        if (replacements.isEmpty()) return;
        String agentId = text(entry, "agentId");
        if (agentId != null) {
            data.agentContentReplacements.computeIfAbsent(agentId, _ -> new ArrayList<>())
                .addAll(replacements);
            return;
        }
        String sessionId = text(entry, "sessionId");
        if (sessionId != null) {
            data.contentReplacements.computeIfAbsent(sessionId, _ -> new ArrayList<>())
                .addAll(replacements);
        }
    }

    private static String applyPreservedRelinks(
            LinkedHashMap<String, ObjectNode> messages,
            Map<String, String> parents) {
        Preservation lastPreservation = null;
        int lastPreservationBoundary = -1;
        int absoluteLastBoundary = -1;
        Map<String, Integer> index = new HashMap<>();
        int position = 0;
        for (Map.Entry<String, ObjectNode> entry : messages.entrySet()) {
            index.put(entry.getKey(), position);
            if (isCompactBoundary(entry.getValue())) {
                absoluteLastBoundary = position;
                Preservation preservation = preservation(entry.getValue(), messages, parents);
                if (preservation != null) {
                    lastPreservation = preservation;
                    lastPreservationBoundary = position;
                }
            }
            position++;
        }
        if (lastPreservation == null) return null;

        boolean live = lastPreservationBoundary == absoluteLastBoundary;
        Preservation selected = live ? lastPreservation : null;
        List<String> preserved = selected == null ? List.of() : selected.uuids();
        if (preserved.stream().anyMatch(uuid -> !messages.containsKey(uuid))) {
            LOG.debug("Preserved transcript segment is incomplete; leaving full history intact");
            return null;
        }
        Set<String> preservedSet = new HashSet<>(preserved);
        String tail = preserved.isEmpty() ? null : preserved.getLast();
        if (selected != null && !preserved.isEmpty()) {
            String parent = selected.anchorUuid();
            for (String uuid : preserved) {
                parents.put(uuid, parent);
                setParent(messages.get(uuid), parent);
                parent = uuid;
            }
            String head = preserved.getFirst();
            for (Map.Entry<String, ObjectNode> entry : messages.entrySet()) {
                if (Strings.CS.equals(entry.getKey(), head)) continue;
                if (Strings.CS.equals(parents.get(entry.getKey()), selected.anchorUuid())) {
                    parents.put(entry.getKey(), tail);
                    setParent(entry.getValue(), tail);
                }
            }
            for (String uuid : preserved) clearAssistantUsage(messages.get(uuid));
        }

        Set<String> deleted = new HashSet<>();
        for (String uuid : new ArrayList<>(messages.keySet())) {
            Integer physical = index.get(uuid);
            if (physical != null && physical < absoluteLastBoundary
                    && !preservedSet.contains(uuid)) {
                deleted.add(uuid);
                messages.remove(uuid);
                parents.remove(uuid);
            }
        }
        if (selected != null && tail != null && !deleted.isEmpty()) {
            for (Map.Entry<String, ObjectNode> entry : messages.entrySet()) {
                String type = text(entry.getValue(), "type");
                if (!Strings.CS.equals("user", type) && !Strings.CS.equals("assistant", type)) {
                    continue;
                }
                if (deleted.contains(parents.get(entry.getKey()))) {
                    parents.put(entry.getKey(), tail);
                    setParent(entry.getValue(), tail);
                }
            }
        }
        return tail;
    }

    private static Preservation preservation(
            JsonNode boundary,
            Map<String, ObjectNode> messages,
            Map<String, String> parents) {
        JsonNode metadata = boundary.path("compactMetadata");
        JsonNode listed = metadata.path("preservedMessages");
        String anchor = text(listed, "anchorUuid");
        JsonNode uuids = listed.path("uuids");
        if (anchor != null && uuids.isArray()) {
            List<String> values = new ArrayList<>();
            uuids.forEach(uuid -> { if (uuid.isTextual()) values.add(uuid.asText()); });
            return new Preservation(anchor, List.copyOf(values));
        }
        JsonNode segment = metadata.path("preservedSegment");
        String head = text(segment, "headUuid");
        String tail = text(segment, "tailUuid");
        anchor = text(segment, "anchorUuid");
        if (head == null || tail == null || anchor == null) return null;
        List<String> reverse = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = tail;
        while (current != null && seen.add(current)) {
            if (!messages.containsKey(current)) return null;
            reverse.add(current);
            if (Strings.CS.equals(current, head)) {
                Collections.reverse(reverse);
                return new Preservation(anchor, List.copyOf(reverse));
            }
            current = parents.get(current);
        }
        return null;
    }

    private static void applySnipRemovals(
            LinkedHashMap<String, ObjectNode> messages,
            Map<String, String> parents) {
        Set<String> removed = new HashSet<>();
        for (JsonNode message : messages.values()) {
            JsonNode uuids = message.path("snipMetadata").path("removedUuids");
            if (!uuids.isArray()) continue;
            uuids.forEach(uuid -> { if (uuid.isTextual()) removed.add(uuid.asText()); });
        }
        if (removed.isEmpty()) return;

        Map<String, String> deletedParents = new HashMap<>();
        Set<String> present = new HashSet<>();
        for (String uuid : removed) {
            if (!messages.containsKey(uuid)) continue;
            deletedParents.put(uuid, parents.get(uuid));
            present.add(uuid);
            messages.remove(uuid);
            parents.remove(uuid);
        }
        for (Map.Entry<String, ObjectNode> entry : messages.entrySet()) {
            String parent = parents.get(entry.getKey());
            if (parent == null || !removed.contains(parent)) continue;
            String resolved = resolveDeletedParent(parent, removed, deletedParents, present);
            parents.put(entry.getKey(), resolved);
            setParent(entry.getValue(), resolved);
        }
    }

    private static String resolveDeletedParent(
            String start,
            Set<String> removed,
            Map<String, String> deletedParents,
            Set<String> present) {
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = start;
        while (current != null && removed.contains(current)) {
            if (!seen.add(current)) {
                current = null;
                break;
            }
            path.add(current);
            if (!present.contains(current)) {
                current = null;
                break;
            }
            current = deletedParents.get(current);
        }
        for (String uuid : path) {
            deletedParents.put(uuid, current);
            present.add(uuid);
        }
        return current;
    }

    private static Set<String> selectLeafUuids(
            MutableTranscript data,
            boolean keepAllLeaves,
            String preservedTail) {
        if (!keepAllLeaves && data.clearedToEmpty) return Set.of();
        boolean explicitValid = data.explicitLeaf && data.lastPromptLeafUuid != null
            && data.messages.containsKey(data.lastPromptLeafUuid)
            && !data.messages.get(data.lastPromptLeafUuid).path("isSidechain").asBoolean(false);
        if (!keepAllLeaves && (preservedTail == null || explicitValid)) {
            String selected = data.lastPromptLeafUuid != null
                && data.messages.containsKey(data.lastPromptLeafUuid)
                    ? data.lastPromptLeafUuid : null;
            if (selected != null && !data.explicitLeaf && data.lastNonSidechainUuid != null
                    && !Strings.CS.equals(selected, data.lastNonSidechainUuid)
                    && descendsFrom(data.lastNonSidechainUuid, selected, data.parents)) {
                selected = data.lastNonSidechainUuid;
            }
            if (preservedTail == null && selected == null) selected = data.lastNonSidechainUuid;
            String leaf = nearestUserAssistant(selected, data.messages, data.parents);
            if (leaf != null) return orderedSet(List.of(leaf));
        }

        Set<String> parentUuids = new HashSet<>();
        Set<String> hasUserAssistantChild = new HashSet<>();
        for (Map.Entry<String, ObjectNode> entry : data.messages.entrySet()) {
            String parent = data.parents.get(entry.getKey());
            if (parent == null) continue;
            parentUuids.add(parent);
            String type = text(entry.getValue(), "type");
            if (Strings.CS.equals("user", type) || Strings.CS.equals("assistant", type)) {
                hasUserAssistantChild.add(parent);
            }
        }
        Set<String> leaves = new LinkedHashSet<>();
        for (String uuid : data.messages.keySet()) {
            if (parentUuids.contains(uuid)) continue;
            String leaf = nearestUserAssistant(uuid, data.messages, data.parents);
            if (leaf != null && (keepAllLeaves || !hasUserAssistantChild.contains(leaf))) {
                leaves.add(leaf);
            }
        }
        if (!keepAllLeaves && leaves.size() > 1) {
            String selected = data.lastPromptLeafUuid != null
                && leaves.contains(data.lastPromptLeafUuid)
                    ? data.lastPromptLeafUuid : data.lastNonSidechainUuid;
            String leaf = nearestUserAssistant(selected, data.messages, data.parents);
            if (leaf != null) return orderedSet(List.of(leaf));
        }
        return orderedSet(leaves);
    }

    private static boolean descendsFrom(
            String descendant, String ancestor, Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        String current = descendant;
        while (current != null && seen.add(current)) {
            if (Strings.CS.equals(current, ancestor)) return true;
            current = parents.get(current);
        }
        return false;
    }

    private static String nearestUserAssistant(
            String start,
            Map<String, ObjectNode> messages,
            Map<String, String> parents) {
        Set<String> seen = new HashSet<>();
        String current = start;
        while (current != null && seen.add(current)) {
            JsonNode message = messages.get(current);
            if (message == null) return null;
            String type = text(message, "type");
            if (Strings.CS.equals("user", type) || Strings.CS.equals("assistant", type)) {
                return current;
            }
            current = parents.get(current);
        }
        return null;
    }

    private static JsonNode timestampParentFallback(
            Map<String, JsonNode> messages,
            JsonNode child,
            Set<String> seen) {
        long childTime = timestampMillis(child);
        if (childTime == Long.MIN_VALUE) return null;
        boolean sidechain = child.path("isSidechain").asBoolean(false);
        JsonNode closest = null;
        long closestDelta = Long.MAX_VALUE;
        for (JsonNode candidate : messages.values()) {
            String uuid = text(candidate, "uuid");
            if (uuid == null || seen.contains(uuid)) continue;
            if (candidate.path("isSidechain").asBoolean(false) != sidechain) continue;
            long candidateTime = timestampMillis(candidate);
            if (candidateTime == Long.MIN_VALUE) continue;
            long delta = childTime - candidateTime;
            if (delta >= 0 && delta <= TIMESTAMP_PARENT_FALLBACK_MILLIS
                    && delta < closestDelta) {
                closest = candidate;
                closestDelta = delta;
            }
        }
        return closest;
    }

    private static List<JsonNode> recoverOrphanedParallelToolResults(
            Map<String, JsonNode> messages,
            List<JsonNode> chain,
            Set<String> seen) {
        List<JsonNode> assistants = chain.stream()
            .filter(message -> Strings.CS.equals("assistant", text(message, "type")))
            .toList();
        if (assistants.isEmpty()) return chain;
        Map<String, JsonNode> anchorByMessageId = new HashMap<>();
        for (JsonNode assistant : assistants) {
            String id = text(assistant.path("message"), "id");
            if (id != null) anchorByMessageId.put(id, assistant);
        }
        Map<String, List<JsonNode>> siblings = new HashMap<>();
        Map<String, List<JsonNode>> toolResults = new HashMap<>();
        for (JsonNode message : messages.values()) {
            if (Strings.CS.equals("assistant", text(message, "type"))) {
                String id = text(message.path("message"), "id");
                if (id != null) siblings.computeIfAbsent(id, _ -> new ArrayList<>()).add(message);
            } else if (isToolResultUser(message)) {
                String parent = text(message, "parentUuid");
                if (parent != null) {
                    toolResults.computeIfAbsent(parent, _ -> new ArrayList<>()).add(message);
                }
            }
        }
        Set<String> processed = new HashSet<>();
        Map<String, List<JsonNode>> inserts = new HashMap<>();
        for (JsonNode assistant : assistants) {
            String id = text(assistant.path("message"), "id");
            if (id == null || !processed.add(id)) continue;
            List<JsonNode> group = siblings.getOrDefault(id, List.of(assistant));
            List<JsonNode> orphanedAssistants = new ArrayList<>();
            List<JsonNode> orphanedResults = new ArrayList<>();
            for (JsonNode sibling : group) {
                String uuid = text(sibling, "uuid");
                if (uuid != null && !seen.contains(uuid)) orphanedAssistants.add(sibling);
                for (JsonNode result : toolResults.getOrDefault(uuid, List.of())) {
                    String resultUuid = text(result, "uuid");
                    if (resultUuid != null && !seen.contains(resultUuid)) {
                        orphanedResults.add(result);
                    }
                }
            }
            if (orphanedAssistants.isEmpty() && orphanedResults.isEmpty()) continue;
            Comparator<JsonNode> byTimestamp = Comparator.comparing(TranscriptLoader::timestamp);
            orphanedAssistants.sort(byTimestamp);
            orphanedResults.sort(byTimestamp);
            List<JsonNode> recovered = new ArrayList<>(
                orphanedAssistants.size() + orphanedResults.size());
            recovered.addAll(orphanedAssistants);
            recovered.addAll(orphanedResults);
            recovered.forEach(message -> seen.add(text(message, "uuid")));
            JsonNode anchor = anchorByMessageId.get(id);
            if (anchor != null) inserts.put(text(anchor, "uuid"), recovered);
        }
        if (inserts.isEmpty()) return chain;
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode message : chain) {
            result.add(message);
            result.addAll(inserts.getOrDefault(text(message, "uuid"), List.of()));
        }
        return result;
    }

    private static List<JsonNode> appendTerminalChildren(
            Map<String, JsonNode> messages,
            String leafUuid,
            List<JsonNode> chain,
            Set<String> seen) {
        Map<String, List<JsonNode>> children = new HashMap<>();
        for (JsonNode message : messages.values()) {
            String type = text(message, "type");
            String parent = text(message, "parentUuid");
            if (parent == null || Strings.CS.equals("user", type)
                    || Strings.CS.equals("assistant", type)) continue;
            children.computeIfAbsent(parent, _ -> new ArrayList<>()).add(message);
        }
        List<JsonNode> trailing = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(leafUuid);
        while (!queue.isEmpty()) {
            String parent = queue.removeFirst();
            for (JsonNode child : children.getOrDefault(parent, List.of())) {
                String uuid = text(child, "uuid");
                if (uuid == null || !seen.add(uuid)) continue;
                trailing.add(child);
                queue.addLast(uuid);
            }
        }
        if (trailing.size() > 1) trailing.sort(Comparator.comparing(TranscriptLoader::timestamp));
        if (trailing.isEmpty()) return chain;
        List<JsonNode> result = new ArrayList<>(chain.size() + trailing.size());
        result.addAll(chain);
        result.addAll(trailing);
        return result;
    }

    private List<Message> loadForkContextPrefix(Path agentFile, ForkContextRef reference) {
        if (reference == null) return List.of();
        Path parentFile = resolveParentTranscript(agentFile, reference.parentSessionId());
        if (parentFile == null) return List.of();
        TranscriptFile parent = loadTranscriptFile(parentFile);
        if (!parent.messageEntries().containsKey(reference.parentLastUuid())) {
            LOG.warn("[fork-context-ref] parent uuid {} not found in {}; returning empty prefix",
                reference.parentLastUuid(), parentFile);
            return List.of();
        }
        List<JsonNode> raw = buildConversationChain(parent, reference.parentLastUuid()).stream()
            .filter(message -> !message.path("isSidechain").asBoolean(false))
            .toList();
        return toMessages(raw);
    }

    private static Path resolveParentTranscript(Path agentFile, String parentSessionId) {
        Path current = agentFile.toAbsolutePath().normalize().getParent();
        while (current != null && !Strings.CS.equals("subagents",
                current.getFileName() == null ? null : current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null || current.getParent() == null
                || current.getParent().getParent() == null) return null;
        return current.getParent().getParent().resolve(parentSessionId + ".jsonl");
    }

    private List<Message> toMessages(List<? extends JsonNode> raw) {
        List<Message> messages = new ArrayList<>(raw.size());
        for (JsonNode node : raw) {
            try {
                messages.add(toMessage(node));
            } catch (Exception failure) {
                LOG.debug("Skipping transcript row {} during message projection: {}",
                    text(node, "uuid"), failure.getMessage());
            }
        }
        return List.copyOf(messages);
    }

    private Message toMessage(JsonNode node) throws JsonProcessingException {
        JsonNode projected = node.deepCopy();
        if (projected instanceof ObjectNode root
                && Strings.CS.equals("user", text(root, "type"))
                && root.path("origin").isObject()) {
            String kind = root.path("origin").path("kind").asText("");
            MessageOrigin normalized = switch (kind) {
                case "task-notification" -> MessageOrigin.TASK_NOTIFICATION;
                case "auto-continuation" -> MessageOrigin.AUTO_CONTINUATION;
                case "hook" -> MessageOrigin.HOOK;
                case "system" -> MessageOrigin.SYSTEM;
                case "tool_result", "tool-result" -> MessageOrigin.TOOL_RESULT;
                case "compact_summary", "compact-summary" -> MessageOrigin.COMPACT_SUMMARY;
                default -> MessageOrigin.USER;
            };
            root.put("origin", normalized.name());
        }
        return mapper.treeToValue(projected, Message.class);
    }

    private static List<FileHistorySnapshot> buildFileHistorySnapshotChain(
            Map<String, JsonNode> snapshots,
            List<JsonNode> chain) {
        List<FileHistorySnapshot> result = new ArrayList<>();
        Map<String, Integer> indexBySnapshotMessage = new HashMap<>();
        for (JsonNode message : chain) {
            JsonNode entry = snapshots.get(text(message, "uuid"));
            if (entry == null) continue;
            JsonNode snapshot = entry.get("snapshot");
            if (snapshot == null) continue;
            String messageId = text(snapshot, "messageId");
            boolean update = entry.path("isSnapshotUpdate").asBoolean(false);
            Integer existing = update && messageId != null
                ? indexBySnapshotMessage.get(messageId) : null;
            FileHistorySnapshot projected = new FileHistorySnapshot(
                text(entry, "messageId"), snapshot.deepCopy(), update);
            if (existing == null) {
                if (messageId != null) indexBySnapshotMessage.put(messageId, result.size());
                result.add(projected);
            } else {
                result.set(existing, projected);
            }
        }
        return List.copyOf(result);
    }

    private static String latestUuid(
            Map<String, JsonNode> messages,
            Set<String> candidates) {
        return latestUuid(messages, candidates::contains);
    }

    private static String latestUuid(
            Map<String, JsonNode> messages,
            Predicate<String> predicate) {
        JsonNode latest = latest(messages.values(), message -> {
            String uuid = text(message, "uuid");
            return uuid != null && predicate.test(uuid);
        });
        return latest == null ? null : text(latest, "uuid");
    }

    private static JsonNode latest(
            Iterable<? extends JsonNode> messages,
            Predicate<JsonNode> predicate) {
        JsonNode latest = null;
        long latestTime = Long.MIN_VALUE;
        for (JsonNode message : messages) {
            if (!predicate.test(message)) continue;
            long time = timestampMillis(message);
            if (time > latestTime) {
                latest = message;
                latestTime = time;
            }
        }
        return latest;
    }

    private static long timestampMillis(JsonNode message) {
        String value = timestamp(message);
        if (value.isEmpty()) return Long.MIN_VALUE;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException _) {
            return Long.MIN_VALUE;
        }
    }

    private static String timestamp(JsonNode message) {
        String value = text(message, "timestamp");
        return value == null ? "" : value;
    }

    private static boolean isToolResultUser(JsonNode message) {
        if (!Strings.CS.equals("user", text(message, "type"))) return false;
        JsonNode content = message.path("message").path("content");
        if (!content.isArray()) return false;
        for (JsonNode block : content) {
            if (Strings.CS.equals("tool_result", text(block, "type"))) return true;
        }
        return false;
    }

    private static boolean isCompactBoundary(JsonNode message) {
        return Strings.CS.equals("system", text(message, "type"))
            && Strings.CS.equals("compact_boundary", text(message, "subtype"));
    }

    private static void clearAssistantUsage(ObjectNode entry) {
        if (entry == null || !Strings.CS.equals("assistant", text(entry, "type"))) return;
        ObjectNode message = entry.path("message") instanceof ObjectNode object
            ? object : entry.putObject("message");
        ObjectNode usage = message.path("usage") instanceof ObjectNode object
            ? object : message.putObject("usage");
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
    }

    private static void setParent(ObjectNode message, String parentUuid) {
        if (message == null) return;
        if (parentUuid == null) message.putNull("parentUuid");
        else message.put("parentUuid", parentUuid);
    }

    private static void putText(
            Map<String, String> target,
            JsonNode entry,
            String keyField,
            String valueField) {
        String key = text(entry, keyField);
        String value = text(entry, valueField);
        if (key != null && value != null) target.put(key, value);
    }

    private static JsonNode copyOrNullNode(JsonNode node) {
        return node == null ? JsonUtils.getMapper().nullNode() : node.deepCopy();
    }

    private static JsonNode nullIfJsonNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static TranscriptFileFormatException format(String message, String code) {
        return new TranscriptFileFormatException(message, code);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<String, List<ToolResultBudget.Replacement>> immutableReplacementMap(
            Map<String, List<ToolResultBudget.Replacement>> values) {
        Map<String, List<ToolResultBudget.Replacement>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Set<String> orderedSet(Iterable<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private record Preservation(String anchorUuid, List<String> uuids) {}

    /** Raw graph plus session metadata maps returned by loadTranscriptFile. */
    public record TranscriptFile(
        Map<String, JsonNode> messageEntries,
        Map<String, String> parentByUuid,
        Map<String, String> summaries,
        Map<String, String> customTitles,
        Map<String, String> aiTitles,
        Map<String, String> tags,
        Map<String, String> relocatedCwds,
        Map<String, String> agentNames,
        Map<String, String> agentColors,
        Map<String, String> agentSettings,
        Map<String, String> modes,
        Map<String, String> permissionModes,
        Map<String, String> isolationLatches,
        Set<String> endedSessions,
        Map<String, JsonNode> worktreeStates,
        Map<String, Integer> prNumbers,
        Map<String, String> prUrls,
        Map<String, String> prRepositories,
        Map<String, JsonNode> fileHistorySnapshots,
        Map<String, List<ToolResultBudget.Replacement>> contentReplacements,
        Map<String, List<ToolResultBudget.Replacement>> agentContentReplacements,
        Map<String, ForkContextRef> forkContextRefs,
        Set<String> leafUuids,
        Optional<String> lastPromptLeafUuid,
        boolean clearedToEmpty,
        String rewindAnchorUuid
    ) {}

    /** User-facing projection returned by loadTranscriptFromFile. */
    public record LoadedTranscript(
        List<Message> messages,
        String sessionId,
        String leafUuid,
        String summary,
        String customTitle,
        String tag,
        Path source,
        List<FileHistorySnapshot> fileHistorySnapshots,
        List<ToolResultBudget.Replacement> contentReplacements,
        JsonNode worktreeSession
    ) {
        public LoadedTranscript {
            messages = List.copyOf(messages);
            fileHistorySnapshots = List.copyOf(fileHistorySnapshots);
            contentReplacements = List.copyOf(contentReplacements);
        }

        static LoadedTranscript empty(Path source) {
            return new LoadedTranscript(List.of(), null, null, null, null, null,
                source, List.of(), List.of(), null);
        }
    }

    public record FileHistorySnapshot(
        String messageUuid,
        JsonNode snapshot,
        boolean update
    ) {}

    public record ForkContextRef(
        String agentId,
        String parentSessionId,
        String parentLastUuid,
        int contextLength
    ) {}

    public record AgentTranscript(
        List<Message> messages,
        List<Message> sidechainMessages,
        List<ToolResultBudget.Replacement> contentReplacements
    ) {
        public AgentTranscript {
            messages = List.copyOf(messages);
            sidechainMessages = List.copyOf(sidechainMessages);
            contentReplacements = List.copyOf(contentReplacements);
        }
    }

    public static final class TranscriptFileFormatException extends IllegalArgumentException {
        private final String code;

        TranscriptFileFormatException(String message, String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final class MutableTranscript {
        final LinkedHashMap<String, ObjectNode> messages = new LinkedHashMap<>();
        final Map<String, String> parents = new LinkedHashMap<>();
        final Map<String, String> progressBridge = new HashMap<>();
        final Map<String, String> summaries = new LinkedHashMap<>();
        final Map<String, String> customTitles = new LinkedHashMap<>();
        final Map<String, String> aiTitles = new LinkedHashMap<>();
        final Map<String, String> tags = new LinkedHashMap<>();
        final Map<String, String> relocatedCwds = new LinkedHashMap<>();
        final Map<String, String> agentNames = new LinkedHashMap<>();
        final Map<String, String> agentColors = new LinkedHashMap<>();
        final Map<String, String> agentSettings = new LinkedHashMap<>();
        final Map<String, String> modes = new LinkedHashMap<>();
        final Map<String, String> permissionModes = new LinkedHashMap<>();
        final Map<String, String> isolationLatches = new LinkedHashMap<>();
        final Set<String> endedSessions = new LinkedHashSet<>();
        final Map<String, JsonNode> worktreeStates = new LinkedHashMap<>();
        final Map<String, Integer> prNumbers = new LinkedHashMap<>();
        final Map<String, String> prUrls = new LinkedHashMap<>();
        final Map<String, String> prRepositories = new LinkedHashMap<>();
        final Map<String, JsonNode> fileHistorySnapshots = new LinkedHashMap<>();
        final Map<String, List<ToolResultBudget.Replacement>> contentReplacements =
            new LinkedHashMap<>();
        final Map<String, List<ToolResultBudget.Replacement>> agentContentReplacements =
            new LinkedHashMap<>();
        final Map<String, ForkContextRef> forkContextRefs = new LinkedHashMap<>();
        String lastNonSidechainUuid;
        String lastPromptLeafUuid;
        boolean explicitLeaf;
        boolean clearedToEmpty;
        boolean rewound;

        TranscriptFile freeze(boolean keepAllLeaves, String preservedTail) {
            Set<String> leaves = selectLeafUuids(this, keepAllLeaves, preservedTail);
            Map<String, JsonNode> raw = new LinkedHashMap<>(messages);
            return new TranscriptFile(
                immutableMap(raw), immutableMap(parents), immutableMap(summaries),
                immutableMap(customTitles), immutableMap(aiTitles), immutableMap(tags),
                immutableMap(relocatedCwds), immutableMap(agentNames),
                immutableMap(agentColors), immutableMap(agentSettings), immutableMap(modes),
                immutableMap(permissionModes), immutableMap(isolationLatches),
                orderedSet(endedSessions), immutableMap(worktreeStates),
                immutableMap(prNumbers), immutableMap(prUrls), immutableMap(prRepositories),
                immutableMap(fileHistorySnapshots), immutableReplacementMap(contentReplacements),
                immutableReplacementMap(agentContentReplacements), immutableMap(forkContextRefs),
                leaves, Optional.ofNullable(lastPromptLeafUuid), clearedToEmpty,
                rewound ? lastPromptLeafUuid : null);
        }
    }
}
