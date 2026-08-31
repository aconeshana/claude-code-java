package com.claudecode.core.message;

import com.claudecode.core.annotation.Explanation;
import org.apache.commons.lang3.Strings;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * API error message text + classification for the too-large / PDF content-size recovery path.
 */
public final class ApiErrorMessages {


    public enum TooLargeKind {
        PDF_TOO_LARGE,
        PDF_PASSWORD_PROTECTED,
        PDF_INVALID,
        IMAGE_TOO_LARGE,
        REQUEST_TOO_LARGE,
        PROMPT_TOO_LONG
    }

    private ApiErrorMessages() {}

    /** One of the five canonical error texts. {@code nonInteractive} selects the phrasing. */
    public static String tooLargeMessage(TooLargeKind kind, boolean nonInteractive) {
        return switch (kind) {
            case PDF_TOO_LARGE -> nonInteractive
                ? "PDF too large (max 100 pages, 20 MB). Try reading the file a different way "
                    + "(e.g., extract text with pdftotext)."
                : "PDF too large (max 100 pages, 20 MB). Double press esc to go back and try "
                    + "again, or use pdftotext to convert to text first.";
            case PDF_PASSWORD_PROTECTED -> nonInteractive
                ? "PDF is password protected. Try using a CLI tool to extract or convert the PDF."
                : "PDF is password protected. Please double press esc to edit your message and "
                    + "try again.";
            case PDF_INVALID -> nonInteractive
                ? "The PDF file was not valid. Try converting it to text first (e.g., pdftotext)."
                : "The PDF file was not valid. Double press esc to go back and try again with a "
                    + "different file.";
            case IMAGE_TOO_LARGE -> nonInteractive
                ? "Image was too large. Try resizing the image or using a different approach."
                : "Image was too large. Double press esc to go back and try again with a smaller "
                    + "image.";
            case REQUEST_TOO_LARGE -> nonInteractive
                ? "Request too large (max 20 MB). Try with a smaller file."
                : "Request too large (max 20 MB). Double press esc to go back and try again with "
                    + "a smaller file.";

// deliberately NOT registered in errorToBlockTypes so it strips nothing.
            case PROMPT_TOO_LONG -> "Prompt is too long";
        };
    }


    public static Map<String, Set<String>> errorToBlockTypes() {
        Map<String, Set<String>> map = new HashMap<>();
        register(map, TooLargeKind.PDF_TOO_LARGE, Set.of("document"));
        register(map, TooLargeKind.PDF_PASSWORD_PROTECTED, Set.of("document"));
        register(map, TooLargeKind.PDF_INVALID, Set.of("document"));
        register(map, TooLargeKind.IMAGE_TOO_LARGE, Set.of("image"));
        register(map, TooLargeKind.REQUEST_TOO_LARGE, Set.of("document", "image"));
        return map;
    }

    private static void register(Map<String, Set<String>> map, TooLargeKind kind, Set<String> types) {
        map.put(tooLargeMessage(kind, true), types);
        map.put(tooLargeMessage(kind, false), types);
    }


    public static TooLargeKind classify(String errorText) {
        if (errorText == null) return null;
        if (Strings.CS.contains(errorText, "The PDF specified is password protected")) {
            return TooLargeKind.PDF_PASSWORD_PROTECTED;
        }
        if (Strings.CS.contains(errorText, "The PDF specified was not valid")) {
            return TooLargeKind.PDF_INVALID;
        }
        if (errorText.matches(".*maximum of \\d+ PDF pages.*")) {
            return TooLargeKind.PDF_TOO_LARGE;
        }

        // from the API). A generic "image"/"too large" substring would over-match normal
        // errors, so only the precise phrase is used here.
        if (Strings.CS.contains(errorText, "image exceeds") && Strings.CS.contains(errorText, "maximum")) {
            return TooLargeKind.IMAGE_TOO_LARGE;
        }

        // strips nothing (handled by reactive compact). Kept as its own kind, NOT in
// errorToBlockTypes, so it does not accidentally strip image/document blocks.
        if (Strings.CI.contains(errorText, "prompt is too long")
                || isGenericContextLengthOverflow(errorText)) {
            return TooLargeKind.PROMPT_TOO_LONG;
        }
        if (Strings.CS.contains(errorText, "request_too_large")) {
            return TooLargeKind.REQUEST_TOO_LARGE;
        }
        if (Strings.CS.contains(errorText, "413")) {
            return TooLargeKind.REQUEST_TOO_LARGE;
        }
        return null;
    }


    @Explanation("Recognizes generic OpenAI/vLLM-style 'maximum context length' gateway wording "
        + "as PROMPT_TOO_LONG, for third-party models configured via model.json")
    private static boolean isGenericContextLengthOverflow(String errorText) {
        return Strings.CI.contains(errorText, "maximum context length");
    }
}
