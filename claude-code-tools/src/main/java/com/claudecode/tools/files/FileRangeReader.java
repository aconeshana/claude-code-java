package com.claudecode.tools.files;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.io.FileTextUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a line range while keeping large-file memory proportional to the
 * requested window rather than the whole file.
 *
 * <ul>
 *   <li>the under-10MB fast path and
 *       chunked large-file path that discards lines outside the selected range.</li>
 * </ul>
 */
final class FileRangeReader {

    private static final long FAST_PATH_MAX_BYTES = 10L * 1024 * 1024;
    private static final int STREAM_BUFFER_CHARS = 512 * 1024;

    record Result(String content, String fullContent, int lineCount, int totalLines,
                  long totalBytes, long readBytes, long mtimeMs) {}

    private FileRangeReader() {}

    static Result read(Path path, int zeroBasedOffset, int maxLines,
                       AbortController abortController) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.size() < FAST_PATH_MAX_BYTES) {
            return readFast(path, attributes, zeroBasedOffset, maxLines, abortController);
        }
        return readStreaming(path, attributes, zeroBasedOffset, maxLines, abortController);
    }

    private static Result readFast(Path path, BasicFileAttributes attributes,
                                   int zeroBasedOffset, int maxLines,
                                   AbortController abortController) throws IOException {
        throwIfAborted(abortController);
        String raw = FileTextUtils.readWithMetadata(path).content();
        if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') raw = raw.substring(1);
        Selection selection = select(raw, zeroBasedOffset, maxLines);
        return new Result(selection.content(), raw, selection.lineCount(), selection.totalLines(),
            attributes.size(), utf8Length(selection.content()), attributes.lastModifiedTime().toMillis());
    }

    private static Result readStreaming(Path path, BasicFileAttributes attributes,
                                        int zeroBasedOffset, int maxLines,
                                        AbortController abortController) throws IOException {
        Charset charset = FileTextUtils.detectEncoding(path);
        int endLine = saturatedEnd(zeroBasedOffset, maxLines);
        List<String> selectedLines = new ArrayList<>(Math.min(maxLines, 256));
        StringBuilder selectedLine = null;
        int currentLine = 0;
        boolean firstCharacter = true;
        char[] buffer = new char[STREAM_BUFFER_CHARS];

        try (BufferedReader reader = Files.newBufferedReader(path, charset)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                throwIfAborted(abortController);
                for (int i = 0; i < read; i++) {
                    char ch = buffer[i];
                    if (firstCharacter) {
                        firstCharacter = false;
                        if (ch == '\uFEFF') continue;
                    }
                    boolean selected = currentLine >= zeroBasedOffset && currentLine < endLine;
                    if (ch == '\n') {
                        if (selected) {
                            selectedLines.add(stripTrailingCarriageReturn(selectedLine));
                            selectedLine = null;
                        }
                        currentLine++;
                    } else if (selected) {
                        if (selectedLine == null) selectedLine = new StringBuilder();
                        selectedLine.append(ch);
                    }
                }
            }
        }

        if (currentLine >= zeroBasedOffset && currentLine < endLine) {
            selectedLines.add(stripTrailingCarriageReturn(selectedLine));
        }
        int totalLines = currentLine + 1;
        String content = String.join("\n", selectedLines);
        return new Result(content, null, selectedLines.size(), totalLines,
            attributes.size(), utf8Length(content), attributes.lastModifiedTime().toMillis());
    }

    private static Selection select(String text, int zeroBasedOffset, int maxLines) {
        int endLine = saturatedEnd(zeroBasedOffset, maxLines);
        List<String> selectedLines = new ArrayList<>(Math.min(maxLines, 256));
        int currentLine = 0;
        int lineStart = 0;
        int newline;
        while ((newline = text.indexOf('\n', lineStart)) >= 0) {
            if (currentLine >= zeroBasedOffset && currentLine < endLine) {
                int lineEnd = newline > lineStart && text.charAt(newline - 1) == '\r'
                    ? newline - 1 : newline;
                selectedLines.add(text.substring(lineStart, lineEnd));
            }
            currentLine++;
            lineStart = newline + 1;
        }
        if (currentLine >= zeroBasedOffset && currentLine < endLine) {
            int lineEnd = text.length() > lineStart && text.charAt(text.length() - 1) == '\r'
                ? text.length() - 1 : text.length();
            selectedLines.add(text.substring(lineStart, lineEnd));
        }
        return new Selection(String.join("\n", selectedLines), selectedLines.size(), currentLine + 1);
    }

    private static int saturatedEnd(int offset, int maxLines) {
        long end = (long) offset + maxLines;
        return end >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) end;
    }

    private static String stripTrailingCarriageReturn(StringBuilder line) {
        if (line == null || line.isEmpty()) return "";
        int length = line.length();
        if (line.charAt(length - 1) == '\r') line.setLength(length - 1);
        return line.toString();
    }

    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void throwIfAborted(AbortController abortController) {
        if (abortController != null) abortController.throwIfAborted();
    }

    private record Selection(String content, int lineCount, int totalLines) {}
}
