package com.claudecode.runtime.sessionhost;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.PastedRefParser;

import java.util.Base64;

import org.apache.commons.lang3.StringUtils;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles one {@link SessionHostSubmission} into the prompt text plus the
 * pasted-contents map every engine front end consumes.
 *
 * <p>Inline images become numbered {@code PastedContent} chips referenced
 * from the prompt as {@code [Image #N]} — the same shape the TUI input panel
 * produces for pasted screenshots, so the engine's image wiring (resize,
 * disk persistence, model-visible blocks) is identical for remote turns.
 * File attachments are handed to the owning endpoint for persistence; each
 * persisted path is appended to the prompt as an {@code Attached file:}
 * line the model can read.
 *
 * @param prompt   the display text with image refs and attachment lines
 * @param pasted   the numbered image chips, keyed by chip id
 */
public record RemoteSubmissionPrompt(String prompt, Map<Integer, PastedContent> pasted) {

    public RemoteSubmissionPrompt {
        pasted = Map.copyOf(pasted == null ? Map.of() : pasted);
    }

    /** Functional persistence endpoint for one file attachment. */
    @FunctionalInterface
    public interface FilePersister {
        /** Persists one attachment and returns the path that was written. */
        String persist(SessionHostSubmission.Attachment attachment);
    }

    /**
     * Assembles one submission. Attachments are persisted in order through
     * {@code persister} before the prompt references them, so a persisted
     * path is on disk by the time the model can read it.
     */
    public static RemoteSubmissionPrompt assemble(
            SessionHostSubmission submission, FilePersister persister) {
        Map<Integer, PastedContent> pasted = new LinkedHashMap<>();
        StringBuilder prompt = new StringBuilder(submission.prompt());
        int imageId = 1;
        for (SessionHostSubmission.Attachment image : submission.images()) {
            String mediaType = StringUtils.isBlank(image.mimeType())
                ? "image/png" : image.mimeType();
            pasted.put(imageId, PastedContent.image(imageId,
                Base64.getEncoder().encodeToString(image.data()),
                mediaType, null, null));
            if (!prompt.isEmpty()) prompt.append(' ');
            prompt.append(PastedRefParser.formatImageRef(imageId));
            imageId++;
        }
        for (SessionHostSubmission.Attachment file : submission.attachments()) {
            String path = persister.persist(file);
            if (!prompt.isEmpty()) prompt.append('\n');
            prompt.append("Attached file: ").append(path);
        }
        return new RemoteSubmissionPrompt(prompt.toString(), pasted);
    }
}
