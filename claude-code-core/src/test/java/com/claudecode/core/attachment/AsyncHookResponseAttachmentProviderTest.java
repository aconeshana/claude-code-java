package com.claudecode.core.attachment;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.claudecode.core.engine.AsyncHookResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AsyncHookResponseAttachment;
import com.claudecode.core.message.AttachmentPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class AsyncHookResponseAttachmentProviderTest {

    /** Stub dispatcher that returns canned responses and records the remove call. */
    private static final class StubDispatcher implements HookDispatcher {
        final List<AsyncHookResponse> responses;
        final AtomicInteger removeCalls = new AtomicInteger();
        final AtomicInteger lastRemoveCount = new AtomicInteger();

        StubDispatcher(List<AsyncHookResponse> responses) {
            this.responses = responses;
        }

        @Override
        public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) {
            return true;
        }

        @Override
        public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) {}

        @Override
        public void dispatchUserPromptSubmit(String prompt) {}

        @Override
        public void dispatchSessionStart(String trigger) {}

        @Override
        public void dispatchStop(String reason) {}

        @Override
        public List<AsyncHookResponse> checkForAsyncHookResponses() {
            return responses;
        }

        @Override
        public void removeDeliveredAsyncHooks(List<String> processIds) {
            removeCalls.incrementAndGet();
            lastRemoveCount.set(processIds.size());
        }
    }

    private static AttachmentContext contextWith(HookDispatcher dispatcher) {
        Set<String> empty = ConcurrentHashMap.newKeySet();
        return AttachmentContext.builder(".")
            .loadedNestedMemoryPaths(empty)
            .nestedMemoryAttachmentTriggers(empty)
            .hookDispatcher(dispatcher)
            .build();
    }

    @Test
    void collectConvertsResponsesAndRemovesDelivered() {
        StubDispatcher dispatcher = new StubDispatcher(List.of(
            new AsyncHookResponse("async-hook-1", "{\"ok\":true}", "cmd", "PreToolUse", "Bash",
                null, "full stdout", "stderr text", 0),
            new AsyncHookResponse("async-hook-2", "{\"done\":1}", "cmd2", "Stop", null,
                null, "stdout2", "", 2)));
        AsyncHookResponseAttachmentProvider provider = new AsyncHookResponseAttachmentProvider();

        List<AttachmentPayload> out = provider.collect(contextWith(dispatcher));

        assertEquals(2, out.size());
        AsyncHookResponseAttachment a0 = (AsyncHookResponseAttachment) out.getFirst();
        assertEquals("async-hook-1", a0.processId());
        assertEquals("{\"ok\":true}", a0.responseJson());
        assertEquals("PreToolUse", a0.hookEvent());
        assertEquals("Bash", a0.toolName());
        assertEquals(0, a0.exitCode());

        AsyncHookResponseAttachment a1 = (AsyncHookResponseAttachment) out.get(1);
        assertEquals("async-hook-2", a1.processId());
        assertEquals(2, a1.exitCode());

        assertEquals(1, dispatcher.removeCalls.get(), "delivered responses must be removed");
        assertEquals(2, dispatcher.lastRemoveCount.get());
    }

    @Test
    void collectWithEmptyResponsesReturnsEmptyAndDoesNotRemove() {
        StubDispatcher dispatcher = new StubDispatcher(List.of());
        AsyncHookResponseAttachmentProvider provider = new AsyncHookResponseAttachmentProvider();

        List<AttachmentPayload> out = provider.collect(contextWith(dispatcher));

        assertEquals(List.of(), out);
        assertEquals(0, dispatcher.removeCalls.get(), "no remove call when nothing was surfaced");
    }

    @Test
    void collectWithNullDispatcherReturnsEmpty() {
        AsyncHookResponseAttachmentProvider provider = new AsyncHookResponseAttachmentProvider();
// hookDispatcher == null → safe empty result, no NPE.
        List<AttachmentPayload> out = provider.collect(contextWith(null));
        assertEquals(List.of(), out);
    }
}
