package com.claudecode.commands.diff;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups a conversation transcript into turns (one per real user prompt) and extracts the file
 * edits performed in each turn, for the {@code /diff} per-turn view.
 */
public final class TurnDiffExtractor {

    private static final int PREVIEW_MAX_LENGTH = 30;

    private TurnDiffExtractor() {}


    public record TurnFileDiff(
        String filePath,
        List<StructuredPatchHunk> hunks,
        boolean isNewFile,
        int linesAdded,
        int linesRemoved
    ) {
        public TurnFileDiff {
            hunks = hunks != null ? List.copyOf(hunks) : List.of();
        }
    }


    public record TurnDiff(
        int turnIndex,
        String userPromptPreview,
        List<TurnFileDiff> files,
        DiffData.Stats stats
    ) {
        public TurnDiff {
            files = files != null ? List.copyOf(files) : List.of();
        }
    }

    /**
     * Extracts turn diffs from the transcript, most recent turn first. Turns
     * without file edits are dropped.
     */
    public static List<TurnDiff> extract(List<Message> messages) {
        List<TurnDiff> completedTurns = new ArrayList<>();
        int turnIndex = 0;
        TurnAccumulator currentTurn = null;

        for (Message message : messages) {
            if (!(message instanceof UserMessage user)) continue;

            boolean isToolResult = user.toolUseResult() != null
                || firstBlockIsToolResult(user.message());

            if (!isToolResult && !user.isMeta()) {
                if (currentTurn != null && !currentTurn.files.isEmpty()) {
                    completedTurns.add(currentTurn.build());
                }
                turnIndex++;
                currentTurn = new TurnAccumulator(turnIndex, promptPreview(user.message()));
            } else if (currentTurn != null && user.toolUseResult() != null) {
                FileChangeResult change = coerce(user.toolUseResult());
                if (change != null && isFileEditResult(change)) {
                    currentTurn.merge(change);
                }
            }
        }

        if (currentTurn != null && !currentTurn.files.isEmpty()) {
            completedTurns.add(currentTurn.build());
        }

        return List.copyOf(completedTurns.reversed());
    }

    // ── payload coercion ─────────────────────────────────────────────────────

    /**
     * Normalizes a {@code toolUseResult} payload to {@link FileChangeResult}.
     * Live sessions carry the record itself; sessions resumed from JSONL carry
     * Jackson's untyped {@code Map} deserialization of the same JSON. Anything
     * else (string results, other tools' payloads) yields {@code null}.
     */
    static FileChangeResult coerce(Object payload) {
        if (payload instanceof FileChangeResult result) return result;
        if (payload instanceof Map<?, ?> map) {
            try {
                return JsonUtils.getMapper().convertValue(map, FileChangeResult.class);
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
        return null;
    }


    static boolean isFileEditResult(FileChangeResult result) {
        return result.filePath() != null
            && (!result.structuredPatch().isEmpty()
                || (Strings.CS.equals("create", result.type()) && result.content() != null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean firstBlockIsToolResult(MessageContent content) {
        return content != null
            && content.blocks() != null
            && !content.blocks().isEmpty()
            && content.blocks().getFirst() instanceof ToolResultBlock;
    }


    private static String promptPreview(MessageContent content) {
        String text = content != null && content.isText() ? content.text() : "";
        if (text.length() <= PREVIEW_MAX_LENGTH) return text;
        return FormatUtils.truncate(text, PREVIEW_MAX_LENGTH);
    }

    /** Mutable per-turn accumulator; sealed into an immutable {@link TurnDiff} by {@link #build()}. */
    private static final class TurnAccumulator {
        final int turnIndex;
        final String userPromptPreview;
        final LinkedHashMap<String, FileAccumulator> files = new LinkedHashMap<>();

        TurnAccumulator(int turnIndex, String userPromptPreview) {
            this.turnIndex = turnIndex;
            this.userPromptPreview = userPromptPreview;
        }

        void merge(FileChangeResult change) {
            boolean isNewFile = Strings.CS.equals("create", change.type());
            FileAccumulator file = files.computeIfAbsent(
                change.filePath(), path -> new FileAccumulator(path, isNewFile));

            if (isNewFile && change.structuredPatch().isEmpty() && change.content() != null) {
                // Write-create carries full content, no patch — synthesize one

                List<String> contentLines = List.of(change.content().split("\n", -1));
                file.hunks.add(new StructuredPatchHunk(
                    0, 0, 1, contentLines.size(),
                    contentLines.stream().map(line -> "+" + line).toList()));
                file.linesAdded += contentLines.size();
            } else {
                // Same file may be edited multiple times in a turn — append.
                file.hunks.addAll(change.structuredPatch());
                for (StructuredPatchHunk hunk : change.structuredPatch()) {
                    file.linesAdded += hunk.addedCount();
                    file.linesRemoved += hunk.removedCount();
                }
            }

            // Created then edited in the same turn → still a new file.
            if (isNewFile) file.isNewFile = true;
        }

        TurnDiff build() {
            int totalAdded = 0;
            int totalRemoved = 0;
            List<TurnFileDiff> fileDiffs = new ArrayList<>(files.size());
            for (FileAccumulator file : files.values()) {
                totalAdded += file.linesAdded;
                totalRemoved += file.linesRemoved;
                fileDiffs.add(new TurnFileDiff(
                    file.filePath, file.hunks, file.isNewFile, file.linesAdded, file.linesRemoved));
            }
            return new TurnDiff(turnIndex, userPromptPreview, fileDiffs,
                new DiffData.Stats(files.size(), totalAdded, totalRemoved));
        }
    }

    private static final class FileAccumulator {
        final String filePath;
        final List<StructuredPatchHunk> hunks = new ArrayList<>();
        boolean isNewFile;
        int linesAdded;
        int linesRemoved;

        FileAccumulator(String filePath, boolean isNewFile) {
            this.filePath = filePath;
            this.isNewFile = isNewFile;
        }
    }
}
