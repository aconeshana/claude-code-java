package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.PermissionAskContext;

/** Immutable permission presentation prepared before crossing onto the GUI thread. */
public final class PreparedPermissionPrompt {
    private final PermissionAskContext context;
    private final PermissionRequestBody body;
    private final RejectedFileChangePreview rejectedFileChangePreview;

    PreparedPermissionPrompt(PermissionAskContext context, PermissionRequestBody body,
                             RejectedFileChangePreview rejectedFileChangePreview) {
        this.context = context;
        this.body = body;
        this.rejectedFileChangePreview = rejectedFileChangePreview;
    }

    public PermissionAskContext context() { return context; }

    PermissionRequestBody body() { return body; }

    public RejectedFileChangePreview rejectedFileChangePreview() {
        return rejectedFileChangePreview;
    }
}
