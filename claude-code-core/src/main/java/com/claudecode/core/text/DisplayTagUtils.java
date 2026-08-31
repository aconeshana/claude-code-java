package com.claudecode.core.text;

import java.util.regex.Pattern;

/**
 * Removes system-injected XML-like blocks before text is shown as a session title.
 *
 * <ul>
 *   <li>remove lowercase
 *       paired tag blocks, while retaining the original text if nothing remains.</li>
 *   <li>the same
 *       removal without the fallback.</li>
 *   <li>remove only
 *       {@code ide_opened_file} and {@code ide_selection} context blocks.</li>
 * </ul>
 */
public final class DisplayTagUtils {

    private static final Pattern XML_TAG_BLOCK = Pattern.compile(
        "<([a-z][\\w-]*)(?:\\s[^>]*)?>[\\s\\S]*?</\\1>\\n?");
    private static final Pattern IDE_CONTEXT_TAGS = Pattern.compile(
        "<(ide_opened_file|ide_selection)(?:\\s[^>]*)?>[\\s\\S]*?</\\1>\\n?");

    private DisplayTagUtils() {
    }

    /** Removes display-only blocks, returning the original text if every character was removed. */
    public static String stripDisplayTags(String text) {
        String result = stripDisplayTagsAllowEmpty(text);
        return result.isEmpty() ? text : result;
    }

    /** Removes display-only blocks and permits an empty result. */
    public static String stripDisplayTagsAllowEmpty(String text) {
        return XML_TAG_BLOCK.matcher(text).replaceAll("").trim();
    }

    /** Removes only IDE context blocks, retaining user-authored lowercase markup. */
    public static String stripIdeContextTags(String text) {
        return IDE_CONTEXT_TAGS.matcher(text).replaceAll("").trim();
    }
}
