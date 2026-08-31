package com.claudecode.core.attachment;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.TextReminderAttachment;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class CriticalSystemReminderProviderTest {

    private static AttachmentContext ctxWith(String reminder) {
        return AttachmentContext.builder(".")
            .criticalSystemReminder(reminder)
            .build();
    }

    @Test
    void emitsTextReminderWhenCriticalSet() {
        CriticalSystemReminderProvider p = new CriticalSystemReminderProvider();
        List<AttachmentPayload> out = p.collect(ctxWith("DO NOT run destructive commands"));
        assertEquals(1, out.size());
        assertInstanceOf(TextReminderAttachment.class, out.getFirst());
        assertEquals("DO NOT run destructive commands",
            ((TextReminderAttachment) out.getFirst()).text());
    }

    @Test
    void emitsNothingWhenCriticalNull() {
        assertTrue(new CriticalSystemReminderProvider().collect(ctxWith(null)).isEmpty());
    }

    @Test
    void emitsNothingWhenCriticalBlank() {
        assertTrue(new CriticalSystemReminderProvider().collect(ctxWith("   ")).isEmpty());
    }
}
