package com.claudecode.core.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasteStoreTest {

    @Test
    void stagedContentIsReadableBeforeTheAsynchronousFileWriteRuns(@TempDir Path tmp) {
        String content = "large prompt paste";
        String hash = PasteStore.hashPastedText(content);
        AtomicReference<Runnable> queued = new AtomicReference<>();

        PasteStore.storePastedTextAsync(hash, content, tmp, queued::set);

        assertEquals(content, PasteStore.retrievePastedText(hash, tmp),
            "released Rzi/vzi exposes the in-flight content synchronously");
        queued.get().run();
        assertEquals(content, PasteStore.retrievePastedText(hash, tmp));
    }

    @Test
    void failedFileWriteFallsBackToTheBoundedMemoryCache(@TempDir Path tmp) throws Exception {
        Path notDirectory = tmp.resolve("not-a-directory");
        Files.writeString(notDirectory, "occupied");
        String content = "content retained after write failure";
        String hash = PasteStore.hashPastedText(content);

        PasteStore.storePastedTextAsync(hash, content, notDirectory, Runnable::run);

        assertEquals(content, PasteStore.retrievePastedText(hash, notDirectory),
            "released qJd/$Jd retains failed writes in its memory fallback");
    }
}
