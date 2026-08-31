package com.claudecode.core.attachment;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TextReminderAttachment;


public final class DateChangeAttachmentProvider implements AttachmentProvider {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    @Override
    public String name() {
        return "date_change";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        Instant last = null;
        for (Message msg : ctx.messages()) {
            Optional<Instant> ts = msg.timestamp();
            if (ts.isPresent()) {
                last = ts.get();
            }
        }
        if (last == null) {
            return List.of();
        }
        String lastDay = FMT.format(last);
        String today = FMT.format(Instant.now());
        if (lastDay.equals(today)) {
            return List.of();
        }
        return List.of(new TextReminderAttachment("The date is now " + today + "."));
    }
}
