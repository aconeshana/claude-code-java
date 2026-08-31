package com.claudecode.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Shared text-file encoding, line-ending and line-number helpers.
 *
 * <ul>
 *   <li>Covers:  in full.</li>
 *   <li>Covers:,
 *       {@code #detectLineEndings}, {@code #convertLeadingTabsToSpaces},
 *       {@code #addLineNumbers}, and {@code #stripLineNumberPrefix}.</li>
 * </ul>
 */
public final class FileTextUtils {
    public enum LineEnding { CRLF, LF }
    public record TextFile(String content, Charset charset, LineEnding lineEnding) {}
    private static final Pattern LINE_PREFIX = Pattern.compile("^\\s*\\d+[→\\t](.*)$");

    private FileTextUtils() {}

    public static Charset detectEncoding(Path path) throws IOException {
        byte[] header = readHead(path, 3);
        if (header.length >= 2 && header[0] == (byte) 0xff && header[1] == (byte) 0xfe) {
            return StandardCharsets.UTF_16LE;
        }
        if (header.length >= 2 && header[0] == (byte) 0xfe && header[1] == (byte) 0xff) {
            return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    public static LineEnding detectLineEndings(String content) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                if (i > 0 && content.charAt(i - 1) == '\r') crlf++; else lf++;
            }
        }
        return crlf > lf ? LineEnding.CRLF : LineEnding.LF;
    }

    public static TextFile readWithMetadata(Path path) throws IOException {
        Charset charset = detectEncoding(path);
        String raw = Files.readString(path, charset);
        LineEnding ending = detectLineEndings(raw.substring(0, Math.min(raw.length(), 4096)));
        return new TextFile(raw.replace("\r\n", "\n"), charset, ending);
    }

    public static String restoreLineEndings(String content, LineEnding ending) {
        String lf = content.replace("\r\n", "\n");
        return ending == LineEnding.CRLF ? lf.replace("\n", "\r\n") : lf;
    }

    public static String convertLeadingTabsToSpaces(String content) {
        if (content.indexOf('\t') < 0) return content;
        String[] lines = content.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int tabs = 0;
            while (tabs < lines[i].length() && lines[i].charAt(tabs) == '\t') tabs++;
            if (tabs > 0) lines[i] = "  ".repeat(tabs) + lines[i].substring(tabs);
        }
        return String.join("\n", lines);
    }

    public static String addLineNumbers(String content, int startLine) {
        if (content.isEmpty()) return "";
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(content.length() + lines.length * 4);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(startLine + i).append('\t').append(lines[i]);
        }
        return out.toString();
    }

    public static String stripLineNumberPrefixes(String text) {
        String[] lines = text.split("\\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            var matcher = LINE_PREFIX.matcher(lines[i]);
            out.append(matcher.matches() ? matcher.group(1) : lines[i]);
        }
        return out.toString();
    }

    private static byte[] readHead(Path path, int size) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(size);
            channel.read(buffer);
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }
}
