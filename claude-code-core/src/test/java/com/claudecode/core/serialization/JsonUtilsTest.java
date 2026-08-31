package com.claudecode.core.serialization;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonUtilsTest {

    @TempDir
    Path tmp;

    @Test
    void toPrettyJsonProducesIndentedOutput() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", 1);
        value.put("b", "x");

        String json = JsonUtils.toPrettyJson(value);

        assertTrue(Strings.CS.contains(json, "\n"));
        assertTrue(Strings.CS.contains(json, "\"a\" : 1"));
    }

    @Test
    void readJsonDeserializesTypedValue() throws IOException {
        Path file = tmp.resolve("typed.json");
        Files.writeString(file, "{\"name\":\"demo\",\"count\":3}");

        Sample value = JsonUtils.readJson(file, Sample.class);

        assertEquals("demo", value.name());
        assertEquals(3, value.count());
    }

    @Test
    void writeJsonSupportsArbitraryValuesAndPrettyFlag() throws IOException {
        Path file = tmp.resolve("nested/value.json");

        JsonUtils.writeJson(file, new Sample("demo", 3), true);

        JsonNode node = JsonUtils.readJson(file);
        assertEquals("demo", node.path("name").asText());
        assertTrue(Strings.CS.contains(Files.readString(file), "\n"));
    }

    @Test
    void typedReadRejectsMalformedJson() throws IOException {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{");

        assertThrows(IOException.class, () -> JsonUtils.readJson(file, Sample.class));
    }

    @Test
    void parseJsonLinesStripsBomAndSkipsBlankOrMalformedLines() {
        List<JsonNode> values = JsonUtils.parseJsonLines(
                "\uFEFF{\"id\":1}\n\n{bad\n{\"id\":2}\r\n");

        assertEquals(List.of(1, 2), values.stream().map(n -> n.path("id").asInt()).toList());
    }

    @Test
    void readJsonLinesReadsTailAndSkipsFirstPartialLine() throws IOException {
        Path file = tmp.resolve("session.jsonl");
        Files.writeString(file, "{\"id\":1}\n{\"id\":2}\n{\"id\":3}\n");

        List<JsonNode> values = JsonUtils.readJsonLines(file, 20);

        assertEquals(List.of(2, 3), values.stream().map(n -> n.path("id").asInt()).toList());
    }

    private record Sample(String name, int count) {}
}
