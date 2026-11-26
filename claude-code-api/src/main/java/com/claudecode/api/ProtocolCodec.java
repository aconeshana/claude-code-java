package com.claudecode.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/**
 * Per-protocol wire codec abstraction backing {@link ProtocolWireCodec}.
 *
 * <p>Each supported {@link com.claudecode.core.model.ModelApiProtocol} provides one stateless
 * implementation so adding a new wire protocol does not require modifying existing codecs.
 */
interface ProtocolCodec {

    CreateMessageRequest parseRequest(ObjectNode root);

    ObjectNode encodeRequest(CreateMessageRequest request, String targetBaseUrl, boolean forClientRelay);

    ApiMessage parseResponse(String jsonBody, Set<String> offeredTools) throws Exception;

    ObjectNode encodeResponse(ApiMessage message);

    EventSourceStreamBridge.EventTranslator newSseTranslator(
            String fallbackModel, Set<String> offeredTools);

    String encodeSse(List<StreamEvent> events, String fallbackModel);

    ProtocolWireCodec.StreamProjector newStreamProjector(String fallbackModel);

    default String encodeCountTokensResponse(long inputTokens) {
        ObjectNode out = com.claudecode.core.serialization.JsonUtils.getMapper().createObjectNode();
        out.put("input_tokens", inputTokens);
        return out.toString();
    }
}
