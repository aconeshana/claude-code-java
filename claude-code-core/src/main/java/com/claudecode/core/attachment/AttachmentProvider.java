package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;

/**
 * One dynamically-attached block contributed to the request each turn.
 */
public interface AttachmentProvider {


    String name();

    /** Whether this provider should run this session. Defaults to always-on. */
    default boolean isEnabled(FeatureFlagRegistry flags) {
        return true;
    }

    /** Produce this turn's attachments (empty list = nothing to add). */
    List<AttachmentPayload> collect(AttachmentContext ctx);
}
