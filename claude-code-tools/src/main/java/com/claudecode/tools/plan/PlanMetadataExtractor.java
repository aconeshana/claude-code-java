package com.claudecode.tools.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Deterministically derives compact catalog metadata from a Markdown plan. */
final class PlanMetadataExtractor {

    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final int MAX_TITLE_CODE_POINTS = 80;
    private static final int MAX_SUMMARY_CODE_POINTS = 200;

    private PlanMetadataExtractor() {}

    static Metadata extract(String content, String planId) {
        String source = content == null ? "" : content;
        Matcher titleMatcher = H1.matcher(source);
        String title = titleMatcher.find()
            ? normalize(titleMatcher.group(1))
            : firstBodyParagraph(source);
        if (StringUtils.isBlank(title)) title = "Plan " + planId;

        String summary = contextParagraph(source);
        if (StringUtils.isBlank(summary)) summary = firstBodyParagraph(source);
        if (Strings.CS.equals(title, summary)) summary = "";
        return new Metadata(
            truncate(title, MAX_TITLE_CODE_POINTS),
            truncate(summary, MAX_SUMMARY_CODE_POINTS));
    }

    private static String contextParagraph(String content) {
        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!isContextHeading(line)) continue;
            return paragraphAfter(lines, i + 1);
        }
        return "";
    }

    private static boolean isContextHeading(String line) {
        if (!HEADING.matcher(line).matches()) return false;
        String text = line.replaceFirst("^#{1,6}\\s+", "").strip();
        return Strings.CI.equals("context", text);
    }

    private static String firstBodyParagraph(String content) {
        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || HEADING.matcher(line).matches()) continue;
            return paragraphAfter(lines, i);
        }
        return "";
    }

    private static String paragraphAfter(List<String> lines, int start) {
        List<String> paragraph = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                if (!paragraph.isEmpty()) break;
                continue;
            }
            if (HEADING.matcher(line).matches()) {
                break;
            }
            paragraph.add(line);
        }
        return normalize(String.join(" ", paragraph));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));
        return value.substring(0, end).stripTrailing() + "…";
    }

    record Metadata(String title, String summary) {}
}
