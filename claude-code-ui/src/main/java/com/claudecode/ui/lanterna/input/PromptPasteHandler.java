package com.claudecode.ui.lanterna.input;

import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.ImagePaste;
import com.claudecode.core.paste.PastedRefParser;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * Clipboard and bracketed-paste handling for the prompt.
 *
 * <p>Both entry points classify the payload the same way — clipboard image,
 * drag-dropped image path, large/multi-line text folded into a
 * {@code [Pasted text #N +X lines]} chip, or small text inserted inline — and
 * run the classification off the GUI thread because clipboard probing and
 * image decoding block. While pastes are in flight the hint shows
 * {@code Pasting text…} and an Enter is deferred until the last one lands, so
 * the submitted prompt always contains the chip.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/usePastedContent.ts} /
 *       {@code hooks/useTextInput.ts} — {@code onPaste} / {@code onImagePaste}
 *       classification: image, image path, long-text chip, inline text.</li>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} —
 *       {@code isPasting} state, the pasting indicator, and holding submit
 *       until the paste has resolved.</li>
 *   <li>{@code src/utils/imagePaste.ts} — clipboard image/text probing.</li>
 * </ul>
 */
final class PromptPasteHandler {

    /** Panel services the paste flow needs. */
    interface Host {
        /** Live terminal height for the newline fold threshold. */
        int terminalRows();
        /** GUI-thread marshaller, or null when the panel is not attached to a GUI. */
        Consumer<Runnable> guiInvoker();
        /** Current session id for the on-disk image cache. */
        String sessionId();
        /** Inserts a chip (or inline text) at the caret and publishes the pasted contents. */
        void insertChip(String chip, boolean armLazySpace);
        /** Recomputes the hint row (pasting indicator on/off). */
        void refreshHint();
        /** Performs the Enter that was held back while a paste was pending. */
        void submitDeferred();
    }

    private static final ExecutorService PASTE_EXECUTOR =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("clipboard-paste").factory());

    private final Host host;
    private final PromptPastedContentController pastedContent;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean deferredSubmit = new AtomicBoolean();

    PromptPasteHandler(Host host, PromptPastedContentController pastedContent) {
        this.host = host;
        this.pastedContent = pastedContent;
    }

    boolean isPending() { return pending.get() > 0; }

    /** Holds an Enter until in-flight pastes complete; false when nothing is pending. */
    boolean deferSubmitIfPending() {
        if (pending.get() <= 0) return false;
        deferredSubmit.set(true);
        return true;
    }

    /** Any non-Enter key after a deferred Enter cancels the deferred submit. */
    void cancelDeferredSubmit() {
        if (deferredSubmit.get()) deferredSubmit.set(false);
    }

    /** Marks one paste in flight (shows the pasting indicator). */
    void begin() {
        pending.incrementAndGet();
        host.refreshHint();
    }

    /**
     * Completes one paste on the GUI thread: applies {@code mutation}, drops the
     * pending count and fires a deferred submit once nothing is pending.
     */
    void complete(Runnable mutation) {
        Runnable completion = () -> {
            if (mutation != null) mutation.run();
            int remaining = pending.updateAndGet(value -> Math.max(0, value - 1));
            host.refreshHint();
            if (remaining == 0 && deferredSubmit.getAndSet(false)) {
                host.submitDeferred();
            }
        };
        Consumer<Runnable> invoker = host.guiInvoker();
        if (invoker != null) invoker.accept(completion);
        else completion.run();
    }

    /**
     * Bracketed paste: the terminal wrapped the payload in {@code \e[200~ …
     * \e[201~} so the text is exact. An empty payload on macOS means an image
     * was pasted with Cmd+V. Same classification as {@link #handleClipboardPaste}
     * minus the clipboard-text probe.
     */
    void handleBracketedPaste(String pastedText) {
        begin();
        final int rows = host.terminalRows(); // captured on the GUI thread
        PASTE_EXECUTOR.execute(() -> {
            try {
                if (StringUtils.isEmpty(pastedText)) {
                    ImagePaste.ImageWithDimensions img = ImagePaste.getImageFromClipboard();
                    if (img != null) insertClipboardImage(img, false);
                    return;
                }
                classifyText(pastedText, rows);
            } finally {
                complete(null);
            }
        });
    }

    /** Ctrl+V: clipboard image first, then clipboard text through the shared classification. */
    void handleClipboardPaste() {
        begin();
        final int rows = host.terminalRows(); // captured on the GUI thread
        PASTE_EXECUTOR.execute(() -> {
            try {
                ImagePaste.ImageWithDimensions img = ImagePaste.getImageFromClipboard();
                if (img != null) {
                    insertClipboardImage(img, true);
                    return;
                }
                String text = ImagePaste.getClipboardText();
                if (text == null) return;
                classifyText(text, rows);
            } finally {
                complete(null);
            }
        });
    }

    /**
     * Shared text classification: drag-dropped image path → image chip;
     * large/multi-line → pasted-text chip; otherwise inline at the caret with
     * newlines flattened (a SINGLE_LINE row cannot hold {@code \n}, and
     * inserting one would fire ENTER → premature submit).
     */
    private void classifyText(String text, int rows) {
        ImagePaste.ImageWithDimensions fromPath = ImagePaste.tryReadImageFromPath(text);
        if (fromPath != null) {
            int pasteId = pastedContent.nextId();
            PastedContent content = imageContent(pasteId, fromPath, ImagePaste.asImageFilePath(text));
            ImageStore.cacheImagePath(content, host.sessionId());
            pastedContent.put(content);
            scheduleInsert(PastedRefParser.formatImageRef(pasteId), true);
            return;
        }
        String stripped = PromptPasteTextPolicy.normalize(text);
        int numLines = PastedRefParser.getPastedTextRefNumLines(stripped);
        if (PromptPasteTextPolicy.shouldFoldIntoChip(stripped, numLines, rows)) {
            int pasteId = pastedContent.nextId();
            pastedContent.put(PastedContent.text(pasteId, stripped));
            scheduleInsert(PastedRefParser.formatPastedTextRef(pasteId, numLines), false);
            return;
        }
        String inline = stripped.replace('\n', ' ').stripTrailing();
        if (!inline.isEmpty()) scheduleInsert(inline, false);
    }

    /** Registers a clipboard image; Ctrl+V also caches it on disk so the chip carries a path. */
    private void insertClipboardImage(ImagePaste.ImageWithDimensions img, boolean cacheOnDisk) {
        int pasteId = pastedContent.nextId();
        PastedContent content = imageContent(pasteId, img, null);
        if (cacheOnDisk) {
            String cachedPath = ImageStore.cacheImagePath(content, host.sessionId());
            if (cachedPath != null) content = imageContent(pasteId, img, cachedPath);
        }
        pastedContent.put(content);
        scheduleInsert(PastedRefParser.formatImageRef(pasteId), true);
    }

    private static PastedContent imageContent(int pasteId, ImagePaste.ImageWithDimensions img,
                                              String filePath) {
        return new PastedContent(pasteId, "image", img.base64(), img.mediaType(),
            null, img.dimensions(), filePath);
    }

    /** Chip insertion must run on the GUI thread; without a GUI there is nowhere to insert. */
    private void scheduleInsert(String chip, boolean armLazySpace) {
        Consumer<Runnable> invoker = host.guiInvoker();
        if (invoker != null) invoker.accept(() -> host.insertChip(chip, armLazySpace));
    }
}
