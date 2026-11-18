package com.claudecode.cli;

import com.claudecode.commands.diff.TurnDiffExtractor;
import com.claudecode.core.message.Message;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link TurnDiffExtractor#extract} remembered per transcript instance.
 *
 * <p>The webui's changed-files cards ask for one turn each, so a page showing
 * a long session fires dozens of summary requests at once, and every one of
 * them used to walk the whole transcript and coerce every tool result again
 * to find its single turn. A transcript read from disk is served as the same
 * immutable list until the file changes, so the extraction is kept per list
 * <em>instance</em>: equal content in a new list is extracted again, which is
 * never wrong, only slower.
 *
 * <p>Concurrent requests for one list share a single extraction. A handful of
 * recent lists are kept, each only weakly, so a transcript nothing else
 * references any more is not kept alive here.
 */
final class TurnDiffMemo {

    /** One per session on screen is plenty; older ones are extracted again. */
    private static final int KEPT = 8;

    private final Deque<Entry> entries = new ArrayDeque<>();

    /** The turn diffs of {@code messages}, newest turn first. */
    List<TurnDiffExtractor.TurnDiff> of(List<Message> messages) {
        CompletableFuture<List<TurnDiffExtractor.TurnDiff>> pending;
        boolean owner = false;
        synchronized (entries) {
            pending = lookup(messages);
            if (pending == null) {
                pending = new CompletableFuture<>();
                owner = true;
                entries.addFirst(new Entry(new WeakReference<>(messages), pending));
                while (entries.size() > KEPT) entries.removeLast();
            }
        }
        if (owner) extractInto(messages, pending);
        try {
            return pending.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw e;
        }
    }

    private CompletableFuture<List<TurnDiffExtractor.TurnDiff>> lookup(List<Message> messages) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            List<Message> kept = entry.messages().get();
            if (kept == null) {
                it.remove();
            } else if (kept == messages) {
                return entry.diffs();
            }
        }
        return null;
    }

    private void extractInto(List<Message> messages,
            CompletableFuture<List<TurnDiffExtractor.TurnDiff>> pending) {
        try {
            pending.complete(TurnDiffExtractor.extract(messages));
        } catch (RuntimeException e) {
            // A failed extraction is not remembered: the next request tries again.
            synchronized (entries) {
                entries.removeIf(entry -> entry.diffs() == pending);
            }
            pending.completeExceptionally(e);
        }
    }

    private record Entry(WeakReference<List<Message>> messages,
            CompletableFuture<List<TurnDiffExtractor.TurnDiff>> diffs) {
    }
}
