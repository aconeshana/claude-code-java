package com.claudecode.tools.tasks.teammate;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdateJsonCodec;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader-side consumer of the in-process teammate mailbox (the {@code team-lead} inbox).
 */
public final class TeammateLeaderCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TeammateLeaderCoordinator.class);

    private static final TeammateLeaderCoordinator INSTANCE = new TeammateLeaderCoordinator();

    private final TeammateMailbox mailbox = TeammateMailbox.instance();
    private volatile TeammateLeaderPermissionResolver permissionResolver;
    /** Supplies the leader-side turn submission (UI/REPL-bound). Null = headless log fallback. */
    private volatile TeammateLeaderTurnSubmitter turnSubmitter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loopThread;


    private static final String TEAMMATE_MESSAGE_TAG = "teammate-message";

    private TeammateLeaderCoordinator() {}

    public static TeammateLeaderCoordinator instance() {
        return INSTANCE;
    }

    /** Inject the leader-side resolver (composition root). Null = headless policy fallback. */
    public void setPermissionResolver(TeammateLeaderPermissionResolver resolver) {
        this.permissionResolver = resolver;
    }

    /** Inject the leader-side turn submitter (composition root). Null = headless log fallback. */
    public void setTurnSubmitter(TeammateLeaderTurnSubmitter submitter) {
        this.turnSubmitter = submitter;
    }

    /** Starts the singleton consumer loop if not already running. Idempotent. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            loopThread = Thread.ofVirtual().name("teammate-leader-coordinator").unstarted(this::loop);
            loopThread.start();
            log.info("Teammate leader coordinator started");
        }
    }

    /** Stops the consumer loop. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (loopThread != null) {
                loopThread.interrupt();
            }
            log.info("Teammate leader coordinator stopped");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Test seam: clears the injected resolver and turn submitter. */
    void resetForTest() {
        permissionResolver = null;
        turnSubmitter = null;
    }

    private void loop() {
        try {
            while (running.get()) {
// receive blocks on BlockingQueue.take and never returns null;
                // interruption surfaces as InterruptedException, handled below.
                Mail mail = mailbox.receive(TeammateMailbox.TEAM_LEAD);
                handle(mail);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(Mail mail) {
        switch (mail.type()) {
            case MailTypes.PERMISSION_REQUEST -> handlePermission(mail);
            case MailTypes.PLAN_APPROVAL_REQUEST -> handlePlanApproval(mail);
            case MailTypes.IDLE_NOTIFICATION -> markTeammateAvailable(mail);
            case MailTypes.USER_MESSAGE -> submitTeammateTurn(mail);
            default -> log.debug("Leader ignoring teammate mail type {}", mail.type());
        }
    }

    private void handlePermission(Mail mail) {
        PermissionAskContext ctx = decodePermissionContext(mail.payload());
        PermissionAskCallback.Result decision = resolvePermission(ctx);
        mailbox.send(Mail.reply(mail, MailTypes.PERMISSION_RESPONSE, TeammateMailbox.TEAM_LEAD,
            InProcessTeammateTask.encodeDecision(decision)));
    }

    private void handlePlanApproval(Mail mail) {
        InProcessTeammateTask.PlanApproval decision = resolvePlanApproval(mail.payload());
        mailbox.send(Mail.reply(mail, MailTypes.PLAN_APPROVAL_RESPONSE, TeammateMailbox.TEAM_LEAD,
            InProcessTeammateTask.encodeApproval(decision)));
    }

    /**
     * Forwards a teammate→leader {@code user_message} into the leader's conversation as a new turn.
     */
    private void submitTeammateTurn(Mail mail) {
        String from = mail.from() == null ? "" : mail.from();
        String payload = mail.payload() == null ? "" : mail.payload();
        String wrapped = "<" + TEAMMATE_MESSAGE_TAG + " teammate_id=\"" + from + "\">\n"
            + payload + "\n</" + TEAMMATE_MESSAGE_TAG + ">";
        if (turnSubmitter != null) {
            turnSubmitter.submitTeammateTurn(wrapped);
        } else {
            log.info("Teammate → leader (no turn submitter wired): {}", mail.payload());
        }
    }

    /**
     * Records that the teammate is idle/available: clears its claim so the next leader dispatch can
     * take it.
     */
    private void markTeammateAvailable(Mail mail) {
        String id = null;
        for (String part : mail.payload().split("\\s+")) {
            if (Strings.CS.startsWith(part, "teammate=")) {
                id = part.substring("teammate=".length());
                break;
            }
        }
        if (id != null) {
            try {
                TaskRegistry.global().store().claim(id, null);
            } catch (Exception _) {
                // best-effort: task store may not hold this teammate
            }
        }
        log.info("Teammate idle/available: {}", mail.payload());
    }

    private PermissionAskContext decodePermissionContext(String payload) {
        try {
            JsonNode n = JsonUtils.getMapper().readTree(payload);
            String toolName = n.has("toolName") ? n.get("toolName").asText() : "?";
            JsonNode input = n.has("input") ? n.get("input") : null;
            String toolUseId = n.has("toolUseId") ? n.get("toolUseId").asText() : null;
            String workerId = n.has("workerId") ? n.get("workerId").asText() : null;
// Forward the rich permission hints so the leader's dialog can show the "why ASK" line,
// the "allow [pattern]" suggestion, and the destructive warning.
            String decisionReasonType = n.has("decisionReasonType") ? n.get("decisionReasonType").asText() : null;
            String decisionReasonDetail = n.has("decisionReasonDetail") ? n.get("decisionReasonDetail").asText() : null;
            String suggestionRuleContent = n.has("suggestionRuleContent") ? n.get("suggestionRuleContent").asText() : null;
            String suggestionLabel = n.has("suggestionLabel") ? n.get("suggestionLabel").asText() : null;
            String destructiveWarning = n.has("destructiveWarning") ? n.get("destructiveWarning").asText() : null;
            String blockedPath = n.has("blockedPath") ? n.get("blockedPath").asText() : null;
            String customMessage = n.has("customMessage") ? n.get("customMessage").asText() : null;
            String toolDescription = n.has("toolDescription") ? n.get("toolDescription").asText() : "";
            return PermissionAskContext.builder(toolName, input)
                .toolUseId(toolUseId)
                .decisionReason(decisionReasonType, decisionReasonDetail)
                .suggestion(suggestionRuleContent, suggestionLabel)
                .worker(workerId, null)
                .destructiveWarning(destructiveWarning)
                .blockedPath(blockedPath)
                .customMessage(customMessage)
                .suggestions(PermissionUpdateJsonCodec.fromJson(n.get("suggestions")))
                .toolDescription(toolDescription)
                .build();
        } catch (Exception _) {
            return PermissionAskContext.simple("?", null, null);
        }
    }

    private PermissionAskCallback.Result resolvePermission(PermissionAskContext ctx) {
        if (permissionResolver != null) {
            return permissionResolver.resolvePermission(ctx);
        }
        // Headless fallback: permissive allow. The composition root overrides this with a
        // real UI-bound resolver when running interactively.
        log.warn("No TeammateLeaderPermissionResolver set; defaulting to allow for tool {} (headless)",
            ctx.toolName());
        return PermissionAskCallback.Result.allow();
    }

    private InProcessTeammateTask.PlanApproval resolvePlanApproval(String summary) {
        if (permissionResolver != null) {
            return permissionResolver.resolvePlanApproval(summary);
        }
        log.warn("No TeammateLeaderPermissionResolver set; defaulting plan approval to allowed (headless)");
        return new InProcessTeammateTask.PlanApproval(true, "", "default");
    }
}
