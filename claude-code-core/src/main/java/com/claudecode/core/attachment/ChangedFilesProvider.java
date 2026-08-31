package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.claudecode.core.diff.DiffHunks;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.EditedFileAttachment;
import com.claudecode.core.io.FileTextUtils;

/**
 * Surfaces files whose on-disk content changed after the model last read them (by the user or a
 * linter), so it accounts for the edit without a re-read.
 */
public final class ChangedFilesProvider implements AttachmentProvider {

    private static final int DIFF_CONTEXT = 8;
    private static final int DIFF_SNIPPET_MAX_CHARS = 8192;
    private static final int AGGREGATE_SNIPPET_BUDGET_CHARS = 16_384;
    // Same full-text boundaries enforced by Java FileReadTool. Kept in core so
    // attachment assembly does not introduce a forbidden core -> tools edge.
    private static final long MAX_TEXT_FILE_BYTES = 256L * 1024;
    private static final int MAX_TEXT_TOKENS = 25_000;

    @Override
    public String name() {
        return "changed_files";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        FileStateCache fsc = ctx.fileStateCache();
        if (fsc == null) {
            return List.of();
        }
        List<AttachmentPayload> out = new ArrayList<>();
        int snippetChars = 0;
        for (Map.Entry<String, FileStateCache.FileState> entry : fsc.entries().entrySet()) {
            String path = entry.getKey();
            FileStateCache.FileState st = entry.getValue();
            if (st == null) {
                continue;
            }

            // the cache only contains the viewed window, not a full-file baseline.
            if (st.offset() != null || st.limit() != null) {
                continue;
            }
            if (isReadDenied(ctx, path)) {
                continue;
            }
            Path file;
            try {
                file = Path.of(path);
            } catch (RuntimeException _) {
                continue;
            }
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException _) {
                fsc.remove(path);
                continue;
            } catch (IOException _) {
// Transient stat failure (editor atomic-save race) — don't evict, just skip this
// turn.
                continue;
            }
            long mtime = attributes.lastModifiedTime().toMillis();
            if (mtime <= st.timestampMs()) {
                continue; // unchanged
            }
            if (!attributes.isRegularFile() || attributes.size() > MAX_TEXT_FILE_BYTES) {
                continue;
            }
            String current;
            try {
                byte[] bytes;
                try (var input = Files.newInputStream(file)) {
                    bytes = input.readNBytes((int) MAX_TEXT_FILE_BYTES + 1);
                }
                if (bytes.length > MAX_TEXT_FILE_BYTES) {
                    continue;
                }
                current = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (NoSuchFileException _) {
                fsc.remove(path);
                continue;
            } catch (IOException _) {
                continue;
            }
            if (looksBinary(current) || exceedsTokenCap(path, current)) {
                continue;
            }

            fsc.set(path, new FileStateCache.FileState(current, mtime, null, null, false));
            String snippet = getSnippetForTwoFileDiff(st.content(), current);
            if (snippet.isEmpty()) {
                continue; // touched but not modified
            }
            if (snippetChars >= AGGREGATE_SNIPPET_BUDGET_CHARS) {
                out.add(new EditedFileAttachment(path, ""));
            } else {
                out.add(new EditedFileAttachment(path, snippet));
                snippetChars += snippet.length();
            }
        }
        return out;
    }

    private static boolean isReadDenied(AttachmentContext context, String path) {
        try {
            return context.fileReadDenied().test(path);
        } catch (RuntimeException _) {
            // A failed security check must not become permission to read.
            return true;
        }
    }

    private static boolean looksBinary(String content) {
        return content.indexOf('\0') >= 0;
    }

    private static boolean exceedsTokenCap(String path, String content) {
        String filename = Path.of(path).getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String extension = dot >= 0 && dot < filename.length() - 1
            ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        int bytesPerToken = switch (extension) {
            case "json", "jsonl", "jsonc" -> 2;
            default -> 4;
        };
        return Math.round((double) content.length() / bytesPerToken) > MAX_TEXT_TOKENS;
    }


    private static String getSnippetForTwoFileDiff(String oldContent, String newContent) {
        List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, newContent, DIFF_CONTEXT);
        if (hunks.isEmpty()) {
            return "";
        }
        StringBuilder full = new StringBuilder();
        boolean first = true;
        for (StructuredPatchHunk hunk : hunks) {
            String content = hunk.lines().stream()
                .filter(l -> !Strings.CS.startsWith(l, "-") && !Strings.CS.startsWith(l, "\\"))
                .map(l -> l.length() > 1 ? l.substring(1) : "")
                .collect(Collectors.joining("\n"));
            String numbered = FileTextUtils.addLineNumbers(content, hunk.oldStart());
            if (!first) {
                full.append("\n...\n");
            }
            first = false;
            full.append(numbered);
        }
        String result = full.toString();
        if (result.length() <= DIFF_SNIPPET_MAX_CHARS) {
            return result;
        }
        int cutoff = result.lastIndexOf('\n', DIFF_SNIPPET_MAX_CHARS);
        String kept = cutoff > 0 ? result.substring(0, cutoff) : result.substring(0, DIFF_SNIPPET_MAX_CHARS);
        int remaining = countNewlines(result, kept.length()) + 1;
        return kept + "\n\n... [" + remaining + " lines truncated] ...";
    }

    private static int countNewlines(String s, int upto) {
        int count = 0;
        int end = Math.min(upto, s.length());
        for (int i = 0; i < end; i++) {
            if (s.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
