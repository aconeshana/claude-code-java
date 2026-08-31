package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Secure temporary {@code CLAUDE_CONFIG_DIR} materialization for custom stores.
resume/continue SessionStore behavior.</li></ul>
 */
final class SessionStoreMaterializer {
    private static final Pattern UNSAFE = Pattern.compile("(^|[\\\\/])\\.\\.([\\\\/]|$)");
    private SessionStoreMaterializer() {}

    static Path prepare(QueryOptions options) throws Exception {
        if (options.sessionStore == null || (!options.continueConversation && options.resume == null)) return null;
        Path cwd = options.cwd == null ? Path.of(".").toAbsolutePath().normalize()
            : options.cwd.toAbsolutePath().normalize();
        String projectKey = projectKey(cwd);
        String sessionId = options.resume;
        if (sessionId == null) {
            sessionId = options.sessionStore.listSessions(projectKey).stream()
                .max(Comparator.comparingLong(StoredSession::mtime))
                .map(StoredSession::sessionId).orElse(null);
        }
        if (sessionId == null) return null;
        List<JsonNode> main = options.sessionStore.load(new SessionStoreKey(projectKey, sessionId));
        if (main == null || main.isEmpty()) return null;
        Path temp = Files.createTempDirectory("claude-resume-");
        Path projectDir = temp.resolve("projects").resolve(projectKey);
        Files.createDirectories(projectDir);
        writeJsonl(projectDir.resolve(sessionId + ".jsonl"), main);
        SessionStoreKey root = new SessionStoreKey(projectKey, sessionId);
        for (String subpath : options.sessionStore.listSubkeys(root)) {
            if (!safe(subpath)) continue;
            List<JsonNode> entries = options.sessionStore.load(
                new SessionStoreKey(projectKey, sessionId, subpath));
            if (entries == null || entries.isEmpty()) continue;
            Path target = projectDir.resolve(sessionId).resolve(subpath + ".jsonl").normalize();
            if (!target.startsWith(projectDir.resolve(sessionId))) continue;
            Files.createDirectories(target.getParent());
            writeJsonl(target, entries);
        }
        Map<String, String> env = new LinkedHashMap<>(options.env);
        env.put("CLAUDE_CONFIG_DIR", temp.toString());
        options.env = Map.copyOf(env);
        options.resume = sessionId;
        options.continueConversation = false;
        return temp;
    }

    static SessionStoreKey keyForFrame(String filePath, QueryOptions options) {
        if (filePath == null || options.sessionStore == null) return null;
        Path path;
        try {
            path = Path.of(filePath).normalize();
        } catch (RuntimeException _) {
            return null;
        }
        String name = path.getFileName().toString();
        if (!Strings.CS.endsWith(name, ".jsonl")) return null;
        Path cwd = options.cwd == null ? Path.of(".").toAbsolutePath().normalize()
            : options.cwd.toAbsolutePath().normalize();
        Path parent = path.getParent();
        String substring = name.substring(0, name.length() - 6);
        if (parent != null && Strings.CS.equals(
                "subagents", parent.getFileName().toString())) {
            Path sessionDir = parent.getParent();
            if (sessionDir == null) return null;
            String sessionId = sessionDir.getFileName().toString();
            return new SessionStoreKey(projectKey(cwd), sessionId, "subagents/" + substring);
        }
        return new SessionStoreKey(projectKey(cwd), substring);
    }

    private static void writeJsonl(Path target, List<JsonNode> entries) throws IOException {
        try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (JsonNode entry : entries) {
                writer.write(entry.toString());
                writer.newLine();
            }
        }
    }

    private static boolean safe(String subpath) {
        return StringUtils.isNotBlank(subpath) && !Path.of(subpath).isAbsolute()
            && !UNSAFE.matcher(subpath).find();
    }

    private static String projectKey(Path cwd) {
        return SessionStoreProjectKey.fromDirectory(cwd.toString());
    }
}
