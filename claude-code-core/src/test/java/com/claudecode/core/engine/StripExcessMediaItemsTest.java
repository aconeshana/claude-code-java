package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.claudecode.core.engine.StreamingClient.StreamRequest.RequestMessage;


class StripExcessMediaItemsTest {

    private static Map<String, Object> text(String t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", t);
        return m;
    }

    private static Map<String, Object> image() {
        return image(0);
    }

    /** An inlined image whose base64 payload is {@code dataChars} characters. */
    private static Map<String, Object> image(int dataChars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "image");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        if (dataChars > 0) source.put("data", "A".repeat(dataChars));
        m.put("source", source);
        return m;
    }

    /** A {@code url}-sourced image, which contributes no inlined bytes. */
    private static Map<String, Object> urlImage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "image");
        m.put("source", Map.of("type", "url", "url", "https://example.com/a.png"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolResult(List<Object> content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "tool_result");
        m.put("content", content);
        return m;
    }

    private static RequestMessage user(List<Map<String, Object>> content) {
        return new RequestMessage("user", content);
    }

    @SuppressWarnings("unchecked")
    private static int countMedia(RequestMessage msg) {
        Object content = msg.content();
        if (!(content instanceof List<?> list)) return 0;
        int n = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> block)) continue;
            String type = (String) block.get("type");
            if (Strings.CS.equals("image", type) || Strings.CS.equals("document", type)) {
                n++;
            } else if (Strings.CS.equals("tool_result", type) && block.get("content") instanceof List<?> inner) {
                for (Object nested : inner) {
                    if (nested instanceof Map<?, ?> nb) {
                        String nt = (String) nb.get("type");
                        if (Strings.CS.equals("image", nt) || Strings.CS.equals("document", nt)) n++;
                    }
                }
            }
        }
        return n;
    }

    @Test
    void exactlyAtLimitIsNoOpAndReturnsSameReference() {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int i = 0; i < RequestMessageNormalizer.API_MAX_MEDIA_PER_REQUEST; i++) {
            blocks.add(image());
        }
        List<RequestMessage> messages = List.of(user(blocks));
        List<RequestMessage> result =
            RequestMessageNormalizer.stripExcessMediaItems(messages,
                RequestMessageNormalizer.API_MAX_MEDIA_PER_REQUEST);
        assertSame(messages, result, "at the limit the input list must be returned untouched");
        assertEquals(RequestMessageNormalizer.API_MAX_MEDIA_PER_REQUEST, countMedia(result.getFirst()));
    }

    @Test
    void oneOverLimitDropsTheOldestMediaItem() {
        List<Map<String, Object>> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) first.add(image());
        RequestMessage oldest = user(first);
        RequestMessage newest = user(List.of(image()));
        List<RequestMessage> messages = List.of(oldest, newest);

        List<RequestMessage> result =
            RequestMessageNormalizer.stripExcessMediaItems(messages, 100);

        // Oldest message loses exactly one (the very first) image; newest is untouched.
        assertEquals(99, countMedia(result.getFirst()));
        assertSame(newest, result.get(1), "untouched message keeps its reference");
        assertEquals(1, countMedia(result.get(1)));
    }

    @Test
    void nestedToolResultMediaIsCountedAndStrippedBeforeTopLevel() {
        // msg1: text + tool_result([image, image])  -> 2 nested media
        // msg2: image + image + image                -> 3 top-level media
        // total 5, limit 3 -> drop 2, both from the nested tool_result first.
        RequestMessage msg1 = user(List.of(
            text("ctx"),
            toolResult(new ArrayList<>(List.of(image(), image())))));
        RequestMessage msg2 = user(List.of(image(), image(), image()));
        List<RequestMessage> messages = List.of(msg1, msg2);

        List<RequestMessage> result =
            RequestMessageNormalizer.stripExcessMediaItems(messages, 3);

        @SuppressWarnings("unchecked")
        Map<String, Object> trBlock = (Map<String, Object>) ((List<?>) result.getFirst().content()).get(1);
        @SuppressWarnings("unchecked")
        List<Object> trContent = (List<Object>) trBlock.get("content");
        assertEquals(0, trContent.size(), "both nested images stripped first");
        assertSame(msg2, result.get(1), "msg2 (only top-level media) untouched once limit met");
        assertEquals(3, countMedia(result.get(1)));
        assertEquals(3, countMedia(result.getFirst()) + countMedia(result.get(1)));
    }

    @Test
    void oldestTopLevelMediaDroppedWhenMessageHasNoToolResult() {
        // msg1: 2 top-level images (no tool_result)
        // msg2: text + tool_result([image, image, image]) -> 3 nested
        // total 5, limit 3 -> drop 2 from msg1's top-level first.
        RequestMessage msg1 = user(List.of(image(), image()));
        RequestMessage msg2 = user(List.of(
            text("ctx"),
            toolResult(new ArrayList<>(List.of(image(), image(), image())))));
        List<RequestMessage> messages = List.of(msg1, msg2);

        List<RequestMessage> result =
            RequestMessageNormalizer.stripExcessMediaItems(messages, 3);

        assertEquals(0, countMedia(result.getFirst()), "msg1's 2 top-level images dropped");
        assertSame(msg2, result.get(1), "msg2 untouched (nested preserved)");
        assertEquals(3, countMedia(result.get(1)));
    }

    @Test
    void textOnlyMessagesAreUntouched() {
        RequestMessage msg = user(List.of(text("a"), text("b")));
        List<RequestMessage> messages = List.of(msg);
        List<RequestMessage> result =
            RequestMessageNormalizer.stripExcessMediaItems(messages, 100);
        assertSame(messages, result);
        assertInstanceOf(List.class, result.getFirst().content());
    }

    @Test
    void bytesBelowTheCapAreNoOpAndReturnTheSameReference() {
        List<RequestMessage> messages = List.of(user(List.of(image(1000), image(1000))));
        List<RequestMessage> result = RequestMessageNormalizer.stripExcessMediaItems(
            messages, 100, 0, 5000L, 0L);
        assertSame(messages, result, "under the byte cap nothing may be rewritten");
    }

    @Test
    void oldestMediaIsEvictedOnceTheByteCapIsExceeded() {
        // Three 1000-char images against a 2500-byte cap: the oldest one alone
        // crosses the ceiling, so exactly one block goes.
        RequestMessage oldest = user(List.of(image(1000)));
        RequestMessage middle = user(List.of(image(1000)));
        RequestMessage newest = user(List.of(image(1000)));
        List<RequestMessage> messages = List.of(oldest, middle, newest);

        List<RequestMessage> result = RequestMessageNormalizer.stripExcessMediaItems(
            messages, 100, 0, 2500L, 0L);

        assertEquals(0, countMedia(result.getFirst()), "the oldest image is evicted");
        assertEquals(1, countMedia(result.get(1)));
        assertEquals(1, countMedia(result.get(2)));
        assertSame(middle, result.get(1), "untouched messages keep their reference");
    }

    @Test
    void exceedingTheByteCapTrimsToTheHysteresisBandNotJustUnderTheCap() {
        // 3000 bytes against a 2000-byte cap is 1000 over, but the 1000-byte recent
        // allowance widens the reclaim target to 2000 bytes, so eviction continues
        // past the cap — the band leaves headroom before the next trim.
        RequestMessage oldest = user(List.of(image(1000)));
        RequestMessage newest = user(List.of(image(2000)));
        List<RequestMessage> messages = List.of(oldest, newest);

        List<RequestMessage> result = RequestMessageNormalizer.stripExcessMediaItems(
            messages, 100, 0, 2000L, 1000L);

        assertEquals(0, countMedia(result.getFirst()));
        assertEquals(0, countMedia(result.get(1)),
            "1000 bytes of excess plus the 1000-byte band reclaims both blocks");
    }

    @Test
    void exceedingTheCountLimitTrimsToTheHysteresisBand() {
        RequestMessage oldest = user(List.of(image()));
        RequestMessage middle = user(List.of(image()));
        RequestMessage newest = user(List.of(image()));
        List<RequestMessage> messages = List.of(oldest, middle, newest);

        // 3 blocks against a limit of 2 is 1 over; the 1-block band makes it 2.
        List<RequestMessage> result = RequestMessageNormalizer.stripExcessMediaItems(
            messages, 2, 1, 0L, 0L);

        assertEquals(0, countMedia(result.getFirst()));
        assertEquals(0, countMedia(result.get(1)));
        assertEquals(1, countMedia(result.get(2)), "eviction always proceeds oldest-first");
    }

    @Test
    void aZeroHysteresisBandEvictsOnlyTheExcess() {
        RequestMessage oldest = user(List.of(image()));
        RequestMessage newest = user(List.of(image()));
        List<RequestMessage> messages = List.of(oldest, newest);

        List<RequestMessage> result = RequestMessageNormalizer.stripExcessMediaItems(
            messages, 1, 0, 0L, 0L);

        assertEquals(0, countMedia(result.getFirst()), "1 over the limit evicts exactly the oldest");
        assertEquals(1, countMedia(result.get(1)));
        assertSame(newest, result.get(1));
    }

    @Test
    void urlSourcedMediaContributesNoInlinedBytes() {
        List<RequestMessage> messages = List.of(user(List.of(urlImage())));
        assertSame(messages, RequestMessageNormalizer.stripExcessMediaItems(
            messages, 100, 0, 1L, 0L),
            "a url source carries no inline payload to measure");
    }

    @Test
    void aMessageEmptiedByEvictionKeepsAPlaceholderBlock() {
        // A media-only turn whose content is entirely evicted must still carry a
        // block: an empty content array is not a valid wire turn.
        RequestMessage only = user(List.of(image(1000)));
        List<RequestMessage> messages = List.of(only);

        List<RequestMessage> result = RequestMessageNormalizer.stripExcessMediaItems(
            messages, 100, 0, 999L, 0L);

        List<?> content = (List<?>) result.getFirst().content();
        assertEquals(1, content.size(), "the emptied turn must not go out with no content");
        Map<?, ?> block = (Map<?, ?>) content.getFirst();
        assertEquals("text", block.get("type"));
        assertEquals("[media removed: request limit]", block.get("text"));
    }
}
