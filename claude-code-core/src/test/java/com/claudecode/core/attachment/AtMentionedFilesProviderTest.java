package com.claudecode.core.attachment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.ImageFileAttachment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coverage for {@link AtMentionedFilesProvider} — extracts {@code @}-mentioned file paths from the
 * user input and surfaces them as file content attachments.
 */
class AtMentionedFilesProviderTest {

    @Test
    void emptyInputYieldsNoAttachments(@TempDir Path dir) {
        assertTrue(new AtMentionedFilesProvider().collect(ctx("", dir)).isEmpty());
    }

    @Test
    void mentionedRegularFileIsReadAsAttachment(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello world");
        List<AttachmentPayload> out = new AtMentionedFilesProvider()
            .collect(ctx("please read @notes.txt now", dir));
        assertEquals(1, out.size());
        FileContentAttachment a = (FileContentAttachment) out.getFirst();
        assertEquals(file.toString(), a.filename());
        assertEquals("hello world", a.content());
    }

    @Test
    void nonPathMentionsAreIgnored(@TempDir Path dir) {
        List<AttachmentPayload> out = new AtMentionedFilesProvider()
            .collect(ctx("ping @user and @channel please", dir));
        assertTrue(out.isEmpty());
    }

    @Test
    void mentionedMissingFileIsSkipped(@TempDir Path dir) {
        List<AttachmentPayload> out = new AtMentionedFilesProvider()
            .collect(ctx("open @does-not-exist.txt", dir));
        assertTrue(out.isEmpty());
    }

    @Test
    void mentionedPngUsesImageAttachmentInsteadOfUtf8Text(@TempDir Path dir) throws Exception {
        Path image = dir.resolve("probe.png");
        Files.write(image, Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAFklEQVR4nGNUSDjwnwEPYMInOVgUAAC7FwJL73qn5AAAAABJRU5ErkJggg=="));

        List<AttachmentPayload> out = new AtMentionedFilesProvider()
            .collect(ctx("inspect @probe.png", dir));

        assertEquals(1, out.size());
        ImageFileAttachment attachment = (ImageFileAttachment) out.getFirst();
        assertEquals("image/png", attachment.mediaType());
        assertEquals(79, attachment.originalSize());
        assertEquals(8, attachment.dimensions().originalWidth());
        assertEquals(6, attachment.dimensions().originalHeight());
    }

    private static AttachmentContext ctx(String input, Path cwd) {
        Set<String> empty = ConcurrentHashMap.newKeySet();
        return AttachmentContext.builder(cwd.toString())
            .input(input)
            .loadedNestedMemoryPaths(empty)
            .nestedMemoryAttachmentTriggers(empty)
            .build();
    }
}
