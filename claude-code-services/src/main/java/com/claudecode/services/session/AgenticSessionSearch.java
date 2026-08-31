package com.claudecode.services.session;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.text.FormatUtils;

import com.claudecode.api.AnthropicSdkClient;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.services.model.SideQuery;
import com.claudecode.session.SessionInfo;
import com.claudecode.session.SessionStorage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Agentic session search using a small-fast model to rank stored conversations by semantic
 * relevance.
 */
public final class AgenticSessionSearch {

    private static final int MAX_TRANSCRIPT_CHARS = 2000;
    private static final int MAX_MESSAGES_TO_SCAN = 100;
    private static final int MAX_SESSIONS_TO_SEARCH = 100;

    private static final String SYSTEM_PROMPT = """
        Your goal is to find relevant sessions based on a user's search query.

        You will be given a list of sessions with their metadata and a search query. Identify which sessions are most relevant to the query.

        Each session may include:
        - Title (display name or custom title)
        - Tag (user-assigned category, shown as [tag: name] - users tag sessions with /tag command to categorize them)
        - Branch (git branch name, shown as [branch: name])
        - Summary (AI-generated summary)
        - First message (beginning of the conversation)
        - Transcript (excerpt of conversation content)

        IMPORTANT: Tags are user-assigned labels that indicate the session's topic or category. If the query matches a tag exactly or partially, those sessions should be highly prioritized.

        For each session, consider (in order of priority):
        1. Exact tag matches (highest priority - user explicitly categorized this session)
        2. Partial tag matches or tag-related terms
        3. Title matches (custom titles or first message content)
        4. Branch name matches
        5. Summary and transcript content matches
        6. Semantic similarity and related concepts

        CRITICAL: Be VERY inclusive in your matching. Include sessions that:
        - Contain the query term anywhere in any field
        - Are semantically related to the query (e.g., "testing" matches sessions about "tests", "unit tests", "QA", etc.)
        - Discuss topics that could be related to the query
        - Have transcripts that mention the concept even in passing

        When in doubt, INCLUDE the session. It's better to return too many results than too few. The user can easily scan through results, but missing relevant sessions is frustrating.

        Return sessions ordered by relevance (most relevant first). If truly no sessions have ANY connection to the query, return an empty array - but this should be rare.

        Respond with ONLY the JSON object, no markdown formatting:
        {"relevant_indices": [2, 5, 0]}""";

    private final SideQuery sideQuery;
    private final String smallFastModel;
    private final SessionStorage sessionStorage;
    private final Path sessionDir;

    public AgenticSessionSearch(SideQuery sideQuery, String smallFastModel,
                                SessionStorage sessionStorage, Path sessionDir) {
        this.sideQuery = sideQuery;
        this.smallFastModel = smallFastModel;
        this.sessionStorage = sessionStorage;
        this.sessionDir = sessionDir;
    }

    /**
     * Legacy constructor kept for callers that still pass a raw
     * {@link AnthropicSdkClient}. New callers should build a
     * {@link SideQuery} once and share it across side-query features.
     */
    public AgenticSessionSearch(AnthropicSdkClient client, String smallFastModel,
                                SessionStorage sessionStorage, Path sessionDir) {
        this(new SideQuery(client), smallFastModel, sessionStorage, sessionDir);
    }


    public List<SessionInfo> search(String query, List<SessionInfo> sessions) {
        if (StringUtils.isBlank(query) || sessions.isEmpty()) return List.of();
        String queryLower = query.toLowerCase(Locale.ROOT);

        // Pre-filter: sessions containing the query term
        List<SessionInfo> matching = new ArrayList<>();
        for (SessionInfo s : sessions) {
            if (logContainsQuery(s, queryLower)) matching.add(s);
        }

        // Take up to MAX_SESSIONS_TO_SEARCH, fill with recent non-matching if needed
        List<SessionInfo> toSearch;
        if (matching.size() >= MAX_SESSIONS_TO_SEARCH) {
            toSearch = new ArrayList<>(matching.subList(0, MAX_SESSIONS_TO_SEARCH));
        } else {
            toSearch = new ArrayList<>(matching);
            for (SessionInfo s : sessions) {
                if (toSearch.size() >= MAX_SESSIONS_TO_SEARCH) break;
                if (!matching.contains(s)) toSearch.add(s);
            }
        }

        // Build session list for prompt
        StringBuilder sessionList = new StringBuilder();
        for (int i = 0; i < toSearch.size(); i++) {
            SessionInfo s = toSearch.get(i);
            sessionList.append(i).append(": ").append(buildSessionEntry(s)).append('\n');
        }

        String userMessage = "Sessions:\n" + sessionList + "\nSearch query: \"" + query + "\"\n\nFind the sessions that are most relevant to this query.";

        try {
            String text = sideQuery.queryText(smallFastModel, SYSTEM_PROMPT, userMessage, 1024);
            if (text == null) return List.of();

            // Extract JSON {"relevant_indices": [...]}
            int braceStart = text.indexOf('{');
            int braceEnd = text.lastIndexOf('}');
            if (braceStart < 0 || braceEnd <= braceStart) return List.of();
            String json = text.substring(braceStart, braceEnd + 1);

            JsonNode node = JsonUtils.getMapper().readTree(json);
            JsonNode indices = node.path("relevant_indices");
            if (!indices.isArray()) return List.of();

            List<SessionInfo> result = new ArrayList<>();
            for (JsonNode idx : indices) {
                int i = idx.asInt(-1);
                if (i >= 0 && i < toSearch.size()) result.add(toSearch.get(i));
            }
            return result;
        } catch (Exception _) {
            return List.of();
        }
    }

    private boolean logContainsQuery(SessionInfo s, String queryLower) {
        String title = s.id().toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(title, queryLower)) return true;
        // Check transcript
        String transcript = extractTranscript(s);
        return transcript != null && Strings.CI.contains(transcript, queryLower);
    }

    private String buildSessionEntry(SessionInfo s) {
        StringBuilder parts = new StringBuilder();
        parts.append(s.id(), 0, Math.min(8, s.id().length()));
        String transcript = extractTranscript(s);
        if (StringUtils.isNotBlank(transcript)) {
            parts.append(" - Transcript: ").append(transcript);
        }
        return parts.toString();
    }

    private String extractTranscript(SessionInfo s) {
        if (sessionStorage == null || sessionDir == null) return null;
        try {
            Path file = sessionDir.resolve(s.id() + ".jsonl");
            List<Message> msgs = sessionStorage.readMessages(file);
            if (msgs.isEmpty()) return null;
            // Take messages from start and end
            List<Message> toScan;
            if (msgs.size() <= MAX_MESSAGES_TO_SCAN) {
                toScan = msgs;
            } else {
                int half = MAX_MESSAGES_TO_SCAN / 2;
                toScan = new ArrayList<>(msgs.subList(0, half));
                toScan.addAll(msgs.subList(msgs.size() - half, msgs.size()));
            }
            StringBuilder sb = new StringBuilder();
            for (Message m : toScan) {
                String text = extractMessageText(m);
                if (StringUtils.isNotBlank(text)) {
                    sb.append(text).append(' ');
                }
            }
            String result = sb.toString().replaceAll("\\s+", " ").trim();
            return result.length() > MAX_TRANSCRIPT_CHARS
                ? FormatUtils.truncate(result, MAX_TRANSCRIPT_CHARS)
                : result;
        } catch (Exception _) { return null; }
    }

    private static String extractMessageText(Message m) {
        if (m instanceof UserMessage um) {
            MessageContent mc = um.message();
            if (mc == null) return null;
            if (mc.text() != null) return mc.text();
            if (mc.blocks() != null) {
                StringBuilder sb = new StringBuilder();
                for (var b : mc.blocks()) {
                    if (b instanceof TextBlock tb) sb.append(tb.text());
                }
                return sb.isEmpty() ? null : sb.toString();
            }
        } else if (m instanceof AssistantMessage am) {
            if (am.message() != null && am.message().content() != null) {
                StringBuilder sb = new StringBuilder();
                for (var b : am.message().content()) {
                    if (b instanceof TextBlock tb) sb.append(tb.text());
                }
                return sb.isEmpty() ? null : sb.toString();
            }
        }
        return null;
    }
}
