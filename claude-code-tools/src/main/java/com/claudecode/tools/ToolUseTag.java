package com.claudecode.tools;

import org.apache.commons.lang3.StringUtils;

/**
 * Semantic tool-use tag rendered beside a tool header.
 */
public record ToolUseTag(String text, Tone tone, String hyperlink) {

    public enum Tone { DEFAULT, DIM, SUBTLE }

    public ToolUseTag {
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException("Tool-use tag text must not be blank");
        }
        tone = tone != null ? tone : Tone.DEFAULT;
    }

    public static ToolUseTag dim(String text) {
        return new ToolUseTag(text, Tone.DIM, null);
    }
}
