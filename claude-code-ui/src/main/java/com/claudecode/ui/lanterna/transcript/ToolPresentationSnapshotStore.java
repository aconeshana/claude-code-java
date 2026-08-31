package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.ui.lanterna.dialog.RejectedFileChangePreview;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Turn-scoped immutable presentation snapshots shared across worker and GUI threads. */
public final class ToolPresentationSnapshotStore {
    public record Ticket(long epoch, String toolUseId) {}

    record Snapshot(RejectedFileChangePreview filePreview, String plan) {}

    private record Entry(long epoch, RejectedFileChangePreview filePreview, String plan) {}

    private final AtomicLong epoch = new AtomicLong();
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    Ticket ticket(String toolUseId) {
        return new Ticket(epoch.get(), toolUseId);
    }

    boolean isCurrent(Ticket ticket) {
        return ticket != null && epoch.get() == ticket.epoch();
    }

    boolean publishFilePreview(Ticket ticket, RejectedFileChangePreview preview) {
        if (!valid(ticket) || preview == null) return false;
        entries.compute(ticket.toolUseId(), (_, current) -> {
            if (epoch.get() != ticket.epoch()) return current;
            String plan = current != null && current.epoch() == ticket.epoch()
                ? current.plan() : null;
            return new Entry(ticket.epoch(), preview, plan);
        });
        return publicationStillCurrent(ticket);
    }

    boolean publishPlan(Ticket ticket, String plan) {
        if (!valid(ticket) || StringUtils.isBlank(plan)) return false;
        entries.compute(ticket.toolUseId(), (_, current) -> {
            if (epoch.get() != ticket.epoch()) return current;
            RejectedFileChangePreview preview =
                current != null && current.epoch() == ticket.epoch()
                    ? current.filePreview() : null;
            return new Entry(ticket.epoch(), preview, plan);
        });
        return publicationStillCurrent(ticket);
    }

    RejectedFileChangePreview consumeFilePreview(String toolUseId) {
        if (toolUseId == null) return null;
        AtomicReference<RejectedFileChangePreview> consumed = new AtomicReference<>();
        entries.computeIfPresent(toolUseId, (_, entry) -> {
            if (entry.epoch() != epoch.get()) return null;
            consumed.set(entry.filePreview());
            return entry.plan() == null ? null
                : new Entry(entry.epoch(), null, entry.plan());
        });
        return consumed.get();
    }

    String consumePlan(String toolUseId) {
        if (toolUseId == null) return null;
        AtomicReference<String> consumed = new AtomicReference<>();
        entries.computeIfPresent(toolUseId, (_, entry) -> {
            if (entry.epoch() != epoch.get()) return null;
            consumed.set(entry.plan());
            return entry.filePreview() == null ? null
                : new Entry(entry.epoch(), entry.filePreview(), null);
        });
        return consumed.get();
    }

    Snapshot consume(String toolUseId) {
        if (toolUseId == null) return new Snapshot(null, null);
        AtomicReference<Snapshot> consumed = new AtomicReference<>(new Snapshot(null, null));
        entries.computeIfPresent(toolUseId, (_, entry) -> {
            if (entry.epoch() == epoch.get()) {
                consumed.set(new Snapshot(entry.filePreview(), entry.plan()));
            }
            return null;
        });
        return consumed.get();
    }

    void discard(String toolUseId) {
        if (toolUseId != null) entries.remove(toolUseId);
    }

    void resetTurn() {
        epoch.incrementAndGet();
        entries.clear();
    }

    private boolean valid(Ticket ticket) {
        return ticket != null && StringUtils.isNotBlank(ticket.toolUseId())
            && epoch.get() == ticket.epoch();
    }

    private boolean publicationStillCurrent(Ticket ticket) {
        if (isCurrent(ticket)) return true;
        entries.computeIfPresent(ticket.toolUseId(), (_, entry) ->
            entry.epoch() == ticket.epoch() ? null : entry);
        return false;
    }
}
