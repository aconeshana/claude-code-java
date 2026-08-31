package com.claudecode.core.text;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consolidated XML tag utilities: extract, test, wrap, and strip XML-style tags in plain text.
 */
public final class XmlTagUtils {

    private static final Pattern XML_TAG_PATTERN = Pattern.compile(
            "<[^/>][^>]*>.*?</[^>]+>",
            Pattern.DOTALL
    );

    private static final Pattern SELF_CLOSING_XML_PATTERN = Pattern.compile("<[^>]+/>");

    private XmlTagUtils() {
    }

    // ── extract / contains ───────────────────────────────────────────────────

    /**
     * Extracts the content of the first occurrence of {@code <tagName>} at depth 0 in {@code text}.
     */
    public static Optional<String> extractTag(String text, String tagName) {
        if (org.apache.commons.lang3.StringUtils.isBlank(text)) {
            return Optional.empty();
        }
        if (org.apache.commons.lang3.StringUtils.isBlank(tagName)) {
            return Optional.empty();
        }

        String escapedTag = StringUtils.escapeRegExp(tagName);


        //   <tagName(?:\s+[^>]*)?>   — opening tag with optional attributes
        //   ([\s\S]*?)               — non-greedy content capture group
        //   </tagName>               — closing tag
        // Flags: CASE_INSENSITIVE | DOTALL (DOTALL makes . match newlines, but we use [\s\S]

        Pattern mainPattern = Pattern.compile(
                "<" + escapedTag + "(?:\\s+[^>]*)?>([\\s\\S]*?)</" + escapedTag + ">",
                Pattern.CASE_INSENSITIVE
        );

        Pattern openingPattern = Pattern.compile(
                "<" + escapedTag + "(?:\\s+[^>]*?)?>",
                Pattern.CASE_INSENSITIVE
        );
        Pattern closingPattern = Pattern.compile(
                "</" + escapedTag + ">",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = mainPattern.matcher(text);
        int lastIndex = 0;

        while (matcher.find()) {
            String content = matcher.group(1);
            String beforeMatch = text.substring(lastIndex, matcher.start());

            // Count net depth of same-name tags that opened before this match

            int depth = 0;
            Matcher openMatcher = openingPattern.matcher(beforeMatch);
            while (openMatcher.find()) {
                depth++;
            }
            Matcher closeMatcher = closingPattern.matcher(beforeMatch);
            while (closeMatcher.find()) {
                depth--;
            }


            if (depth == 0 && content != null && !content.isEmpty()) {
                return Optional.of(content);
            }

            lastIndex = matcher.start() + matcher.group(0).length();
        }

        return Optional.empty();
    }

    /**
     * Returns {@code true} if {@code text} contains at least one depth-0 occurrence of {@code
     * <tagName>} with non-empty content.
     */
    public static boolean containsTag(String text, String tagName) {
        return extractTag(text, tagName).isPresent();
    }

    // ── wrap ─────────────────────────────────────────────────────────────────

    /**
     * Wraps {@code content} in a paired XML tag: {@code <tagName>content</tagName>}.
     */
    public static String wrap(String tagName, String content) {
        if (org.apache.commons.lang3.StringUtils.isBlank(tagName)) {
            throw new IllegalArgumentException("tagName must not be null or blank");
        }
        String safeContent = content == null ? "" : content;
        return "<" + tagName + ">" + safeContent + "</" + tagName + ">";
    }

    // ── strip ────────────────────────────────────────────────────────────────

    /**
     * Strips all XML/HTML tags (paired and self-closing) from {@code content}.
     *
     * <p>Covers the {@code stripAll} behaviour from PromptXmlStripper (Task 68.6):
     * removes {@code <tag>...</tag>} pairs (including multiline content) and
     * {@code <tag/>} self-closing tags. Content between stripped tags is also removed.
     *
     * <p>Returns the input unchanged if it is null or empty.
     *
     * @param content the content to strip (may be null)
     * @return content with all XML tags (and their inner text) removed, or the original
     *         value if null/empty
     */
    public static String stripAll(String content) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(content)) {
            return content;
        }
        String result = XML_TAG_PATTERN.matcher(content).replaceAll("");
        result = SELF_CLOSING_XML_PATTERN.matcher(result).replaceAll("");
        return result;
    }

}
