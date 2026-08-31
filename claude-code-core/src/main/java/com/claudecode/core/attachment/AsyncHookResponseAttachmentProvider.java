package com.claudecode.core.attachment;

import java.util.ArrayList;
import java.util.List;

import com.claudecode.core.engine.AsyncHookResponse;
import com.claudecode.core.message.AsyncHookResponseAttachment;
import com.claudecode.core.message.AttachmentPayload;

/**
 * Per-turn provider that re-injects completed background (output-driven / config-async) hook
 * results as {@code async_hook_response} attachments.
 */
public final class AsyncHookResponseAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "async_hook_responses";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (ctx.hookDispatcher() == null) {
            return List.of();
        }
        List<AsyncHookResponse> responses = ctx.hookDispatcher().checkForAsyncHookResponses();
        if (responses.isEmpty()) {
            return List.of();
        }
        List<String> processIds = new ArrayList<>();
        List<AttachmentPayload> out = new ArrayList<>(responses.size());
        for (AsyncHookResponse r : responses) {
            processIds.add(r.processId());
            out.add(new AsyncHookResponseAttachment(
                r.processId(), r.hookName(), r.hookEvent(), r.toolName(),
                r.responseJson(), r.stdout(), r.stderr(), r.exitCode()));
        }
        ctx.hookDispatcher().removeDeliveredAsyncHooks(processIds);
        return out;
    }
}
