package com.claudecode.ui.lanterna.transcript;


import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

public final class ShellOutputFormatter {

    private static final int MAX_JSON_FORMAT_LENGTH = 10_000;

// Match http(s) URLs — conservative: no whitespace, no quotes, no XML/JSON structural
// characters.
    private static final Pattern URL_PATTERN =
        Pattern.compile("https?://[^\\s\"'<>\\\\]+");

    // OSC 8 hyperlink escape sequences: ESC ] 8 ; ; <url> ST <text> ESC ] 8 ; ; ST
    private static final String OSC8_START = "]8;;";
    private static final String OSC8_END   = "\\";

    private ShellOutputFormatter() {}

    /**
     * Best-effort pretty-print a line if it parses as JSON without integer precision loss.
     */
    public static String tryFormatJson(String line) {
        if (StringUtils.isBlank(line)) return line;
        String trimmed = line.stripLeading();
        // Cheap guard: must plausibly start a JSON value.
        char c = trimmed.charAt(0);
        if (c != '{' && c != '[' && c != '"'
                && !Character.isDigit(c) && c != '-'
                && c != 't' && c != 'f' && c != 'n') return line;
        try {
            ObjectMapper mapper = JsonUtils.getMapper();
            JsonNode parsed = mapper.readTree(line);
            String compact = mapper.writeValueAsString(parsed);

            // Precision-loss guard: normalise whitespace and optional escapes,
            // then compare. the compatibility contract: replace(/\\\//g,'/') + replace(/\s+/g,'').
            String normalizedOriginal = line.replace("\\/", "/").replaceAll("\\s+", "");
            String normalizedCompact = compact.replaceAll("\\s+", "");
            if (!normalizedOriginal.equals(normalizedCompact)) {
                return line;
            }
            return JsonUtils.toPrettyJson(parsed);
        } catch (JsonProcessingException _) {
            return line;
        }
    }

    /**
     * Apply {@link #tryFormatJson} to each newline-separated line of {@code content}.
     * Skips content longer than 10 000 characters (the compatibility contract {@code MAX_JSON_FORMAT_LENGTH}).
     */
    public static String tryJsonFormatContent(String content) {
        if (content == null || content.length() > MAX_JSON_FORMAT_LENGTH) return content;
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(tryFormatJson(lines[i]));
        }
        return out.toString();
    }

    /**
     * Wrap URLs in {@code content} with OSC 8 terminal hyperlink escape sequences.
     */
    public static String linkifyUrls(String content) {
        if (StringUtils.isEmpty(content)) return content;
        Matcher m = URL_PATTERN.matcher(content);
        if (!m.find()) return content;
        m.reset();
        StringBuilder out = new StringBuilder(content.length() + 32);
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            String url = m.group();
            out.append(OSC8_START).append(url).append(OSC8_END)
               .append(url)
               .append(OSC8_START).append(OSC8_END);
            last = m.end();
        }
        out.append(content, last, content.length());
        return out.toString();
    }

    /**
     * Convenience: apply {@link #tryJsonFormatContent} then {@link #linkifyUrls}.
     */
    public static String format(String content) {
        return linkifyUrls(tryJsonFormatContent(content));
    }
}
