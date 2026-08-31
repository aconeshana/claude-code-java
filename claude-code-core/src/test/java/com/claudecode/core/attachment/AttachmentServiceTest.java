package com.claudecode.core.attachment;

import com.claudecode.core.message.TextReminderAttachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.claudecode.core.message.AttachmentPayload;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the maybe(name, fn) dispatch core: registration order, feature-flag gating, and the
 * empty default.
 */
class AttachmentServiceTest {

    @Test
    void emptyReturnsNoProvidersAndNoPayloads() {
        AttachmentService svc = AttachmentService.empty();
        assertTrue(svc.collect(sampleContext()).isEmpty());
    }

    @Test
    void collectRunsProvidersInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        AttachmentProvider a = named("a", _ -> { order.add("a"); return List.of(); });
        AttachmentProvider b = named("b", _ -> { order.add("b"); return List.of(); });
        AttachmentService svc = new AttachmentService(List.of(a, b), FeatureFlagRegistry.allOff());
        svc.collect(sampleContext());
        assertEquals(List.of("a", "b"), order);
    }

    @Test
    void disabledProviderIsSkipped() {
        boolean[] called = {false};
        AttachmentProvider gated = new AttachmentProvider() {
            @Override
            public String name() {
                return "gated";
            }

            @Override
            public boolean isEnabled(FeatureFlagRegistry flags) {
                return false;
            }

            @Override
            public List<AttachmentPayload> collect(AttachmentContext ctx) {
                called[0] = true;
                return List.of();
            }
        };
        AttachmentService svc = new AttachmentService(List.of(gated), FeatureFlagRegistry.allOff());
        svc.collect(sampleContext());
        assertFalse(called[0], "disabled provider must not be collected");
    }

    @Test
    void payloadsFromAllProvidersAreConcatenatedInOrder() {
        AttachmentProvider p1 = named("a", _ -> List.of(
            new TextReminderAttachment("one")));
        AttachmentProvider p2 = named("b", _ -> List.of(
            new TextReminderAttachment("two")));
        AttachmentService svc = new AttachmentService(List.of(p1, p2), FeatureFlagRegistry.allOff());
        List<AttachmentPayload> out = svc.collect(sampleContext());
        assertEquals(2, out.size());
        assertEquals("one", ((TextReminderAttachment) out.getFirst()).text());
        assertEquals("two", ((TextReminderAttachment) out.get(1)).text());
    }

    private static AttachmentProvider named(String name, Function<AttachmentContext, List<AttachmentPayload>> fn) {
        return new AttachmentProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<AttachmentPayload> collect(AttachmentContext ctx) {
                return fn.apply(ctx);
            }
        };
    }

    private static AttachmentContext sampleContext() {
        Set<String> empty = ConcurrentHashMap.newKeySet();
        return AttachmentContext.builder(".")
            .loadedNestedMemoryPaths(empty)
            .nestedMemoryAttachmentTriggers(empty)
            .build();
    }
}
