package com.claudecode.api;

import com.claudecode.core.model.ModelNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Applies protocol-independent invariants after a provider adapter has fully
 * assembled its request body.
 *
 * <ul>
 *   <li>removes
 *       client-only context-window tags from the top-level provider model id.</li>
 * </ul>
 *
 * <p>This class intentionally knows nothing about Anthropic Messages, Chat
 * Completions, or Responses body shapes. Protocol adapters own every field;
 * this finalizer runs only after their construction and any last-wins body
 * overlays have completed.</p>
 */
final class LlmWireBodyFinalizer {

    private LlmWireBodyFinalizer() {}

    static ObjectNode finalizeForApi(ObjectNode body) {
        JsonNode model = body.get("model");
        if (model != null && model.isTextual()) {
            body.put("model", ModelNames.normalizeModelStringForApi(model.asText()));
        }
        return body;
    }
}
