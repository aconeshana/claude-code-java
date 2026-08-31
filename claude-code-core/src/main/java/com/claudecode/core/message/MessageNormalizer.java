package com.claudecode.core.message;

import java.util.List;
import java.util.StringJoiner;

/**
 * Stateless utility for extracting text content from messages.
 */
public final class MessageNormalizer {

    private MessageNormalizer() {}


    public static String extractTextContent(List<? extends ContentBlock> blocks, String separator) {
        if (blocks == null) return "";
        StringJoiner sj = new StringJoiner(separator);
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb && tb.text() != null) sj.add(tb.text());
        }
        return sj.toString();
    }


    public static String getContentText(MessageContent mc) {
        if (mc == null) return null;
        if (mc.text() != null) return mc.text();
        if (mc.blocks() != null) {
            String text = extractTextContent(mc.blocks(), "\n").trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }
}
