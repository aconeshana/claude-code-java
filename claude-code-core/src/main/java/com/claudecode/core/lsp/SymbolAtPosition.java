package com.claudecode.core.lsp;

import com.claudecode.core.io.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the identifier/operator at a 0-based (line, character) position in a file, used to
 * enrich LSP tool-use messages with the symbol under the cursor (e.g.
 */
public final class SymbolAtPosition {

    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_SYMBOL_LEN = 30;
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[\\w$'!]+|[+\\-*/%&|^~<>=]+");

    private SymbolAtPosition() {}

    /**
     * Returns the symbol/word at the given 0-based line/character, or
     * {@link Optional#empty} if it cannot be determined.
     */
    public static Optional<String> symbolAt(Path filePath, int line, int character) {
        if (!FileUtils.isRegularFile(filePath)) return Optional.empty();
        if (line < 0 || character < 0) return Optional.empty();
        try {
            byte[] buf = new byte[MAX_READ_BYTES];
            int bytesRead;
            try (InputStream in = Files.newInputStream(filePath)) {
                int total = 0;
                int n;
                while (total < MAX_READ_BYTES
                        && (n = in.read(buf, total, MAX_READ_BYTES - total)) != -1) {
                    total += n;
                }
                bytesRead = total;
            }
            String content = new String(buf, 0, bytesRead, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            if (line >= lines.length) return Optional.empty();
            // If the buffer was filled, the last line may be truncated mid-line.
            if (bytesRead >= MAX_READ_BYTES && line == lines.length - 1) return Optional.empty();
            String lineContent = lines[line];
            if (character >= lineContent.length()) return Optional.empty();
            Matcher m = SYMBOL_PATTERN.matcher(lineContent);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (character >= start && character < end) {
                    String symbol = m.group();
                    return Optional.of(symbol.length() > MAX_SYMBOL_LEN
                            ? symbol.substring(0, MAX_SYMBOL_LEN) : symbol);
                }
            }
            return Optional.empty();
        } catch (IOException _) {
            return Optional.empty();
        }
    }
}
