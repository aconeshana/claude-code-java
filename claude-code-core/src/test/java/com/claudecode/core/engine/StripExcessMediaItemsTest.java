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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "image");
        m.put("source", Map.of("type", "base64"));
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
}
