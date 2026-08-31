package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A file that was read before compaction and is re-attached with its full content.
 */
public record FileContentAttachment(
    @JsonProperty("filename") String filename,
    @JsonProperty("content") String content
) implements AttachmentPayload {

    public FileContentAttachment {
    }

/**
     * Accepts both Java's model-facing text shape and.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FileContentAttachment fromJson(JsonNode node) {
        String filename = node.path("filename").asText();
        JsonNode contentNode = node.path("content");
        if (contentNode.isTextual()) {
            return new FileContentAttachment(filename, contentNode.asText());
        }
        String raw = contentNode.path("file").path("content").asText();
        return new FileContentAttachment(filename, raw);
    }
}
