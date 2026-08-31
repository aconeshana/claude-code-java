package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.util.HashUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.XmlTagUtils;
import com.claudecode.core.util.UuidUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages session lifecycle: creation, directory resolution, and listing.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /**
     * Maximum length of the sanitized path component before a hash suffix is appended.
     */
    static final int MAX_SANITIZED_LENGTH = 200;

    private final Path projectDir;
    /** Original project/worktree path used to derive {@link #projectDir}. */
    private final String projectPath;
    /** {@code ~/.claude/projects} — the root holding every project's transcripts. */
    private final Path projectsRoot;

    /**
     * Creates a SessionManager using the default base directory ({@code ~/.claude})
     * and the current working directory as the project key.
     */
    public SessionManager() {
        this(ClaudePaths.CLAUDE_HOME,
             System.getProperty("user.dir"));
    }

    /**
     * Creates a SessionManager with an explicit CWD.
     *
     * @param cwd the working directory used as the project key
     */
    public SessionManager(String cwd) {
        this(ClaudePaths.CLAUDE_HOME, cwd);
    }

    /**
     * Creates a SessionManager with an explicit base directory and CWD.
     *
     * @param baseDir the Claude config home (normally {@code ~/.claude})
     * @param cwd     the working directory used as the project key
     */
    public SessionManager(Path baseDir, String cwd) {
        String projectPath = canonicalizePath(cwd != null ? cwd : System.getProperty("user.dir"));
        this.projectPath = projectPath;
        String sanitized = sanitizePath(projectPath);
        this.projectsRoot = baseDir.resolve("projects");
        this.projectDir = selectProjectDirectory(projectsRoot, sanitized);
    }

    private static Path selectProjectDirectory(Path projectsRoot, String sanitized) {
        List<Path> compatible = findCompatibleProjectDirs(projectsRoot, sanitized);
        Path exact = compatible.getFirst();
        if (Files.isDirectory(exact) || sanitized.length() <= MAX_SANITIZED_LENGTH) return exact;
        return compatible.stream().skip(1).filter(Files::isDirectory).findFirst().orElse(exact);
    }


    private static List<Path> findCompatibleProjectDirs(Path projectsRoot, String sanitized) {
        Path exact = projectsRoot.resolve(sanitized);
        List<Path> result = new ArrayList<>();
        result.add(exact);
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) return List.copyOf(result);
        String prefix = sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-";
        if (!Files.isDirectory(projectsRoot)) return List.copyOf(result);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(projectsRoot,
                p -> Files.isDirectory(p) && Strings.CS.startsWith(p.getFileName().toString(), prefix))) {
            for (Path candidate : dirs) if (!candidate.equals(exact)) result.add(candidate);
        } catch (IOException _) {
            // Missing/inaccessible projects root: create/use the exact Java path later.
        }
        result.sort(Comparator.comparing(Path::toString));
        if (result.remove(exact)) result.addFirst(exact);
        return List.copyOf(result);
    }

    /**
     * Generates a new session ID (UUID).
     * The session file is NOT pre-created — it will be created on first append.
     *
     * @return the new session ID
     */
    public String createSession() {
        return UUID.randomUUID().toString();
    }

    /** Returns whether this project already has a transcript for the supplied session id. */
    public boolean sessionIdExists(String sessionId) {
        return StringUtils.isNotEmpty(sessionId) && Files.exists(getSessionFile(sessionId));
    }

    /**
     * Returns the session JSONL file path for the given session ID.
     * Path:
     */
    public Path getSessionFile(String sessionId) {
        return projectDir.resolve(sessionId + ".jsonl");
    }

    Path projectDirectory() {
        return projectDir;
    }

    List<Path> compatibleProjectDirectories() {
        return findCompatibleProjectDirs(projectsRoot, sanitizePath(projectPath));
    }

    Path projectsRoot() {
        return projectsRoot;
    }

    /** Records a project-directory alias in this manager's project sidecar. */
    public void recordSessionAlias(Path sourceProjectDirectory) {
        if (sourceProjectDirectory == null) return;
        Path source = canonicalizeExistingPath(sourceProjectDirectory);
        Path target = canonicalizeExistingPath(projectDir);
        if (source.equals(target)) return;
        try {
            Files.createDirectories(projectDir);
            Path aliases = projectDir.resolve(".session-aliases");
            LinkedHashSet<String> lines = new LinkedHashSet<>();
            if (Files.isReadable(aliases)) {
                for (String line : Files.readAllLines(aliases, UTF_8)) {
                    if (StringUtils.isBlank(line)) continue;
                    try {
                        lines.add(canonicalizeExistingPath(Path.of(line.strip())).toString());
                    } catch (RuntimeException _) {
                        // Ignore malformed legacy sidecar rows while preserving valid aliases.
                    }
                }
            }
            if (lines.add(source.toString())) {
                Files.writeString(aliases, source + System.lineSeparator(), UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.debug("Unable to record session alias {} -> {}", projectDir, source, e);
        }
    }

    /** Records an alias for an active project directory under a newly added workspace dir. */
    public void recordSessionAlias(Path targetDirectory, Path sourceProjectDirectory) {
        if (targetDirectory == null) return;
        new SessionManager(projectsRoot.getParent(), targetDirectory.toString())
            .recordSessionAlias(sourceProjectDirectory);
    }

    List<Path> readSessionAliases() {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (Path directory : compatibleProjectDirectories()) {
            Path aliases = directory.resolve(".session-aliases");
            if (!Files.isReadable(aliases)) continue;
            try {
                for (String line : Files.readAllLines(aliases, UTF_8)) {
                    if (StringUtils.isBlank(line)) continue;
                    try {
                        Path path = canonicalizeExistingPath(Path.of(line.strip()));
                        if (Files.isDirectory(path)) result.add(path);
                    } catch (RuntimeException _) {
                        // One malformed row must not hide later valid aliases.
                    }
                }
            } catch (IOException e) {
                log.debug("Unable to read session aliases from {}", aliases, e);
            }
        }
        return List.copyOf(result);
    }

    static String canonicalizePath(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        try { path = path.toRealPath(); } catch (IOException _) { }
        return Normalizer.normalize(path.toString(), Normalizer.Form.NFC);
    }

    static Path canonicalizeExistingPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try { normalized = normalized.toRealPath(); } catch (IOException _) { }
        return Path.of(Normalizer.normalize(normalized.toString(), Normalizer.Form.NFC));
    }

    /**
     * Returns the current session's persisted large-tool-result directory.
     */
    public Path getToolResultsDir(String sessionId) {
        return projectDir.resolve(sessionId).resolve("tool-results");
    }

    /**
     * Permanently deletes a stored conversation and its session-owned sidecar directory.
     */
    @Explanation("Confirmed permanent deletion from the Java /resume picker")
    public boolean deleteSessionPermanently(String sessionId) {
        if (!UuidUtils.isValid(sessionId)) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
        Path transcript = getSessionFile(sessionId).normalize();
        Path ownedDirectory = projectDir.resolve(sessionId).normalize();
        if (!transcript.getParent().equals(projectDir.normalize())
                || !ownedDirectory.getParent().equals(projectDir.normalize())) {
            throw new IllegalArgumentException("Session path escapes project directory: " + sessionId);
        }

        boolean existed = Files.exists(transcript) || Files.exists(ownedDirectory);
        try {
            deleteTreeStrict(ownedDirectory);
            Files.deleteIfExists(transcript);
            return existed;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to permanently delete session " + sessionId, e);
        }
    }

    private static void deleteTreeStrict(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    /**
     * Returns the sidechain transcript file path for a sub-agent invocation.
     */
    public Path getAgentTranscriptPath(String parentSessionId, String agentId) {
        return projectDir.resolve(parentSessionId).resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
    }


    public Path getWorkflowTranscriptDir(String sessionId, String runId) {
        return projectDir.resolve(sessionId).resolve("subagents")
            .resolve("workflows").resolve(runId);
    }


    public Path getWorkflowRunPath(String sessionId, String runId) {
        return projectDir.resolve(sessionId).resolve("workflows").resolve(runId + ".json");
    }

    // ── JSONL metadata tail scanning ─────────────────────────────────────────


    private static final int META_TAIL_BYTES = 64 * 1024;

    /**
     * Reads the most-recent {@code customTitle} value for a session by scanning the tail of its JSONL.
     */
    public String readCustomTitle(String sessionId) {
        Path file = getSessionFile(sessionId);
        if (!Files.isReadable(file)) return null;
        try {
            String tail = readTail(file, META_TAIL_BYTES);
            String custom = lastJsonStringField(tail, "customTitle");
            return custom != null ? custom : lastJsonStringField(tail, "aiTitle");
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Reads the most-recent {@code agentName} value for a session by scanning the tail of its JSONL.
     */
    public String readAgentName(String sessionId) {
        Path file = getSessionFile(sessionId);
        if (!Files.isReadable(file)) return null;
        try {
            String tail = readTail(file, META_TAIL_BYTES);
            return lastJsonStringField(tail, "agentName");
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Reads the most-recent {@code agentColor} value for a session by scanning the tail of its JSONL.
     */
    public String readAgentColor(String sessionId) {
        Path file = getSessionFile(sessionId);
        if (!Files.isReadable(file)) return null;
        try {
            String tail = readTail(file, META_TAIL_BYTES);
            return lastJsonStringField(tail, "agentColor");
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Reads the session id this session was derived from — a {@code /clear} predecessor or a {@code
     * /branch} source — by scanning the tail of its JSONL for a {@code parent-session} entry.
     */
    public String readParentSessionId(String sessionId) {
        Path file = getSessionFile(sessionId);
        if (!Files.isReadable(file)) return null;
        try {
            String tail = readTail(file, META_TAIL_BYTES);
            return lastJsonStringField(tail, "parentSessionId");
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Reads how this session relates to its {@link #readParentSessionId}
     * predecessor: {@code "clear"} or {@code "branch"}. {@code null} when
     * there is no recorded parent.
     */
    public String readParentRelation(String sessionId) {
        Path file = getSessionFile(sessionId);
        if (!Files.isReadable(file)) return null;
        try {
            String tail = readTail(file, META_TAIL_BYTES);
            return lastJsonStringField(tail, "relation");
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Records that {@code sessionId} was derived from {@code parentSessionId} via {@code relation}
     * ({@code "clear"} or {@code "branch"}) — a {@code parent-session} JSONL entry in the same
     * lightweight-metadata family as {@code custom-title}/{@code tag}/{@code agent-name}.
     */
    public void appendParentSession(String sessionId, String parentSessionId, String relation) {
        if (StringUtils.isBlank(sessionId)
                || parentSessionId == null || StringUtils.isBlank(parentSessionId)) {
            return;
        }
        Path file = getSessionFile(sessionId);
        new SessionStorage().appendCustomEntry(file, parentSessionEntry(parentSessionId, relation, sessionId));
    }

    /**
     * Re-appends session-scoped metadata entries to the JSONL EOF so the 64KB tail window used by
     * {@code readLiteMetadata} still sees them after long conversations push the original entries out
     * of scope.
     */
    public void reAppendSessionMetadata(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return;
        Path file = getSessionFile(sessionId);
        if (!Files.isReadable(file)) return;
        ObjectNode lastPrompt = null;
        ObjectNode title = null;
        ObjectNode tag = null;
        ObjectNode agentName = null;
        ObjectNode agentColor = null;
        ObjectNode agentSetting = null;
        ObjectNode mode = null;
        ObjectNode worktree = null;
        ObjectNode parentSession = null;
        try (var reader = Files.newBufferedReader(file, UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode parsed = JsonUtils.getMapper().readTree(line);
                    if (!(parsed instanceof ObjectNode node)) continue;
                    switch (node.path("type").asText()) {
                        case "last-prompt" -> lastPrompt = node.deepCopy();
                        case "custom-title" -> title = node.deepCopy();
                        case "tag" -> tag = node.deepCopy();
                        case "agent-name" -> agentName = node.deepCopy();
                        case "agent-color" -> agentColor = node.deepCopy();
                        case "agent-setting" -> agentSetting = node.deepCopy();
                        case "mode" -> mode = node.deepCopy();
                        case "worktree-state" -> worktree = node.deepCopy();
                        case "parent-session" -> parentSession = node.deepCopy();
                        default -> { }
                    }
                } catch (IOException | RuntimeException _) {
                    // Transcript readers tolerate damaged JSONL rows; metadata
                    // refresh must not make graceful shutdown less resilient.
                }
            }
        } catch (IOException _) {
            return;
        }

        SessionStorage ss = new SessionStorage();
        appendMetadataCopy(ss, file, lastPrompt, sessionId, null);
        appendMetadataCopy(ss, file, title, sessionId, "customTitle");
        appendMetadataCopy(ss, file, tag, sessionId, "tag");
        appendMetadataCopy(ss, file, agentName, sessionId, "agentName");
        appendMetadataCopy(ss, file, agentColor, sessionId, "agentColor");
        appendMetadataCopy(ss, file, agentSetting, sessionId, "agentSetting");
        appendMetadataCopy(ss, file, mode, sessionId, "mode");
        appendMetadataCopy(ss, file, worktree, sessionId, null);
        appendMetadataCopy(ss, file, parentSession, sessionId, "parentSessionId");
    }

    private static void appendMetadataCopy(SessionStorage storage, Path file,
                                           ObjectNode entry, String sessionId,
                                           String clearableField) {
        if (entry == null) return;
        if (clearableField != null
                && StringUtils.isEmpty(entry.path(clearableField).asText(null))) {
            return;
        }
        entry.put("sessionId", sessionId);
        storage.appendCustomEntry(file, entry);
    }

    /** Builds a {@code parent-session} entry — see {@link #appendParentSession}. */
    private static ObjectNode parentSessionEntry(
            String parentSessionId, String relation, String sessionId) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("type", "parent-session");
        node.put("parentSessionId", parentSessionId);
        node.put("relation", relation);
        node.put("sessionId", sessionId);
        return node;
    }

    /**
     * Returns the project directory ({@code ~/.claude/projects/<sanitized-cwd>}).
     */
    public Path getProjectDir() {
        return projectDir;
    }

    /** Project/worktree path used to derive this manager's storage directory. */
    public String projectPath() {
        return projectPath;
    }

    /**
     * Reads the {@code cwd} field stamped into the first non-metadata line of
     * a session file. Exact-ID fallback lookup uses it to recover the session's
     * original project path when catalog enrichment is unavailable. Returns
     * {@link Optional#empty()} when the file is unreadable or holds only
     * metadata entries.
     */
    public Optional<String> readSessionCwd(String sessionId) {
        Path file = getSessionFile(sessionId);
        if (!Files.exists(file)) return Optional.empty();
        try (Stream<String> lines = Files.lines(file, UTF_8)) {
            return lines
                .filter(l -> !StringUtils.isBlank(l) && !SessionStorage.isMetadataEntry(l))
                .map(SessionManager::extractCwd)
                .filter(Objects::nonNull)
                .findFirst();
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    private static String extractCwd(String line) {
        int idx = line.indexOf("\"cwd\":\"");
        if (idx < 0) return null;
        int start = idx + 7;
        int end = line.indexOf('"', start);
        if (end < 0) return null;
        return line.substring(start, end);
    }















    public List<SessionInfo> listSessions() {
        return listSessions(Integer.MAX_VALUE);
    }

    /**
     * Lists sessions, parsing at most {@code limit} entries.
     * <p>
     * Uses two-phase loading for performance — Preserves compatibility with {@code onLoadMore} pagination:
     * <ol>
     *   <li>no content reads</li>
     *   <li>Phase 2 (expensive): parse head/tail of the top-{@code limit} newest files</li>
     * </ol>
     * This lets the caller load an initial batch quickly and request more lazily.
     *
     * @param limit maximum number of visible sessions to return
     * @return sorted list of up to {@code limit} session info records
     */
    public List<SessionInfo> listSessions(int limit) {
        if (limit <= 0) return List.of();
        return SessionCatalog.forProject(this, _ -> false).loadMore(limit).stream()
            .map(SessionCatalog.Entry::info).toList();
    }











    public List<SessionInfo> listAllProjectsSessions(int limit) {
        if (limit <= 0) return List.of();
        return SessionCatalog.forAllProjects(this, _ -> false).loadMore(limit).stream()
            .map(SessionCatalog.Entry::info).toList();
    }

    // ── File head/tail reading ───────────────────────────────────────────────

    private String readTail(Path file, int maxBytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(maxBytes, size);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long position = Math.max(0, size - length);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position + buffer.position());
                if (read <= 0) break;
            }
            return new String(buffer.array(), 0, buffer.position(), UTF_8);
        }
    }

    // ── JSONL field extraction ───────────────────────────────────────────────

    /**
     * Extracts the LAST occurrence of a JSON string field from text.
     * Preserves compatibility with {@code extractLastJsonStringField}.
     */
    static String lastJsonStringField(String text, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int pos = text.lastIndexOf(key);
        if (pos == -1) {
            key = "\"" + fieldName + "\": \"";
            pos = text.lastIndexOf(key);
        }
        if (pos == -1) return null;
        int start = pos + key.length();
        int end = findJsonStringEnd(text, start);
        if (end == -1) return null;
        return unescapeJsonString(text.substring(start, end));
    }

    /**
     * Extracts the FIRST occurrence of a JSON string field from text.
     */
    static String jsonStringField(String text, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int pos = text.indexOf(key);
        if (pos == -1) {
            key = "\"" + fieldName + "\": \"";
            pos = text.indexOf(key);
        }
        if (pos == -1) return null;
        int start = pos + key.length();
        int end = findJsonStringEnd(text, start);
        if (end == -1) return null;
        return unescapeJsonString(text.substring(start, end));
    }

    /**
     * Extracts the first user-visible prompt from the head of a JSONL file.
     */
    static String extractFirstPromptFromHead(String head) {
        String firstCommandFallback = null;
        for (String line : head.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String text;
            try {
                JsonNode node = JsonUtils.getMapper().readTree(line);
                if (node == null || !Strings.CS.equals("user", node.path("type").asText())) continue;
                if (node.path("isMeta").asBoolean(false)) continue;
                if (Strings.CS.equals("hook", node.path("subtype").asText())) continue;


                // legacy top-level content plus Java-native message.text/blocks.
                JsonNode message = node.get("message");
                text = firstText(message != null ? message : node);
            } catch (Exception _) {
                // A partially-written final JSONL row must not make every older
                // valid session disappear. Fall back to the former lite scan.
                if (!Strings.CS.contains(line, "\"type\":\"user\"")
                        && !Strings.CS.contains(line, "\"type\": \"user\"")) continue;
                text = jsonStringField(line, "text");
            }
            if (text == null) continue;
            // The welcome screen renders each recent activity item as one row.

            // prompts before they become a session summary.
            text = text.replace('\n', ' ').trim();
            if (text.isEmpty()) continue;

            String commandName = XmlTagUtils.extractTag(text, "command-name")
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
            if (commandName != null) {
                String commandArgs = XmlTagUtils.extractTag(text, "command-args")
                    .map(String::trim)
                    .orElse("");
                if (firstCommandFallback == null) {
                    firstCommandFallback = commandName
                        + (commandArgs.isEmpty() ? "" : " " + commandArgs);
                }
                continue;
            }

            // Skip IDE/context attachments and hook output encoded in prose.
            if (Strings.CS.contains(text, "<system-reminder>") || Strings.CS.contains(text, "ide-attached")) continue;
            // Plain slash input from older transcripts: retain it as the same
            // clean fallback used for command envelopes.
            if (Strings.CS.startsWith(text, "/")) {
                if (firstCommandFallback == null) firstCommandFallback = text;
                continue;
            }
            return text.length() > 200 ? text.substring(0, 200).trim() + '\u2026' : text;
        }
        return firstCommandFallback;
    }

    private static String firstText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = firstText(item);
                if (StringUtils.isNotBlank(text)) return text;
            }
            return null;
        }
        if (!node.isObject()) return null;
        if (node.has("content")) {
            String text = firstText(node.get("content"));
            if (StringUtils.isNotBlank(text)) return text;
        }
        if (node.has("text") && node.get("text").isTextual()) {
            return node.get("text").asText();
        }
        if (node.has("blocks")) return firstText(node.get("blocks"));
        return null;
    }

    /** Finds the end index of a JSON string starting at {@code start} (after the opening quote). */
    private static int findJsonStringEnd(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; } // skip escaped char
            if (c == '"') return i;
        }
        return -1;
    }

    /** Unescapes JSON string escape sequences. */
    private static String unescapeJsonString(String s) {
        if (!Strings.CS.contains(s, "\\")) return s;
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }



    /**
     * Sanitizes a path string for use as a directory name.
     */
    public static String sanitizePath(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9]", "-");
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return sanitized;
        }
        String hash = Long.toString(Math.abs((long) HashUtils.djb2(name)), 36);
        return sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-" + hash;
    }
}
