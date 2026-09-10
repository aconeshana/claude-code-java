package com.claudecode.gateway;

import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import java.util.List;

/**
 * Attachments extracted from one protocol request, split by how the session
 * owner consumes them: images become inline base64 picture blocks, files are
 * persisted to disk and referenced by path.
 *
 * @param images inline image attachments (base64 picture payloads)
 * @param files  file attachments persisted by the session owner
 */
record SubmissionAttachments(
        List<SessionHostSubmission.Attachment> images,
        List<SessionHostSubmission.Attachment> files) {

    static final SubmissionAttachments NONE =
        new SubmissionAttachments(List.of(), List.of());

    SubmissionAttachments {
        images = List.copyOf(images == null ? List.of() : images);
        files = List.copyOf(files == null ? List.of() : files);
    }

    boolean isEmpty() {
        return images.isEmpty() && files.isEmpty();
    }
}
