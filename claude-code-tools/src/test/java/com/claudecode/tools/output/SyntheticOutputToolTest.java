package com.claudecode.tools.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;


class SyntheticOutputToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode readSchema(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void description_matchesTsWireDescription() throws Exception {

        assertEquals("Return structured output in the requested format",
            new SyntheticOutputTool(readSchema("{\"type\":\"object\"}")).description());
    }

    @Test
    void maxResultSizeChars_matchesTs() throws Exception {

        assertEquals(100_000,
            new SyntheticOutputTool(readSchema("{\"type\":\"object\"}")).maxResultSizeChars());
    }

    @Test
    void create_rejectsUncompilablePattern() throws Exception {
        JsonNode bad = readSchema(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"(\"}}}");
        SyntheticOutputTool.CreateResult res = SyntheticOutputTool.create(bad);
        assertInstanceOf(SyntheticOutputTool.CreateResult.Err.class, res, "uncompilable pattern must produce Err, got: " + res);
    }

    @Test
    void create_acceptsValidPattern() throws Exception {
        JsonNode ok = readSchema(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"^a.*z$\"}}}");
        SyntheticOutputTool.CreateResult res = SyntheticOutputTool.create(ok);
        assertInstanceOf(SyntheticOutputTool.CreateResult.Ok.class, res, "valid pattern must produce Ok, got: " + res);
    }
}
