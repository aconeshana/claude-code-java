package com.claudecode.core.message;



/**
 * A file whose on-disk content changed after the model last read it (by the user or a linter),
 * surfaced so the model accounts for the edit without re-reading.
 */
public record EditedFileAttachment(
    String filename,
    String snippet
) implements AttachmentPayload {
}
