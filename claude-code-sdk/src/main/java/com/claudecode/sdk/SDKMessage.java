package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.Strings;

/**
 * Lossless public message yielded by an SDK query.
{@code SDKMessage} union.</li></ul>
 */
public record SDKMessage(JsonNode value) {
    public SDKMessage {
        value = value == null ? null : value.deepCopy();
    }
    public String type() { return value == null ? null : value.path("type").asText(null); }
    public String subtype() { return value == null ? null : value.path("subtype").asText(null); }
    public boolean isResult() { return Strings.CS.equals("result", type()); }
    @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
}
