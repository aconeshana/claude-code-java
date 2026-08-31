package com.claudecode.core.message;

import com.claudecode.core.imagestore.ImageResizer;

/**
 * An image discovered through an {@code @file} mention.
 */
public record ImageFileAttachment(
    String filename,
    String displayPath,
    String base64,
    String mediaType,
    long originalSize,
    ImageResizer.PastedDims dimensions
) implements AttachmentPayload {
}
