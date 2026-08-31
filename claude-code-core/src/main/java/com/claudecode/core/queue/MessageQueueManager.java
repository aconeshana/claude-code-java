package com.claudecode.core.queue;

import com.claudecode.core.message.SDKMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Thread-safe priority command queue for injecting messages between query turns.
 *
 * <ul>
 *   <li>module-level queue functions
 *       ({@code enqueue}, {@code dequeue}, {@code peek}, {@code hasCommandsInQueue}, etc.)</li>
 *   <li>bounded, session-scoped SDK event
 *       side queue drained by non-interactive output independently of commands,
 *       including the per-main-message sequence boundary needed to preserve
 *       synchronous drain-before-output ordering.</li>
 * </ul>
 *
 * <p>Priority order: {@code NOW > NEXT > LATER}. Within the same priority,
 * commands are dequeued FIFO.
 *
 * <p>The queue is a singleton per-session: {@link QueryEngine} holds one instance
 * and shares it with {@code McpClientManager} via a setter. Non-MCP callers
 * (e.g. task-notification injection from hooks) also use the same instance.
 */
public final class MessageQueueManager {

    private static final int MAX_SDK_EVENT_QUEUE_SIZE = 1000;

    /** Module-level backing store — same lifecycle as the owning session. */
    private final List<QueuedCommand> queue = new CopyOnWriteArrayList<>();

    /** SDK-only lifecycle events; never injected into the model conversation. */
    private final ConcurrentLinkedQueue<SequencedSdkEvent> sdkEvents = new ConcurrentLinkedQueue<>();

    /** Monotonic boundary for drain-before-current-message ordering. */
    private long sdkEventSequence;

    /** Background agents whose final assistant is visible but terminal patch is pending. */
    private final Set<String> pendingTerminalAssistantTaskIds = new HashSet<>();

    /** Change listeners notified on every mutating operation. */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ── Write operations ──────────────────────────────────────────────────

    /**
     * Enqueue a command. Defaults priority to {@link QueuePriority#NEXT} if
     * the record carries {@code null} (the record constructor already guards
     * this, so this is defensive).
     */
    public void enqueue(QueuedCommand cmd) {


        QueuePriority effective = cmd.priority() != null ? cmd.priority() : QueuePriority.NEXT;
        queue.add(new QueuedCommand(cmd.text(), cmd.pastedContents(), cmd.mode(), effective,
            cmd.isMeta(), cmd.originKind(), cmd.skipSlashCommands(), cmd.bridgeOrigin(),
            cmd.preExpansionValue(), cmd.workload(), cmd.agentId(), cmd.orphanedPermission(),
            cmd.taskId(), cmd.modelScheduledOrigin()));
        notifyListeners();
    }


    public void enqueuePendingNotification(QueuedCommand cmd) {


        QueuePriority effective = cmd.priority() != null ? cmd.priority() : QueuePriority.LATER;
        queue.add(new QueuedCommand(cmd.text(), null, cmd.mode(), effective,
            cmd.isMeta(), cmd.originKind(), cmd.skipSlashCommands(),
            cmd.bridgeOrigin(), cmd.preExpansionValue(), cmd.workload(), cmd.agentId(),
            cmd.orphanedPermission(), cmd.taskId(), cmd.modelScheduledOrigin()));
        notifyListeners();
    }

    /**
     * Adds an SDK-only lifecycle event.
     */
    public synchronized void enqueueSdkEvent(SDKMessage event) {
        if (event == null) return;
        while (sdkEvents.size() >= MAX_SDK_EVENT_QUEUE_SIZE) {
            sdkEvents.poll();
        }
        sdkEvents.add(new SequencedSdkEvent(++sdkEventSequence, event));
        if (event instanceof SDKMessage.TaskUpdated updated) {
            pendingTerminalAssistantTaskIds.remove(updated.taskId());
            notifyAll();
        }
    }

    /** Marks a background final assistant as awaiting its terminal task patch. */
    public synchronized void enqueuePendingTerminalAssistant(
            String taskId, SDKMessage.Assistant event) {
        if (taskId != null) pendingTerminalAssistantTaskIds.add(taskId);
        enqueueSdkEvent(event);
    }

    /**
     * Waits briefly for terminal patches corresponding to already-published
     * background final assistants. Called only at a parent assistant output
     * boundary, never by the child query producer.
     */
    public synchronized boolean awaitPendingTerminalAssistants(long timeoutMillis) {
        if (pendingTerminalAssistantTaskIds.isEmpty()) return false;
        long deadline = System.nanoTime()
            + Math.max(0L, timeoutMillis) * 1_000_000L;
        while (!pendingTerminalAssistantTaskIds.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                long millis = Math.max(1L, remaining / 1_000_000L);
                wait(millis);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return true;
    }

    // ── Read operations ───────────────────────────────────────────────────

    /** Returns true if the queue contains at least one command. */
    public boolean hasCommands() { return !queue.isEmpty(); }

    /** Returns the current queue length. */
    public int size() { return queue.size(); }

    /**
     * Immutable snapshot of everything still queued, in insertion order.
     */
    public List<QueuedCommand> snapshot() { return List.copyOf(queue); }

    /**
     * Removes and returns the highest-priority command (FIFO within same
     * priority), or {@code null} if the queue is empty.
     *
     * @param filter optional predicate — only commands passing it are
     *               considered; non-matching commands stay in the queue.
     */
    public synchronized QueuedCommand dequeue(Predicate<QueuedCommand> filter) {
        if (queue.isEmpty()) return null;
        int bestIdx = -1;
        int bestOrder = Integer.MAX_VALUE;
        List<QueuedCommand> snap = new ArrayList<>(queue);
        for (int i = 0; i < snap.size(); i++) {
            QueuedCommand c = snap.get(i);
            if (filter != null && !filter.test(c)) continue;


            QueuePriority cp = c.priority() != null ? c.priority() : QueuePriority.NEXT;
            if (cp.order < bestOrder) {
                bestIdx = i;
                bestOrder = cp.order;
            }
        }
        if (bestIdx < 0) return null;
        QueuedCommand result = snap.get(bestIdx);
        queue.remove(bestIdx);
        notifyListeners();
        return result;
    }

    /** {@link #dequeue(Predicate)} with no filter. */
    public QueuedCommand dequeue() { return dequeue(null); }

    /**
     * Returns the highest-priority command without removing it, or {@code null}.
     */
    public synchronized QueuedCommand peek(Predicate<QueuedCommand> filter) {
        if (queue.isEmpty()) return null;
        QueuedCommand best = null;
        int bestOrder = Integer.MAX_VALUE;
        for (QueuedCommand c : queue) {
            if (filter != null && !filter.test(c)) continue;


            QueuePriority cp = c.priority() != null ? c.priority() : QueuePriority.NEXT;
            if (best == null || cp.order < bestOrder) {
                best = c;
                bestOrder = cp.order;
            }
        }
        return best;
    }

    /** {@link #peek(Predicate)} with no filter. */
    public QueuedCommand peek() { return peek(null); }

    /**
     * Removes and returns all commands matching {@code predicate}.
     * Non-matching commands stay in the queue.
     */
    public synchronized List<QueuedCommand> dequeueAllMatching(Predicate<QueuedCommand> predicate) {
        List<QueuedCommand> matched = new ArrayList<>();
        List<QueuedCommand> snap = new ArrayList<>(queue);
        for (QueuedCommand c : snap) {
            if (predicate.test(c)) matched.add(c);
        }
        if (matched.isEmpty()) return List.of();
        queue.removeAll(matched);
        notifyListeners();
        return matched;
    }

    /** Returns the latest SDK-event sequence without mutating the queue. */
    public synchronized long sdkEventSequence() {
        return sdkEventSequence;
    }

    /** Atomically drains SDK-only lifecycle events in FIFO order. */
    public synchronized List<SDKMessage> drainSdkEvents() {
        return drainSdkEventsThrough(Long.MAX_VALUE);
    }

    /**
     * Atomically drains only events that existed at or before {@code sequence}.
     */
    public synchronized List<SDKMessage> drainSdkEventsThrough(long sequence) {
        if (sdkEvents.isEmpty()) return List.of();
        List<SDKMessage> drained = new ArrayList<>();
        SequencedSdkEvent next;
        while ((next = sdkEvents.peek()) != null && next.sequence() <= sequence) {
            sdkEvents.poll();
            drained.add(next.event());
        }
        return List.copyOf(drained);
    }

    /**
     * Clears all commands. Used by ESC cancellation to discard queued items.
     */
    public synchronized void clear() {
        if (queue.isEmpty()) return;
        queue.clear();
        notifyListeners();
    }

    /**
     * Resets to empty without notifying listeners. Test-cleanup only.
     */
    public void reset() {
        queue.clear();
        sdkEvents.clear();
        synchronized (this) {
            sdkEventSequence = 0L;
        }
    }

    private record SequencedSdkEvent(long sequence, SDKMessage event) {}

    // ── Change listeners ──────────────────────────────────────────────────

    /** Register a callback invoked on every queue mutation. */
    public void addListener(Runnable listener) { listeners.add(listener); }

    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception _) {}
        }
    }
}
