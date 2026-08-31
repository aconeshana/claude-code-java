package com.claudecode.core.paste;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;












public final class PastedRefParser {

    private PastedRefParser() {}

    /** {@code [Pasted text #N]} or {@code [Pasted text #N +M lines]}. */
    public static String formatPastedTextRef(int id, int numLines) {
        if (numLines == 0) return "[Pasted text #" + id + "]";
        return "[Pasted text #" + id + " +" + numLines + " lines]";
    }

    /** {@code [Image #N]}. */
    public static String formatImageRef(int id) {
        return "[Image #" + id + "]";
    }


    public static int getPastedTextRefNumLines(String text) {
        if (StringUtils.isEmpty(text)) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') count++;
        }

        // so \r\n counts once. We over-counted by 1 for each CRLF; subtract.
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '\r' && text.charAt(i + 1) == '\n') count--;
        }
        return count;
    }

    /** A parsed reference with id, raw match text, and byte offset in the input. */
    public record Ref(int id, String match, int index) {}

    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
        "\\[(Pasted text|Image|\\.\\.\\.Truncated text) #(\\d+)(?: \\+\\d+ lines)?(\\.)*\\]");

    /**
     * Find all {@code [Pasted text #N]}, {@code [Image #N]}, and
     * {@code [...Truncated text #N]} references in the input.
     * Filters out id=0 (invalid).
     */
    public static List<Ref> parseReferences(String input) {
        List<Ref> out = new ArrayList<>();
        if (StringUtils.isEmpty(input)) return out;
        Matcher m = REFERENCE_PATTERN.matcher(input);
        while (m.find()) {
            int id = Integer.parseInt(m.group(2));
            if (id > 0) {
                out.add(new Ref(id, m.group(0), m.start()));
            }
        }
        return out;
    }

    /**
     * Replace {@code [Pasted text #N]} placeholders with their actual content.
     */
    public static String expandPastedTextRefs(String input, Map<Integer, ? extends PastedContentLike> pastedContents) {
        if (StringUtils.isEmpty(input)) return input;
        List<Ref> refs = parseReferences(input);
        if (refs.isEmpty()) return input;

        StringBuilder sb = new StringBuilder(input);
        // Reverse order so earlier offsets stay valid after later replacements.
        for (int i = refs.size() - 1; i >= 0; i--) {
            Ref ref = refs.get(i);
            PastedContentLike c = pastedContents == null ? null : pastedContents.get(ref.id);
            if (c == null || !Strings.CS.equals("text", c.type())) continue;
            sb.replace(ref.index(), ref.index() + ref.match().length(), c.content());
        }
        return sb.toString();
    }







    public static boolean isValidImagePaste(PastedContentLike c) {
        return c != null && Strings.CS.equals("image", c.type()) && c.content() != null && !c.content().isEmpty();
    }

    /** Structural interface so this util doesn't depend on core's PastedContent. */
    public interface PastedContentLike {
        int    id();
        String type();
        String content();
    }
}
