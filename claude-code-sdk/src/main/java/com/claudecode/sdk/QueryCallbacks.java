package com.claudecode.sdk;

import com.claudecode.core.engine.AbortController;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * User callback contracts dispatched from SDK control requests.
permission, hook, dialog, and auth callbacks.</li></ul>
 */
public final class QueryCallbacks {
    private QueryCallbacks() {}

    @FunctionalInterface public interface JsonCallback {
        JsonNode call(JsonNode request, AbortController abortController) throws Exception;
    }
    @FunctionalInterface public interface CanUseTool {
        JsonNode call(String toolName, JsonNode input, AbortController abortController) throws Exception;
    }
}
