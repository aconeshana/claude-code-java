package com.claudecode.services.insights;


import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.DiffHunks;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.session.stats.StatsDates;
import com.claudecode.core.text.XmlTagUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Turns one conversation branch ({@link SessionLog}) into the persisted {@link SessionMeta} row:
 * message counts, duration, tool/language/git stats, interruption and response-time signals, error
 * categories, and the first-prompt preview — the deterministic (non-LLM) half of the {@code
 * /insights} extraction pipeline.
 */
public final class SessionMetaExtractor {


    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
        Map.entry(".ts", "TypeScript"),
        Map.entry(".tsx", "TypeScript"),
        Map.entry(".js", "JavaScript"),
        Map.entry(".jsx", "JavaScript"),
        Map.entry(".py", "Python"),
        Map.entry(".rb", "Ruby"),
        Map.entry(".go", "Go"),
        Map.entry(".rs", "Rust"),
        Map.entry(".java", "Java"),
        Map.entry(".md", "Markdown"),
        Map.entry(".json", "JSON"),
        Map.entry(".yaml", "YAML"),
        Map.entry(".yml", "YAML"),
        Map.entry(".sh", "Shell"),
        Map.entry(".css", "CSS"),
        Map.entry(".html", "HTML")
    );


    private static final String AGENT_TOOL_NAME = "Agent";
    private static final String LEGACY_AGENT_TOOL_NAME = "Task";

    private static final String INTERRUPT_MARKER = "[Request interrupted by user";


    private static final Pattern SKIP_FIRST_PROMPT_PATTERN =
        Pattern.compile("^(?:\\s*<[a-z][\\w-]*[\\s>]|\\[Request interrupted by user[^\\]]*\\])");

    private SessionMetaExtractor() {}

    // ── public surface ───────────────────────────────────────────────────────


    public static boolean hasValidDates(SessionLog log) {
        return createdInstant(log) != null && modifiedInstant(log) != null;
    }


    public static SessionMeta toSessionMeta(SessionLog log) {
        ToolStats stats = extractToolStats(log);

        String sessionId = log.sessionId() != null ? log.sessionId() : "unknown";
        Instant created = createdInstant(log);
        Instant modified = modifiedInstant(log);
        if (created == null) created = Instant.EPOCH;
        if (modified == null) modified = created;

        String startTime = FormatUtils.formatInstantIso(created);
        double durationMinutes =
            Math.round((modified.toEpochMilli() - created.toEpochMilli()) / 1000.0 / 60.0);

        long userMessageCount = 0;
        long assistantMessageCount = 0;
        for (JsonNode msg : log.messages()) {
            String type = msg.path("type").asText(null);
            if (Strings.CS.equals("assistant", type)) assistantMessageCount++;
            // Only count user messages with actual text content (human

            if (Strings.CS.equals("user", type) && msg.hasNonNull("message")
                    && isHumanMessage(msg.path("message").path("content"))) {
                userMessageCount++;
            }
        }

        return SessionMeta.builder(sessionId, projectPath(log), startTime)
            .durationMinutes(durationMinutes)
            .userMessageCount(userMessageCount)
            .assistantMessageCount(assistantMessageCount)
            .toolCounts(stats.toolCounts)
            .languages(stats.languages)
            .gitCommits(stats.gitCommits)
            .gitPushes(stats.gitPushes)
            .inputTokens(stats.inputTokens)
            .outputTokens(stats.outputTokens)
            .firstPrompt(log.firstPrompt() != null
                ? log.firstPrompt() : extractFirstPrompt(log.messages()))
            .summary(log.summary())
            .userInterruptions(stats.userInterruptions)
            .userResponseTimes(stats.userResponseTimes)
            .toolErrors(stats.toolErrors)
            .toolErrorCategories(stats.toolErrorCategories)
            .usesTaskAgent(stats.usesTaskAgent)
            .usesMcp(stats.usesMcp)
            .usesWebSearch(stats.usesWebSearch)
            .usesWebFetch(stats.usesWebFetch)
            .linesAdded(stats.linesAdded)
            .linesRemoved(stats.linesRemoved)
            .filesModified(stats.filesModified.size())
            .messageHours(stats.messageHours)
            .userMessageTimestamps(stats.userMessageTimestamps)
            .build();
    }


    public static List<SessionMeta> deduplicateBranches(List<SessionMeta> metas) {
        Map<String, SessionMeta> bestBySession = new LinkedHashMap<>();
        for (SessionMeta meta : metas) {
            SessionMeta existing = bestBySession.get(meta.sessionId());
            if (existing == null
                    || meta.userMessageCount() > existing.userMessageCount()
                    || (meta.userMessageCount() == existing.userMessageCount()
                        && meta.durationMinutes() > existing.durationMinutes())) {
                bestBySession.put(meta.sessionId(), meta);
            }
        }
        return List.copyOf(bestBySession.values());
    }



    private record ToolStats(
        Map<String, Long> toolCounts,
        Map<String, Long> languages,
        long gitCommits,
        long gitPushes,
        long inputTokens,
        long outputTokens,
        long userInterruptions,
        List<Double> userResponseTimes,
        long toolErrors,
        Map<String, Long> toolErrorCategories,
        boolean usesTaskAgent,
        boolean usesMcp,
        boolean usesWebSearch,
        boolean usesWebFetch,
        long linesAdded,
        long linesRemoved,
        Set<String> filesModified,
        List<Integer> messageHours,
        List<String> userMessageTimestamps
    ) {}

    private static ToolStats extractToolStats(SessionLog log) {
        Map<String, Long> toolCounts = new LinkedHashMap<>();
        Map<String, Long> languages = new LinkedHashMap<>();
        long gitCommits = 0;
        long gitPushes = 0;
        long inputTokens = 0;
        long outputTokens = 0;

        long userInterruptions = 0;
        List<Double> userResponseTimes = new ArrayList<>();
        long toolErrors = 0;
        Map<String, Long> toolErrorCategories = new LinkedHashMap<>();
        boolean usesTaskAgent = false;

        long linesAdded = 0;
        long linesRemoved = 0;
        Set<String> filesModified = new HashSet<>();
        List<Integer> messageHours = new ArrayList<>();
        List<String> userMessageTimestamps = new ArrayList<>();
        boolean usesMcp = false;
        boolean usesWebSearch = false;
        boolean usesWebFetch = false;
        String lastAssistantTimestamp = null;

        for (JsonNode msg : log.messages()) {
            String msgTimestamp = msg.path("timestamp").asText(null);
            String type = msg.path("type").asText(null);

            if (Strings.CS.equals("assistant", type) && msg.hasNonNull("message")) {
                if (msgTimestamp != null) {
                    lastAssistantTimestamp = msgTimestamp;
                }

                JsonNode usage = msg.path("message").path("usage");
                if (usage.isObject()) {
                    inputTokens += usage.path("input_tokens").asLong(0);
                    outputTokens += usage.path("output_tokens").asLong(0);
                }

                JsonNode content = msg.path("message").path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if (!Strings.CS.equals("tool_use", block.path("type").asText(null))
                                || !block.has("name")) {
                            continue;
                        }
                        String toolName = block.path("name").asText("");
                        toolCounts.merge(toolName, 1L, Long::sum);

                        if (AGENT_TOOL_NAME.equals(toolName)
                                || LEGACY_AGENT_TOOL_NAME.equals(toolName)) {
                            usesTaskAgent = true;
                        }
                        if (Strings.CS.startsWith(toolName, "mcp__")) usesMcp = true;
                        if (Strings.CS.equals("WebSearch", toolName)) usesWebSearch = true;
                        if (Strings.CS.equals("WebFetch", toolName)) usesWebFetch = true;

                        JsonNode input = block.path("input");
                        if (!input.isObject()) continue;

                        String filePath = input.path("file_path").asText("");
                        if (!filePath.isEmpty()) {
                            String lang = getLanguageFromPath(filePath);
                            if (lang != null) {
                                languages.merge(lang, 1L, Long::sum);
                            }
                            // Files modified by Edit/Write tools
                            if (Strings.CS.equals("Edit", toolName) || Strings.CS.equals("Write", toolName)) {
                                filesModified.add(filePath);
                            }
                        }

                        if (Strings.CS.equals("Edit", toolName)) {

// DiffHunks is the repo's structuredPatch match and
                            // yields the same +/- counts for a line-level LCS.
                            String oldString = input.path("old_string").asText("");
                            String newString = input.path("new_string").asText("");
                            List<StructuredPatchHunk> hunks = DiffHunks.compute(oldString, newString);
                            long[] changed = DiffHunks.countLinesChanged(hunks, null);
                            linesAdded += changed[0];
                            linesRemoved += changed[1];
                        }

                        if (Strings.CS.equals("Write", toolName)) {
                            String writeContent = input.path("content").asText("");
                            if (!writeContent.isEmpty()) {
                                linesAdded += StringUtils.countChar(writeContent, '\n') + 1;
                            }
                        }

                        String command = input.path("command").asText("");
                        if (Strings.CS.contains(command, "git commit")) gitCommits++;
                        if (Strings.CS.contains(command, "git push")) gitPushes++;
                    }
                }
            }

            if (Strings.CS.equals("user", type) && msg.hasNonNull("message")) {
                JsonNode content = msg.path("message").path("content");

                // Only track message hours and response times for actual
                // human messages (text content, not just tool_results).
                if (isHumanMessage(content)) {
                    if (msgTimestamp != null) {


                        // NaN; a List<Integer> cannot, and NaN hours are noise).
                        Instant instant = StatsDates.parseFlexible(msgTimestamp);
                        if (instant != null) {
                            messageHours.add(
                                ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()).getHour());
                            // Timestamp collected for multi-clauding detection
                            userMessageTimestamps.add(msgTimestamp);
                        }
                    }

                    // Response time: last assistant message → this user
                    // message; only 2s-1h gaps count (real think time).
                    if (lastAssistantTimestamp != null && msgTimestamp != null) {
                        Instant assistantTime = StatsDates.parseFlexible(lastAssistantTimestamp);
                        Instant userTime = StatsDates.parseFlexible(msgTimestamp);
                        if (assistantTime != null && userTime != null) {
                            double responseTimeSec =
                                assistantTime.until(userTime, ChronoUnit.MILLIS) / 1000.0;
                            if (responseTimeSec > 2 && responseTimeSec < 3600) {
                                userResponseTimes.add(responseTimeSec);
                            }
                        }
                    }
                }

                // Tool results — error tracking
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if (!Strings.CS.equals("tool_result", block.path("type").asText(null))
                                || !block.has("content")) {
                            continue;
                        }
                        if (!block.path("is_error").asBoolean(false)) continue;
                        toolErrors++;
                        toolErrorCategories.merge(
                            categorizeToolError(block.get("content")), 1L, Long::sum);
                    }
                }

                // Interruption detection (Python-reference marker match)
                if (content.isTextual()) {
                    if (Strings.CS.contains(content.asText(), INTERRUPT_MARKER)) {
                        userInterruptions++;
                    }
                } else if (content.isArray()) {
                    for (JsonNode block : content) {
                        if (Strings.CS.equals("text", block.path("type").asText(null))
                                && block.has("text")
                                && Strings.CS.contains(block.path("text").asText(""), INTERRUPT_MARKER)) {
                            userInterruptions++;
                            break;
                        }
                    }
                }
            }
        }

        return new ToolStats(
            toolCounts, languages, gitCommits, gitPushes, inputTokens, outputTokens,
            userInterruptions, userResponseTimes, toolErrors, toolErrorCategories,
            usesTaskAgent, usesMcp, usesWebSearch, usesWebFetch,
            linesAdded, linesRemoved, filesModified, messageHours, userMessageTimestamps);
    }


    private static String categorizeToolError(JsonNode resultContent) {
        if (resultContent == null || !resultContent.isTextual()) return "Other";
        String lower = resultContent.asText().toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(lower, "exit code")) return "Command Failed";
        if (Strings.CS.contains(lower, "rejected") || Strings.CS.contains(lower, "doesn't want")) return "User Rejected";
        if (Strings.CS.contains(lower, "string to replace not found") || Strings.CS.contains(lower, "no changes")) {
            return "Edit Failed";
        }
        if (Strings.CS.contains(lower, "modified since read")) return "File Changed";
        if (Strings.CS.contains(lower, "exceeds maximum") || Strings.CS.contains(lower, "too large")) {
            return "File Too Large";
        }
        if (Strings.CS.contains(lower, "file not found") || Strings.CS.contains(lower, "does not exist")) {
            return "File Not Found";
        }
        return "Other";
    }




    static String extractFirstPrompt(List<JsonNode> messages) {
        return extractFirstPrompt(messages, _ -> false);
    }

    /** Exact first-prompt extraction with the caller's built-in command inventory. */
    static String extractFirstPrompt(
            List<JsonNode> messages, Predicate<String> builtInCommandPredicate) {
        String textContent = firstMeaningfulUserText(
            messages, builtInCommandPredicate != null ? builtInCommandPredicate : _ -> false);
        if (textContent == null) return "No prompt";
        String result = textContent.replace("\n", " ").trim();
        if (result.length() > 200) {
            result = result.substring(0, 200).trim() + "…";
        }
        return result;
    }


    private static String firstMeaningfulUserText(
            List<JsonNode> messages, Predicate<String> builtInCommandPredicate) {
        for (JsonNode msg : messages) {
            if (!Strings.CS.equals("user", msg.path("type").asText(null))) continue;
            if (msg.path("isMeta").asBoolean(false)) continue;
            if (msg.path("isCompactSummary").asBoolean(false)) continue;

            JsonNode content = msg.path("message").path("content");
            List<String> texts = new ArrayList<>();
            if (content.isTextual()) {
                texts.add(content.asText());
            } else if (content.isArray()) {
                for (JsonNode block : content) {
                    if (Strings.CS.equals("text", block.path("type").asText(null))) {
                        String blockText = block.path("text").asText("");
                        if (!blockText.isEmpty()) texts.add(blockText);
                    }
                }
            }

            for (String text : texts) {
                if (text.isEmpty()) continue;

                String commandNameTag = XmlTagUtils.extractTag(text, "command-name").orElse(null);
                if (commandNameTag != null) {
                    String commandName = Strings.CS.removeStart(commandNameTag, "/");
                    if (builtInCommandPredicate.test(commandName)) continue;
                    String commandArgs = XmlTagUtils.extractTag(text, "command-args")
                        .map(String::trim).orElse("");
                    if (commandArgs.isEmpty()) continue;
                    return commandNameTag + " " + commandArgs;
                }

                String bashInput = XmlTagUtils.extractTag(text, "bash-input").orElse(null);
                if (bashInput != null) {
                    return "! " + bashInput;
                }

                if (SKIP_FIRST_PROMPT_PATTERN.matcher(text).find()) continue;

                return text;
            }
        }
        return null;
    }

    // ── small helpers ────────────────────────────────────────────────────────


    static String getLanguageFromPath(String filePath) {
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String name = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null; // no extension, or dotfile like ".bashrc"
        return EXTENSION_TO_LANGUAGE.get(name.substring(dot).toLowerCase(Locale.ROOT));
    }


    private static boolean isHumanMessage(JsonNode content) {
        if (content.isTextual()) {
            return !content.asText().trim().isEmpty();
        }
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (Strings.CS.equals("text", block.path("type").asText(null)) && block.has("text")) {
                    return true;
                }
            }
        }
        return false;
    }


    private static Instant createdInstant(SessionLog log) {
        if (log.messages().isEmpty()) return null;
        return StatsDates.parseFlexible(log.messages().getFirst().path("timestamp").asText(null));
    }


    private static Instant modifiedInstant(SessionLog log) {
        List<JsonNode> messages = log.messages();
        if (log.leafUuid() != null) {
            for (JsonNode message : messages) {
                if (Strings.CS.equals(log.leafUuid(), message.path("uuid").asText(null))) {
                    return StatsDates.parseFlexible(message.path("timestamp").asText(null));
                }
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            String type = messages.get(i).path("type").asText(null);
            if (Strings.CS.equals("user", type) || Strings.CS.equals("assistant", type)) {
                return StatsDates.parseFlexible(messages.get(i).path("timestamp").asText(null));
            }
        }
        return null;
    }


    private static String projectPath(SessionLog log) {
        if (log.projectPath() != null) return log.projectPath();
        if (log.messages().isEmpty()) return "";
        return log.messages().getFirst().path("cwd").asText("");
    }

}
