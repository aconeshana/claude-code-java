package com.claudecode.tools.tasks.teammate;

import java.util.Map;
import java.util.List;
import java.util.Collection;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;


public final class TeammateMailbox {

    /** Recipient name the leader (main coordinator) listens on. */
    public static final String TEAM_LEAD = "team-lead";

    private static final TeammateMailbox INSTANCE = new TeammateMailbox();

    private final ConcurrentHashMap<String, BlockingQueue<Mail>> boxes = new ConcurrentHashMap<>();
    /** Maps a teammate's human-readable name to its inbox key (task id). */
    private final ConcurrentHashMap<String, String> nameToInbox = new ConcurrentHashMap<>();

    private TeammateMailbox() {}

    public static TeammateMailbox instance() {
        return INSTANCE;
    }

    /** Enqueues {@code mail} to its recipient's inbox (queue created on first use). */
    public synchronized void send(Mail mail) {
        String recipient = resolveToInbox(mail.to());
        Mail routed = Objects.equals(recipient, mail.to())
            ? mail
            : new Mail(mail.type(), mail.requestId(), mail.from(), recipient, mail.payload());
        // Unbounded queue: add always succeeds (would throw IllegalStateException
        // on a bounded queue, surfacing a misconfiguration instead of dropping mail).
        boxes.computeIfAbsent(recipient, _ -> new LinkedBlockingQueue<>()).add(routed);
    }

    /**
     * Blocks until a message arrives for {@code agentName}. Used by a teammate's
     * mailbox reader thread to wait for control messages (shutdown / plan-approval
     * response) without polling.
     */
    public Mail receive(String agentName) throws InterruptedException {
        return boxes.computeIfAbsent(agentName, _ -> new LinkedBlockingQueue<>()).take();
    }

    /** Non-blocking read of the next queued message for {@code agentName}, or {@code null}. */
    public Mail poll(String agentName) {
        BlockingQueue<Mail> q = boxes.get(agentName);
        return q == null ? null : q.poll();
    }

    /** Drops all pending mail for {@code agentName} (called on teammate shutdown). */
    public void clear(String agentName) {
        BlockingQueue<Mail> q = boxes.get(agentName);
        if (q != null) {
            q.clear();
        }
    }

    /** True if {@code agentName} currently has a live inbox (a running teammate). */
    public boolean hasInbox(String agentName) {
        return agentName != null && boxes.containsKey(agentName);
    }

    /** Drops every inbox (test isolation / process shutdown). */
    public void clearAll() {
        boxes.clear();
        nameToInbox.clear();
    }

    /**
     * Registers a teammate's display {@code agentName} → its inbox key (task id) so peers can address
     * it by name.
     */
    public synchronized void registerName(String agentName, String inboxKey) {
        if (StringUtils.isNotBlank(agentName)) {
            nameToInbox.put(agentName, inboxKey);
            if (Objects.equals(agentName, inboxKey)) return;

            BlockingQueue<Mail> pending = boxes.remove(agentName);
            if (pending == null) return;

            BlockingQueue<Mail> target = boxes.computeIfAbsent(
                inboxKey, _ -> new LinkedBlockingQueue<>());
            Mail queued;
            while ((queued = pending.poll()) != null) {
                target.add(new Mail(
                    queued.type(), queued.requestId(), queued.from(), inboxKey, queued.payload()));
            }
        }
    }

    /** Unregisters a previously registered name (called on teammate shutdown). */
    public void unregisterName(String agentName) {
        if (agentName != null) {
            nameToInbox.remove(agentName);
        }
    }

    /**
     * Resolves a {@code recipient} to the inbox key messages should be delivered
     * to. The leader ({@link #TEAM_LEAD}) and raw task ids pass through unchanged;
     * a registered agent name maps to its task id. Falls back to the recipient
     * itself when unknown (preserving the existing task-id addressing).
     */
    public String resolveToInbox(String recipient) {
        if (recipient == null) {
            return null;
        }
        if (TEAM_LEAD.equals(recipient)) {
            return TEAM_LEAD;
        }
        return nameToInbox.getOrDefault(recipient, recipient);
    }

    /**
     * Delivers {@code mail} to every registered inbox except the sender — used
     * for {@code to == "*"} broadcast. Recipients are snapshotted at call time.
     */
    public void broadcast(Mail mail) {
        for (Map.Entry<String, BlockingQueue<Mail>> e : boxes.entrySet()) {
            if (!e.getKey().equals(mail.from())) {
// Unbounded queue: add always succeeds (see send for rationale).
                e.getValue().add(mail);
            }
        }
    }

    /**
     * Number of inboxes a {@link #broadcast} from {@code sender} would actually reach — every
     * registered inbox except the sender's own.
     */
    public int broadcastRecipientCount(String sender) {
        int count = 0;
        for (String key : boxes.keySet()) {
            if (!key.equals(sender)) {
                count++;
            }
        }
        return count;
    }

    /** Snapshot of recipients a broadcast from {@code sender} reaches. */
    public List<String> broadcastRecipients(String sender) {
        return boxes.keySet().stream()
            .filter(key -> !key.equals(sender))
            .sorted()
            .toList();
    }

    /** Clears a team's member inboxes and removes name mappings pointing to them. */
    public void clearTeam(Collection<String> inboxKeys) {
        if (inboxKeys == null) return;
        for (String key : inboxKeys) {
            if (key != null) {
                clear(key);
                boxes.remove(key);
            }
        }
        nameToInbox.entrySet().removeIf(entry -> inboxKeys.contains(entry.getValue()));
    }
}
