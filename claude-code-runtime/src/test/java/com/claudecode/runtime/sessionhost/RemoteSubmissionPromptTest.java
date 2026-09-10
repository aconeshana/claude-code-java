package com.claudecode.runtime.sessionhost;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.paste.PastedRefParser;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteSubmissionPromptTest {

    @Test
    void imagesBecomeNumberedChipsReferencedFromThePrompt() {
        SessionHostSubmission submission = new SessionHostSubmission(
            "describe this", "m1",
            List.of(new SessionHostSubmission.Attachment(
                "image/png", "pic.png", new byte[] {1, 2})),
            List.of());

        RemoteSubmissionPrompt assembled = RemoteSubmissionPrompt.assemble(
            submission, file -> "/stored/" + file.fileName());

        assertThat(assembled.prompt())
            .isEqualTo("describe this [Image #1]");
        assertThat(assembled.pasted()).containsOnlyKeys(1);
        assertThat(assembled.pasted().get(1).isImage()).isTrue();
        assertThat(assembled.pasted().get(1).mediaType()).isEqualTo("image/png");
        assertThat(assembled.pasted().get(1).content())
            .isEqualTo(Base64.getEncoder().encodeToString(new byte[] {1, 2}));
    }

    @Test
    void blankImageMediaTypeDefaultsToPng() {
        SessionHostSubmission submission = new SessionHostSubmission(
            "", "m1",
            List.of(new SessionHostSubmission.Attachment(null, null, new byte[] {9})),
            List.of());

        RemoteSubmissionPrompt assembled = RemoteSubmissionPrompt.assemble(
            submission, _ -> "/stored/x");

        assertThat(assembled.pasted().get(1).mediaType()).isEqualTo("image/png");
        // A blank prompt keeps a leading space off the image ref.
        assertThat(assembled.prompt()).isEqualTo("[Image #1]");
    }

    @Test
    void filesArePersistedThenReferencedAsAttachedFileLines() {
        List<String> persisted = new ArrayList<>();
        SessionHostSubmission submission = new SessionHostSubmission(
            "read the report", "m1", List.of(),
            List.of(
                new SessionHostSubmission.Attachment(
                    "application/pdf", "a.pdf", new byte[] {1}),
                new SessionHostSubmission.Attachment(
                    "text/plain", "notes.txt", new byte[] {2})));

        RemoteSubmissionPrompt assembled = RemoteSubmissionPrompt.assemble(
            submission, file -> {
                persisted.add(file.fileName());
                return "/ws/.claude/remote-attachments/" + file.fileName();
            });

        // Persistence happens in order before the prompt is referenced.
        assertThat(persisted).containsExactly("a.pdf", "notes.txt");
        assertThat(assembled.prompt()).isEqualTo(
            """
            read the report
            Attached file: /ws/.claude/remote-attachments/a.pdf
            Attached file: /ws/.claude/remote-attachments/notes.txt""");
        assertThat(assembled.pasted()).isEmpty();
    }

    @Test
    void imagesAndFilesComposeInTheSamePrompt() {
        SessionHostSubmission submission = new SessionHostSubmission(
            "both", "m1",
            List.of(
                new SessionHostSubmission.Attachment("image/png", "1.png", new byte[] {1}),
                new SessionHostSubmission.Attachment("image/jpeg", "2.jpg", new byte[] {2})),
            List.of(new SessionHostSubmission.Attachment(
                "text/plain", "list.txt", new byte[] {3})));

        RemoteSubmissionPrompt assembled = RemoteSubmissionPrompt.assemble(
            submission, file -> "/p/" + file.fileName());

        assertThat(assembled.prompt()).isEqualTo(
            "both [Image #1] [Image #2]\nAttached file: /p/list.txt");
        assertThat(assembled.pasted()).containsOnlyKeys(1, 2);
        assertThat(PastedRefParser.parseReferences(assembled.prompt()))
            .extracting(PastedRefParser.Ref::id)
            .containsExactly(1, 2);
    }

    @Test
    void plainTextSubmissionPassesThroughUntouched() {
        SessionHostSubmission submission = new SessionHostSubmission(
            "just words", "m1", List.of(), List.of());

        RemoteSubmissionPrompt assembled = RemoteSubmissionPrompt.assemble(
            submission, _ -> "/never");

        assertThat(assembled.prompt()).isEqualTo("just words");
        assertThat(assembled.pasted()).isEmpty();
    }
}
