package com.claudecode.core.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorMessagesTest {

    @Test
    void classify_mapsEachTooLargeErrorToCorrectKind() {
        assertEquals(ApiErrorMessages.TooLargeKind.PDF_TOO_LARGE,
            ApiErrorMessages.classify("Error: maximum of 100 PDF pages exceeded"));
        assertEquals(ApiErrorMessages.TooLargeKind.PDF_PASSWORD_PROTECTED,
            ApiErrorMessages.classify("The PDF specified is password protected foo"));
        assertEquals(ApiErrorMessages.TooLargeKind.PDF_INVALID,
            ApiErrorMessages.classify("The PDF specified was not valid"));

        assertEquals(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE,
            ApiErrorMessages.classify("image exceeds the maximum allowed size of 5 MB"));
        assertEquals(ApiErrorMessages.TooLargeKind.REQUEST_TOO_LARGE,
            ApiErrorMessages.classify("{\"error\":{\"type\":\"request_too_large\"}}"));
        assertEquals(ApiErrorMessages.TooLargeKind.REQUEST_TOO_LARGE,
            ApiErrorMessages.classify("HTTP 413 Request Entity Too Large"));
    }

    @Test
    void classify_promptTooLong_isItsOwnKind_notRequestTooLarge() {
        // Regression: "prompt is too long" must NOT be routed to REQUEST_TOO_LARGE

        // PROMPT_TOO_LONG_ERROR_MESSAGE and strips nothing.
        assertEquals(ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG,
            ApiErrorMessages.classify("Prompt is too long"));
        assertEquals(ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG,
            ApiErrorMessages.classify("Vertex: Prompt Is Too Long (token gap 123)"));
    }

    @Test
    void classify_genericGatewayContextLengthOverflow_isPromptTooLong() {


        // through the "anthropic" protocol adapter; those reject overflow with
        // "...exceeds the model's maximum context length of N tokens..." instead.
        // Without recognizing this wording too, the existing PTL retry / reactive
        // -compact safety net never engages for such gateways and the turn just dies.
        assertEquals(ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG,
            ApiErrorMessages.classify("Requested token count exceeds the model's maximum "
                + "context length of 131072 tokens. You requested a total of 133927 tokens: "
                + "101927 tokens from the input messages and 32000 tokens for the completion. "
                + "Please reduce the number of tokens in the input messages or the completion "
                + "to fit within the limit."));
        assertEquals(ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG,
            ApiErrorMessages.classify("This model's maximum context length is 8192 tokens. "
                + "However, your messages resulted in 9000 tokens."));
    }

    @Test
    void classify_doesNotOverMatchGenericImageErrors() {
        // The broad "image" + "too large" substring is intentionally NOT used (B fix):
        // a generic message must not be classified as image-too-large.
        assertNull(ApiErrorMessages.classify("image is too large, please shrink"));
        assertNull(ApiErrorMessages.classify("something completely unrelated"));
        assertNull(ApiErrorMessages.classify(null));
    }

    @Test
    void errorToBlockTypes_keysMatchBothPhrasings() {
        // Every too-large message (interactive and non-interactive phrasing) must be a
        // key in errorToBlockTypes so the strip lookup is robust to session mode. And
        // PROMPT_TOO_LONG must NOT be a key (it strips nothing).
        for (ApiErrorMessages.TooLargeKind kind : ApiErrorMessages.TooLargeKind.values()) {
            String interactive = ApiErrorMessages.tooLargeMessage(kind, false);
            String nonInteractive = ApiErrorMessages.tooLargeMessage(kind, true);
            if (kind == ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG) {
                assertFalse(ApiErrorMessages.errorToBlockTypes().containsKey(interactive),
                    "PROMPT_TOO_LONG must not strip anything");
                assertFalse(ApiErrorMessages.errorToBlockTypes().containsKey(nonInteractive));
            } else {
                assertTrue(ApiErrorMessages.errorToBlockTypes().containsKey(interactive),
                    "missing key for interactive phrasing of " + kind);
                assertTrue(ApiErrorMessages.errorToBlockTypes().containsKey(nonInteractive),
                    "missing key for non-interactive phrasing of " + kind);
            }
        }
    }

    @Test
    void tooLargeMessage_phrasingsDifferAndAreStable() {
        String interactive = ApiErrorMessages.tooLargeMessage(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE, false);
        String nonInteractive = ApiErrorMessages.tooLargeMessage(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE, true);
        assertNotEquals(interactive, nonInteractive);
        assertEquals("Prompt is too long",
            ApiErrorMessages.tooLargeMessage(ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG, false));
        assertEquals("Prompt is too long",
            ApiErrorMessages.tooLargeMessage(ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG, true));
    }
}
