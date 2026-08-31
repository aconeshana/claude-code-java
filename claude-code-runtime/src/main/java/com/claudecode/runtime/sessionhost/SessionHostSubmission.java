package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.List;

/** Model-visible user input arriving from an attached non-terminal endpoint. */
@Explanation("Remote endpoint submission carried outside PTY rendering")
public record SessionHostSubmission(
        String prompt,
        String messageId,
        List<Attachment> images,
        List<Attachment> attachments) {

    public SessionHostSubmission {
        prompt = prompt == null ? "" : prompt;
        messageId = messageId == null ? "" : messageId;
        images = List.copyOf(images == null ? List.of() : images);
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
    }

    /** Bounded binary attachment decoded by the Session Link server. */
    public record Attachment(String mimeType, String fileName, byte[] data) {
        public Attachment {
            mimeType = mimeType == null ? "" : mimeType;
            fileName = fileName == null ? "" : fileName;
            data = data == null ? new byte[0] : data.clone();
        }

        @Override public byte[] data() { return data.clone(); }
    }
}
