package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.EditedFileAttachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class ChangedFilesProviderTest {

    private static AttachmentContext ctxWith(FileStateCache cache) {
        return ctxWith(cache, _ -> false);
    }

    private static AttachmentContext ctxWith(FileStateCache cache, Predicate<String> fileReadDenied) {
        return AttachmentContext.builder(".")
            .fileStateCache(cache)
            .fileReadDenied(fileReadDenied)
            .build();
    }

    @Test
    void emitsEditedAttachmentWhenFileChangedAfterRead() throws IOException {
        Path f = Files.createTempFile("cfp", ".txt");
        try {
            Files.writeString(f, "line1\nline2\n");
            long mtime = Files.getLastModifiedTime(f).toMillis();
            FileStateCache cache = new FileStateCache();
            cache.set(f.toString(),
                new FileStateCache.FileState("line1\nline2\n", mtime - 10000, null, null, false));

            // Modify the file on disk after the (simulated) read.
            Files.writeString(f, "line1\nCHANGED\n");

            ChangedFilesProvider p = new ChangedFilesProvider();
            List<AttachmentPayload> out = p.collect(ctxWith(cache));

            assertEquals(1, out.size());
            assertInstanceOf(EditedFileAttachment.class, out.getFirst());
            EditedFileAttachment a = (EditedFileAttachment) out.getFirst();
            assertEquals(f.toString(), a.filename());
            assertTrue(Strings.CS.contains(a.snippet(), "CHANGED"),
                "diff snippet should surface the edited line, got: " + a.snippet());
            assertEquals("line1\nCHANGED\n", cache.get(f.toString()).content());
            assertTrue(p.collect(ctxWith(cache)).isEmpty(),
                "successful re-read should refresh the cache and suppress repeat attachments");
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void emitsNothingWhenFileUnchanged() {
        ChangedFilesProvider p = new ChangedFilesProvider();
        FileStateCache cache = new FileStateCache();
        // timestampMs equal to a fresh stat → mtime <= st.timestampMs → skip.
        cache.set("/tmp/never.txt",
            new FileStateCache.FileState("same", 9_999_999_999L, null, null, false));
        List<AttachmentPayload> out = p.collect(ctxWith(cache));
        assertTrue(out.isEmpty());
    }

    @Test
    void skipsPartialReads() {
        ChangedFilesProvider p = new ChangedFilesProvider();
        FileStateCache cache = new FileStateCache();

        cache.set("/tmp/partial.txt",
            new FileStateCache.FileState("abc", 0, 0, 3, true));
        List<AttachmentPayload> out = p.collect(ctxWith(cache));
        assertTrue(out.isEmpty());
    }

    @Test
    void emitsNothingWithNullCache() {
        assertTrue(new ChangedFilesProvider().collect(ctxWith(null)).isEmpty());
    }

    @Test
    void respectsCurrentReadDenyRulesWithoutRefreshingCache() throws IOException {
        Path f = Files.createTempFile("cfp-denied", ".txt");
        try {
            Files.writeString(f, "new content\n");
            FileStateCache cache = new FileStateCache();
            FileStateCache.FileState previous = new FileStateCache.FileState(
                "old content\n", Files.getLastModifiedTime(f).toMillis() - 10_000,
                null, null, false);
            cache.set(f.toString(), previous);

            assertTrue(new ChangedFilesProvider().collect(
                ctxWith(cache, path -> path.equals(f.toString()))).isEmpty());
            assertEquals(previous, cache.get(f.toString()));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void evictsOnlyMissingFilesFromReadState() throws IOException {
        Path f = Files.createTempFile("cfp-missing", ".txt");
        FileStateCache cache = new FileStateCache();
        cache.set(f.toString(), new FileStateCache.FileState("old\n", 0, null, null, false));
        Files.delete(f);

        assertTrue(new ChangedFilesProvider().collect(ctxWith(cache)).isEmpty());
        assertNull(cache.get(f.toString()));
    }

    @Test
    void skipsFilesOutsideReadToolTextLimitsWithoutRefreshingCache() throws IOException {
        Path f = Files.createTempFile("cfp-large", ".txt");
        try {
            Files.writeString(f, "x".repeat(256 * 1024 + 1));
            FileStateCache cache = new FileStateCache();
            FileStateCache.FileState previous = new FileStateCache.FileState(
                "old\n", Files.getLastModifiedTime(f).toMillis() - 10_000,
                null, null, false);
            cache.set(f.toString(), previous);

            assertTrue(new ChangedFilesProvider().collect(ctxWith(cache)).isEmpty());
            assertEquals(previous, cache.get(f.toString()));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void skipsTokenDenseJsonAndBinaryContentWithoutRefreshingCache() throws IOException {
        for (String suffix : List.of(".json", ".txt")) {
            Path f = Files.createTempFile("cfp-unreadable", suffix);
            try {
                String content = Strings.CS.equals(".json", suffix)
                    ? "{" + "\"x\":1,".repeat(9_000) + "\"z\":0}"
                    : "text\0binary";
                Files.writeString(f, content);
                FileStateCache cache = new FileStateCache();
                FileStateCache.FileState previous = new FileStateCache.FileState(
                    "old\n", Files.getLastModifiedTime(f).toMillis() - 10_000,
                    null, null, false);
                cache.set(f.toString(), previous);

                assertTrue(new ChangedFilesProvider().collect(ctxWith(cache)).isEmpty());
                assertEquals(previous, cache.get(f.toString()));
            } finally {
                Files.deleteIfExists(f);
            }
        }
    }

    @Test
    void appliesReleased197AggregateSnippetBudget() throws IOException {
        List<Path> files = new ArrayList<>();
        try {
            FileStateCache cache = new FileStateCache();
            String oldContent = numberedLines("old");
            String newContent = numberedLines("new");
            for (int i = 0; i < 3; i++) {
                Path file = Files.createTempFile("cfp-budget-" + i, ".txt");
                files.add(file);
                Files.writeString(file, newContent);
                cache.set(file.toString(), new FileStateCache.FileState(
                    oldContent, Files.getLastModifiedTime(file).toMillis() - 10_000,
                    null, null, false));
            }

            List<AttachmentPayload> out = new ChangedFilesProvider().collect(ctxWith(cache));

            assertEquals(3, out.size());
            assertFalse(((EditedFileAttachment) out.getFirst()).snippet().isEmpty());
            assertFalse(((EditedFileAttachment) out.get(1)).snippet().isEmpty());
            assertTrue(((EditedFileAttachment) out.get(2)).snippet().isEmpty(),
                "later changed files should retain the attachment but omit the diff");
        } finally {
            for (Path file : files) Files.deleteIfExists(file);
        }
    }

    private static String numberedLines(String prefix) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 1_500; i++) {
            content.append(prefix).append('-').append(i).append(" payload payload payload\n");
        }
        return content.toString();
    }
}
