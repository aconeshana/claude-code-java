package com.claudecode.services.insights;

import com.claudecode.session.TranscriptLoader;
import com.claudecode.session.TranscriptLoader.TranscriptFile;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Projects the shared session transcript DAG into one insights log per leaf.
 */
public final class TranscriptLogLoader {

    private TranscriptLogLoader() {}

    /** Compatibility entry point retained for existing callers. */
    public static List<SessionLog> loadAllLogs(Path sessionFile) {
        return loadAllLogsFromSessionFile(sessionFile, null, _ -> false);
    }

    /**
     * Exact branch projection used by insights.
     *
     * @param projectPathOverride explicit project path, or {@code null} to use
     *                            the first message's {@code cwd}
     * @param builtInCommandPredicate command-name predicate without a leading slash
     */
    public static List<SessionLog> loadAllLogsFromSessionFile(
            Path sessionFile,
            String projectPathOverride,
            Predicate<String> builtInCommandPredicate) {
        Predicate<String> builtIns = builtInCommandPredicate != null
            ? builtInCommandPredicate : _ -> false;
        TranscriptFile data;
        try {
            data = new TranscriptLoader().loadTranscriptFile(sessionFile, true);
        } catch (RuntimeException _) {
            return List.of();
        }
        if (data.messageEntries().isEmpty() || data.leafUuids().isEmpty()) return List.of();

        List<SessionLog> logs = new ArrayList<>();
        for (String leafUuid : data.messageEntries().keySet()) {
            if (!data.leafUuids().contains(leafUuid)) continue;
            List<JsonNode> chain = TranscriptLoader.buildConversationChain(data, leafUuid);
            if (chain.isEmpty()) continue;
            JsonNode leaf = data.messageEntries().get(leafUuid);
            JsonNode first = chain.getFirst();
            String sessionId = text(leaf, "sessionId");
            if (sessionId == null) sessionId = text(first, "sessionId");
            String projectPath = projectPathOverride != null
                ? projectPathOverride : text(first, "cwd");
            logs.add(new SessionLog(
                sessionId,
                chain,
                leafUuid,
                SessionMetaExtractor.extractFirstPrompt(chain, builtIns),
                data.summaries().get(leafUuid),
                projectPath));
        }
        return List.copyOf(logs);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
