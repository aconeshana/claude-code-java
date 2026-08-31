package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SDKControlEnvelopeTest {
    @Test
    void requestRoundTripsThePublishedWireEnvelope() throws Exception {
        JsonNode wire = JsonUtils.getMapper().readTree("""
            {
              "type": "control_request",
              "request_id": "request-1",
              "request": {
                "subtype": "can_use_tool",
                "tool_name": "Bash",
                "input": {"command": "pwd"}
              }
            }
            """);

        SDKControlRequest request = SDKControlRequest.fromJson(wire);

        assertEquals("request-1", request.requestId());
        assertEquals("can_use_tool", request.request().path("subtype").asText());
        assertEquals(wire, request.toJson());
    }

    @Test
    void responseRoundTripsSuccessAndErrorVariants() throws Exception {
        JsonNode successWire = JsonUtils.getMapper().readTree("""
            {
              "type": "control_response",
              "response": {
                "subtype": "success",
                "request_id": "request-1",
                "response": {"behavior": "allow"}
              }
            }
            """);
        SDKControlResponse success = SDKControlResponse.fromJson(successWire);

        assertTrue(success.success());
        assertNull(success.error());
        assertTrue(success.pendingPermissionRequests().isEmpty());
        assertEquals(successWire, success.toJson());

        JsonNode errorWire = JsonUtils.getMapper().readTree("""
            {
              "type": "control_response",
              "response": {
                "subtype": "error",
                "request_id": "request-2",
                "error": "denied",
                "pending_permission_requests": [{
                  "type": "control_request",
                  "request_id": "pending-1",
                  "request": {"subtype": "can_use_tool"}
                }]
              }
            }
            """);
        SDKControlResponse error = SDKControlResponse.fromJson(errorWire);

        assertFalse(error.success());
        assertNull(error.response());
        assertEquals("denied", error.error());
        assertEquals(List.of("pending-1"), error.pendingPermissionRequests().stream()
            .map(SDKControlRequest::requestId).toList());
        assertEquals(errorWire, error.toJson());
    }
}
