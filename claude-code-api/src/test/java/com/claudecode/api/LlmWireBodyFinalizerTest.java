package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Contract that finalization cannot alter protocol-specific body structure. */
class LlmWireBodyFinalizerTest {

    @Test
    void changesOnlyTheTopLevelProviderModel() throws Exception {
        ObjectNode body = (ObjectNode) JsonUtils.getMapper().readTree("""
            {
              "model":"gpt-5.6-sol[1m]",
              "stream":true,
              "messages":[{"role":"user","content":"hello"}],
              "reasoning":{"effort":"high"},
              "metadata":{"model":"internal[1m]"}
            }
            """);
        ObjectNode expected = body.deepCopy();
        expected.put("model", "gpt-5.6-sol");

        ObjectNode finalized = LlmWireBodyFinalizer.finalizeForApi(body);

        assertSame(body, finalized);
        assertEquals(expected, finalized);
    }

    @Test
    void leavesBodiesWithoutATextModelUntouched() throws Exception {
        ObjectNode missing = (ObjectNode) JsonUtils.getMapper().readTree(
            "{\"input\":[],\"stream\":false}");
        ObjectNode numeric = (ObjectNode) JsonUtils.getMapper().readTree(
            "{\"model\":5,\"input\":[]}");
        ObjectNode missingBefore = missing.deepCopy();
        ObjectNode numericBefore = numeric.deepCopy();

        LlmWireBodyFinalizer.finalizeForApi(missing);
        LlmWireBodyFinalizer.finalizeForApi(numeric);

        assertEquals(missingBefore, missing);
        assertEquals(numericBefore, numeric);
    }
}
