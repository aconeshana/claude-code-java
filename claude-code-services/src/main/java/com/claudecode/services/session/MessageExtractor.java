package com.claudecode.services.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.io.FileTextUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static helpers that walk a persisted conversation and pull out the two
 * per-session state sets used by {@code /resume}:
 *
 * <ul>
 *   <li>
 *       (lines 362-520) — two-pass reconstruction of the full
 *       {@link FileStateCache} (content + mtime, not just paths) for every
 *       {@code Read} / {@code Write} / {@code Edit} tool_use in the
 *       transcript. See {@link #extractReadFileState} for the pass-by-pass
 *       mapping. Also retains the legacy path-only
 *       {@link #extractReadFilePaths} for the bypass
 *       {@code QuerySession.readFileState} marker map.</li>
 *   <li>
 *       (lines 523-550) + {@code extractCliName} (lines 559+) — CLI names
 *       actually invoked via {@code Bash} tool. Feeds
 *       {@code QuerySession.bashTools} so future autocompletion and permission
 *       exemptions know which commands the session has already used.</li>
 * </ul>
 */
public final class MessageExtractor {


    private static final String READ_TOOL_NAME  = "Read";
    private static final String WRITE_TOOL_NAME = "Write";
    private static final String EDIT_TOOL_NAME  = "Edit";
    private static final String BASH_TOOL_NAME  = "Bash";


    private static final Set<String> STRIPPED_COMMAND_PREFIXES = Set.of("sudo");


    private static final String FILE_UNCHANGED_STUB =
        "File unchanged since last read. The content from the earlier Read "
        + "tool_result in this conversation is still current — refer to that "
        + "instead of re-reading.";

    private static final Pattern SYSTEM_REMINDER_BLOCK =
        Pattern.compile("<system-reminder>.*?</system-reminder>", Pattern.DOTALL);

    private MessageExtractor() {}

    /**
     * Returns the set of absolute file paths the persisted assistant messages asked the write/read
     * tools to touch.
     */
    public static Set<String> extractReadFilePaths(List<Message> messages, String cwd) {
        if (messages == null || messages.isEmpty()) return Set.of();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am) || am.message() == null) continue;
            List<ContentBlock> content = am.message().content();
            if (content == null) continue;
            for (ContentBlock b : content) {
                if (!(b instanceof ToolUseBlock tub) || tub.input() == null) continue;
                String name = tub.name();
                if (READ_TOOL_NAME.equals(name)) {
                    JsonNode input = tub.input();
                    if (input.has("offset") || input.has("limit")) continue;
                    String p = expandPath(inputString(input, "file_path"), cwd);
                    if (p != null) paths.add(p);
                } else if (WRITE_TOOL_NAME.equals(name) || EDIT_TOOL_NAME.equals(name)) {
                    String p = expandPath(inputString(tub.input(), "file_path"), cwd);
                    if (p != null) paths.add(p);
                }
            }
        }
        return paths;
    }


    public static FileStateCache extractReadFileState(List<Message> messages, String cwd) {
        FileStateCache cache = new FileStateCache();
        if (messages == null || messages.isEmpty()) return cache;

        Map<String, String> fileReadToolUseIds = new HashMap<>();
        Map<String, WriteToolUse> fileWriteToolUseIds = new HashMap<>();
        Map<String, String> fileEditToolUseIds = new HashMap<>();

        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am) || am.message() == null) continue;
            List<ContentBlock> content = am.message().content();
            if (content == null) continue;
            for (ContentBlock b : content) {
                if (!(b instanceof ToolUseBlock tub) || tub.input() == null || tub.id() == null) continue;
                String name = tub.name();
                JsonNode input = tub.input();
                if (READ_TOOL_NAME.equals(name)) {
                    if (input.has("offset") || input.has("limit")) continue;
                    String p = expandPath(inputString(input, "file_path"), cwd);
                    if (p != null) fileReadToolUseIds.put(tub.id(), p);
                } else if (WRITE_TOOL_NAME.equals(name)) {
                    String p = expandPath(inputString(input, "file_path"), cwd);
                    String writeContent = inputString(input, "content");
                    if (p != null && writeContent != null && !writeContent.isEmpty()) {
                        fileWriteToolUseIds.put(tub.id(), new WriteToolUse(p, writeContent));
                    }
                } else if (EDIT_TOOL_NAME.equals(name)) {
                    String p = expandPath(inputString(input, "file_path"), cwd);
                    if (p != null) fileEditToolUseIds.put(tub.id(), p);
                }
            }
        }

        for (Message m : messages) {
            if (!(m instanceof UserMessage um) || um.message() == null) continue;
            List<ContentBlock> content = um.message().blocks();
            if (content == null) continue;
            Long timestampMs = um.timestamp().map(Instant::toEpochMilli).orElse(null);
            for (ContentBlock b : content) {
                if (!(b instanceof ToolResultBlock trb) || trb.toolUseId() == null) continue;

                String readFilePath = fileReadToolUseIds.get(trb.toolUseId());
                if (readFilePath != null && timestampMs != null) {
                    String text = flattenText(trb.content());
                    if (text != null && !Strings.CS.startsWith(text, FILE_UNCHANGED_STUB)) {
                        String processed = SYSTEM_REMINDER_BLOCK.matcher(text).replaceAll("");
                        String fileContent = FileTextUtils.stripLineNumberPrefixes(processed).trim();
                        cache.set(readFilePath, new FileStateCache.FileState(
                            fileContent, timestampMs, null, null, false));
                    }
                }

                WriteToolUse writeData = fileWriteToolUseIds.get(trb.toolUseId());
                if (writeData != null && timestampMs != null) {
                    cache.set(writeData.filePath(), new FileStateCache.FileState(
                        writeData.content(), timestampMs, null, null, false));
                }

                String editFilePath = fileEditToolUseIds.get(trb.toolUseId());
                if (editFilePath != null && !trb.isError()) {
                    try {
                        Path path = Paths.get(editFilePath);
                        String diskContent = Files.readString(path);
                        long mtime = FileUtils.modificationTimeMillis(path);
                        cache.set(editFilePath, new FileStateCache.FileState(
                            diskContent, mtime, null, null, false));
                    } catch (NoSuchFileException _) {

                    } catch (IOException _) {
                        // Inaccessible (permissions, etc.) — skip.
                    }
                }
            }
        }

        return cache;
    }

    private record WriteToolUse(String filePath, String content) {}

    private static String flattenText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        String joined = MessageConstants.extractTextContent(blocks, "\n");
        return joined.isEmpty() ? null : joined;
    }


    public static Set<String> extractBashTools(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> tools = new HashSet<>();
        for (Message m : messages) {
            if (!(m instanceof AssistantMessage am) || am.message() == null) continue;
            List<ContentBlock> content = am.message().content();
            if (content == null) continue;
            for (ContentBlock b : content) {
                if (!(b instanceof ToolUseBlock tub)) continue;
                if (!BASH_TOOL_NAME.equals(tub.name())) continue;
                String command = inputString(tub.input(), "command");
                String cli = extractCliName(command);
                if (cli != null) tools.add(cli);
            }
        }
        return tools;
    }


    public static String extractCliName(String command) {
        if (StringUtils.isBlank(command)) return null;
        String[] tokens = command.trim().split("\\s+");
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            // Skip env-var assignments like FOO=bar
            if (tok.indexOf('=') >= 0 && isEnvVarAssignment(tok)) continue;
            if (STRIPPED_COMMAND_PREFIXES.contains(tok)) continue;
            // Return the first non-skipped token, stripping any leading path.
            int slash = tok.lastIndexOf('/');
            return slash >= 0 ? tok.substring(slash + 1) : tok;
        }
        return null;
    }

    private static boolean isEnvVarAssignment(String tok) {
        int eq = tok.indexOf('=');
        if (eq <= 0) return false;
        for (int i = 0; i < eq; i++) {
            char c = tok.charAt(i);
            // env var names are [A-Za-z_][A-Za-z0-9_]*
            if (i == 0 && !(Character.isLetter(c) || c == '_')) return false;
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static String inputString(JsonNode input, String field) {
        if (input == null || !input.has(field)) return null;
        JsonNode n = input.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String expandPath(String p, String cwd) {
        if (StringUtils.isBlank(p)) return null;
        return PathUtils.expandPath(p, cwd == null ? System.getProperty("user.dir") : cwd).toString();
    }
}
