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

    /**
     * The webui `@` menu's wire form, transcribed from
     * `webui/src/views/atReference.ts`'s `formatMention`. The two sides are
     * one contract: the client inserts this text and this provider parses it,
     * so a change to either escaping rule has to fail here.
     */
    private static String formatMention(String path) {
        return "@" + path.replace(" ", "\\ ");
    }

    /**
     * The client's pick for a path containing spaces must be read back as the
     * file it names. Reasoning about the two regexes independently is not
     * enough — this asserts the closed loop the feature actually depends on,
     * because a mismatch means the model receives the mention as literal text.
     */
    @Test
    void webClientMentionFormatForASpacedPathResolves(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("my file with spaces.txt");
        Files.writeString(file, "MENTION_PROBE_TOKEN_42");
        List<AttachmentPayload> out = new AtMentionedFilesProvider()
            .collect(ctx("please read " + formatMention(file.getFileName().toString()) + " now", dir));
        assertEquals(1, out.size());
        FileContentAttachment a = (FileContentAttachment) out.getFirst();
        assertEquals(file.toString(), a.filename());
        assertEquals("MENTION_PROBE_TOKEN_42", a.content());
    }

    /** A directory row's trailing slash is not a file; it yields nothing. */
    @Test
    void webClientMentionFormatForADirectoryYieldsNoAttachment(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        List<AttachmentPayload> out = new AtMentionedFilesProvider()
            .collect(ctx("descend into " + formatMention("src/") + " please", dir));
        assertTrue(out.isEmpty());
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
