package com.claudecode.core.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


class RequestMessageNormalizerToolReferenceTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolResultMap(StreamingClient.StreamRequest.RequestMessage rm) {
        return (Map<String, Object>) ((List<Map<String, Object>>) rm.content()).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> innerContent(Map<String, Object> trMap) {
        return (List<Map<String, Object>>) trMap.get("content");
    }

    private static Map<String, Object> refBlock(String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "tool_reference");
        b.put("tool_name", name);
        return b;
    }

    private static Map<String, Object> textBlock(String t) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "text");
        b.put("text", t);
        return b;
    }

    @Test
    void stripsTopLevelToolReference() {
        Map<String, Object> topRef = refBlock("X");
        StreamingClient.StreamRequest.RequestMessage rm = new StreamingClient.StreamRequest.RequestMessage(
            "user", List.of(topRef, textBlock("keep me")));
        var out = RequestMessageNormalizer.stripToolReferences(List.of(rm));

        assertEquals(1, out.size());
        List<Map<String, Object>> content = (List<Map<String, Object>>) out.getFirst().content();
        assertEquals(1, content.size());
        assertEquals("text", content.getFirst().get("type"));
        assertEquals("keep me", content.getFirst().get("text"));
    }

    @Test
    void stripsToolReferenceNestedInToolResult() {
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "abc");
        tr.put("content", List.of(textBlock("ok"), refBlock("X")));
        StreamingClient.StreamRequest.RequestMessage rm = new StreamingClient.StreamRequest.RequestMessage(
            "user", List.of(tr));
        var out = RequestMessageNormalizer.stripToolReferences(List.of(rm));

        Map<String, Object> trMap = toolResultMap(out.getFirst());
        List<Map<String, Object>> inner = innerContent(trMap);
        assertEquals(1, inner.size());
        assertEquals("text", inner.getFirst().get("type"));
    }

    @Test
    void emptyToolResult_isRefilledWithPlaceholder() {

        // is refilled with a placeholder text so the wire never carries an empty tool_result.
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "abc");
        tr.put("content", List.of(refBlock("X")));
        StreamingClient.StreamRequest.RequestMessage rm = new StreamingClient.StreamRequest.RequestMessage(
            "user", List.of(tr));
        var out = RequestMessageNormalizer.stripToolReferences(List.of(rm));

        Map<String, Object> trMap = toolResultMap(out.getFirst());
        List<Map<String, Object>> inner = innerContent(trMap);
        assertEquals(1, inner.size());
        assertEquals("text", inner.getFirst().get("type"));
        assertEquals("[Tool references removed - tool search not enabled]", inner.getFirst().get("text"));
    }

    @Test
    void stripsToolReferenceInsideToolResultKeepingText() {

        // are stripped even when the tool_result also holds real text content.
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", "abc");
        tr.put("content", List.of(textBlock("result text"), refBlock("X")));
        StreamingClient.StreamRequest.RequestMessage rm = new StreamingClient.StreamRequest.RequestMessage(
            "user", List.of(tr));
        var out = RequestMessageNormalizer.stripToolReferences(List.of(rm));

        Map<String, Object> trMap = toolResultMap(out.getFirst());
        List<Map<String, Object>> inner = innerContent(trMap);
        assertEquals(1, inner.size());
        assertEquals("text", inner.getFirst().get("type"));
        assertEquals("result text", inner.getFirst().get("text"));
    }

    @Test
    void plainUserMessage_unchanged() {
        StreamingClient.StreamRequest.RequestMessage rm = new StreamingClient.StreamRequest.RequestMessage(
            "user", List.of(textBlock("just text")));
        var out = RequestMessageNormalizer.stripToolReferences(List.of(rm));
        assertEquals(1, out.size());
        List<Map<String, Object>> content = (List<Map<String, Object>>) out.getFirst().content();
        assertEquals(1, content.size());
        assertEquals("just text", content.getFirst().get("text"));
    }
}
