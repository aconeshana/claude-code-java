package com.claudecode.tools.messaging;

import com.claudecode.tools.tasks.TaskState;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.tasks.teammate.Mail;
import com.claudecode.tools.tasks.teammate.MailTypes;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.agent.AgentContinuationService;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.Tool;

import java.util.Locale;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ValidationResult;


/**
 * SendMessageTool — send a message to another agent.
 */
@BuiltInTool(
    name = "SendMessage",
    shouldDefer = true
)
public class SendMessageTool extends AnnotatedTool<JsonNode, String> {

    private static final Logger LOG = LoggerFactory.getLogger(SendMessageTool.class);
    private static final JsonNode SCHEMA = buildSchema();
    private final AgentContinuationService continuationService;

    public SendMessageTool() {
        this.continuationService = null;
    }

    public SendMessageTool(SubAgentFactory subAgentFactory) {
        this.continuationService = new AgentContinuationService(subAgentFactory);
    }

    /** Advanced constructor for an explicitly scoped continuation service. */
    public SendMessageTool(AgentContinuationService continuationService) {
        this.continuationService = continuationService;
    }

    @Override
    public String description() {

        return ToolTexts.description("SendMessage");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
// Single source of truth: the wire tool catalogue is built from prompt(null), and
// it must equal the permission-UI description (ToolDescriptionWireParityTest).
        return description();
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }



    @Override
    public String searchHint() {
        return "send messages to agent teammates";
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        JsonNode message = input.get("message");
        String to = text(input, "to");
        if (message != null && message.isTextual()) {
            return "to " + to + ": " + message.asText();
        }
        String type = message == null ? "" : message.path("type").asText("");
        return switch (type) {
            case "shutdown_request" -> "shutdown_request to " + to;
            case "shutdown_response" -> "shutdown_response "
                + (message.path("approve").asBoolean(false) ? "approve " : "reject ")
                + message.path("request_id").asText("");
            case "plan_approval_response" -> "plan_approval "
                + (message.path("approve").asBoolean(false) ? "approve " : "reject ")
                + "to " + to;
            default -> "to " + to;
        };
    }


    @Override
    public boolean isReadOnly(JsonNode input) {
        return input != null && input.path("message").isTextual();
    }

    /** In-session mailbox delivery is allowed; unsupported remote targets require a refusal. */
    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
        String to = text(input, "to");
        if (Strings.CS.startsWith(to, "bridge:") ||Strings.CS.startsWith( to, "uds:")) {
            return PermissionDecision.ask();
        }
        return PermissionDecision.allow();
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String to = text(input, "to").trim();
        if (to.isEmpty()) return ValidationResult.invalid("to must not be empty");
        if (Strings.CS.contains(to, "@")) {
            return ValidationResult.invalid(
                "to must be a bare teammate name or \"*\" — there is only one team per session");
        }
        if (Strings.CS.startsWith(to, "bridge:") ||Strings.CS.startsWith( to, "uds:")) {
            return ValidationResult.invalid("cross-session messaging is not supported in this build");
        }
        // text(input, ...) above already dereferenced input unconditionally, so
        // it is non-null here — the message lookup needs no separate null guard.
        JsonNode message = input.get("message");
        if (message == null || message.isNull()) return ValidationResult.invalid("message is required");
        if (message.isTextual()) {
            if (text(input, "summary").trim().isEmpty()) {
                return ValidationResult.invalid("summary is required when message is a string");
            }
            return ValidationResult.valid();
        }
        if (!message.isObject()) return ValidationResult.invalid("message must be a string or object");
        if (Strings.CS.equals("*", to)) {
            return ValidationResult.invalid("structured messages cannot be broadcast (to: \"*\")");
        }
        String type = message.path("type").asText("");
        if (Strings.CS.equals("shutdown_response", type)) {
            if (!Strings.CS.equals("team-lead", to)) {
                return ValidationResult.invalid("shutdown_response must be sent to \"team-lead\"");
            }
            if (!message.path("approve").asBoolean(false)
                    && message.path("reason").asText("").trim().isEmpty()) {
                return ValidationResult.invalid("reason is required when rejecting a shutdown request");
            }
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {


        // we do the same mapping so the existing routing logic is reused unchanged.
        String to = text(input, "to");
        TaskRegistry registry = TaskRegistry.global();
        String resolvedAgentId = registry.resolveAgentId(to);

        JsonNode messageNode = input.has("message") ? input.get("message") : null;
        String message;
        boolean messageIsObject;
        if (messageNode == null || messageNode.isNull()) {
            return error("message is required");
        }
        if (messageNode.isObject()) {
            // Serialize the structured object back to JSON text so the existing
            // protocol-mail routing (buildProtocolMail) can detect its `type`.
            messageIsObject = true;
            message = messageNode.toString();
        } else {
            messageIsObject = false;
            message = messageNode.asText("");
            if (StringUtils.isBlank(message)) {
                return error("message is required");
            }
            if (input instanceof ObjectNode observable) {

                // input before SDK/transcript persistence. Keep the public
                // fields and add the internal routing projection alongside it.
                observable.put("type", "message");
                observable.put("recipient", to);
                observable.put("content", message);
            }
        }

        String summary = text(input, "summary");

        if (StringUtils.isBlank(to)) {
            return error("to must not be empty");
        }


        // containing '@' is never a valid in-session recipient — addresses are
        // bare teammate names or '*'.
        if (Strings.CS.contains(to, "@")) {
            return error("to must be a bare teammate name or \"*\" — there is only one team per session");
        }


        if (!messageIsObject && StringUtils.isBlank(summary)) {
            return error("summary is required when message is a string");
        }


        if (Strings.CS.equals(to, "*")) {
            if (messageIsObject) {
                return error("structured messages cannot be broadcast (to: \"*\")");
            }
            return broadcastMessage(message, summary);
        }


        // "bridge:<session-id>" Remote Control transport, gated behind isAgentSwarmsEnabled(),
        // off by default. It is intentionally unsupported here.
        if (Strings.CS.startsWith(to, "session:")) {
            return error("cross-session messaging is not supported");
        }


        // implementation. Queue the plain-text message for its next child turn
        // instead of treating the opaque agent id as an unresolvable teammate.
        // Structured protocol messages remain team-mailbox-only.
        if (!messageIsObject) {
            String from = context != null && context.agentId() != null
                ? registry.resolveAgentName(context.agentId())
                : "main";
            boolean queued = registry.getAgentHandle(resolvedAgentId).isPresent()
                && registry.queueAgentMessage(resolvedAgentId, message, from);
            if (queued) {
                return basicResult(true,
                    "Message queued for delivery to " + to + " at its next tool round.");
            }
        }

// In-process teammate context: route teammate-bound messages through the in-JVM mailbox.
        TeammateContext tc = TeammateContextHolder.get();
        if (tc != null) {
            return sendToTeammate(tc, to, message, summary);
        }

// Leader (main thread) → teammate dispatch: route the message through the in-JVM mailbox so
// the teammate's run loop receives it as the next turn's prompt.
        String inbox = TeammateMailbox.instance().resolveToInbox(to);
        if (!inbox.equals(TeammateMailbox.TEAM_LEAD) && TeammateMailbox.instance().hasInbox(inbox)) {
            Mail mail = buildProtocolMail(TeammateMailbox.TEAM_LEAD, inbox, message);
            TeammateMailbox.instance().send(mail);

            // task-claiming so two leaders can't grab the same teammate).
            try {
                TaskRegistry.global().store().claim(inbox, TeammateMailbox.TEAM_LEAD);
            } catch (Exception _) {
                // best-effort: a missing task store must not block the message
            }
            return routingResult("Message sent to teammate '" + to + "'",
                TeammateMailbox.TEAM_LEAD, "@" + to, summary, message);
        }

// Terminal and evicted local agents are resumed from their persisted sidechain
// transcript under the same id.
        if (looksLikeAgentId(resolvedAgentId)) {
            if (continuationService == null) {
                return error("agent '" + to + "' cannot be continued — sub-agent "
                    + "continuation is not configured in this runtime.");
            }
            TaskStatus previousStatus = registry.get(resolvedAgentId)
                .map(TaskState::status).orElse(null);
            try {
                AgentContinuationService.ResumeResult resumed =
                    continuationService.resume(resolvedAgentId, message, context);
                String statusText = previousStatus == null
                    ? "had no active task; resumed from transcript"
                    : "was stopped (" + previousStatus.name().toLowerCase(Locale.ROOT)
                        + "); resumed it";
                return basicResult(true,
                    "Agent \"" + to + "\" " + statusText
                        + " in the background with your message. You'll be notified when it finishes. Output: "
                        + resumed.outputFile());
            } catch (AgentContinuationService.UserStoppedAgentException stopped) {
                return basicResult(false, stopped.getMessage());
            } catch (Exception resumeError) {
                String prefix = previousStatus == null
                    ? "is registered but has no transcript to resume. It may have been cleaned up."
                    : "is stopped (" + previousStatus.name().toLowerCase(Locale.ROOT)
                        + ") and could not be resumed:";
                return basicResult(false,
                    "Agent \"" + to + "\" " + prefix + " " + resumeError.getMessage());
            }
        }

        // Catch-all: any `to` that reaches here is neither '*', nor a known
        // live teammate inbox nor a resumable sub-agent id.
        // It cannot be resolved to a recipient, so failing is correct — NOT a
        // silent "Message sent" success (which would falsely imply delivery,
        // the same class of bug as the old broadcast false-success).
        LOG.warn("No recipient found for '{}': message not delivered", to);
        return error("No recipient found for '" + to + "'");
    }


    private String broadcastMessage(String message, String summary) {
        Mail mail = Mail.of(MailTypes.USER_MESSAGE, TeammateMailbox.TEAM_LEAD,
            TeammateMailbox.TEAM_LEAD, message);
        TeammateMailbox.instance().broadcast(mail);
        var recipientNames = TeammateMailbox.instance()
            .broadcastRecipients(TeammateMailbox.TEAM_LEAD);
        int recipients = recipientNames.size();
        ObjectNode data = mapper().createObjectNode();
        data.put("success", true);
        data.put("message", "Message broadcast to " + recipients + " teammate(s)");
        ArrayNode recipientArray = data.putArray("recipients");
        recipientNames.forEach(recipientArray::add);
        ObjectNode routing = data.putObject("routing");
        routing.put("sender", TeammateMailbox.TEAM_LEAD);
        routing.put("target", "@team");
        if (!StringUtils.isBlank(summary)) {
            routing.put("summary", summary);
        }
        routing.put("content", message);
        return data.toString();
    }


    private String routingResult(String message, String sender, String target,
                                 String summary, String content) {
        ObjectNode data = mapper().createObjectNode();
        data.put("success", true);
        data.put("message", message);
        ObjectNode routing = data.putObject("routing");
        routing.put("sender", sender);
        routing.put("target", target);
        if (StringUtils.isNotBlank(summary)) {
            routing.put("summary", summary);
        }
        if (content != null) {
            routing.put("content", content);
        }
        return data.toString();
    }




    private String basicResult(boolean success, String message) {
        return mapper().createObjectNode()
            .put("success", success)
            .put("message", message)
            .toString();
    }

    /**
     * Delivers a message from the currently-active in-process teammate to another
     * agent over the in-JVM {@link TeammateMailbox}. The {@code to} recipient is
     * routed to {@link TeammateMailbox#TEAM_LEAD} when it is {@code "team-lead"}.
     * A structured JSON body carrying a known protocol {@code type}
     * (e.g. {@code shutdown_request}) is delivered as that protocol message;
     * otherwise it is a plain {@code user_message}.
     */
    private String sendToTeammate(TeammateContext tc, String recipient, String content, String summary) {
        String from = tc.agentId();
// Resolve the recipient to its inbox key: "team-lead" and raw task ids pass through; a
// registered teammate name maps to its task id.
        String to = TeammateMailbox.instance().resolveToInbox(recipient);
        Mail mail = buildProtocolMail(from, to, content);
        TeammateMailbox.instance().send(mail);
        return routingResult("Message sent to teammate '" + recipient + "'",
            from, "@" + recipient, summary, content);
    }

    private static String error(String message) {
        return "Error: " + message;
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String output)) return null;
        if (Strings.CS.startsWith(output, "Error:")) {
            return ToolResult.error(output).withContentForm(ToolResultContentForm.BLOCKS);
        }
        try {
            JsonNode data = mapper().readTree(output);
            ToolResult result = ToolResult.success(mapper().createObjectNode()
                .put("success", data.path("success").asBoolean())
                .put("message", data.path("message").asText()).toString());
            ToolResult mapped = data.size() > 2 ? result.withToolUseResult(data) : result;
            return mapped.withContentForm(ToolResultContentForm.BLOCKS);
        } catch (Exception _) {
            return null;
        }
    }

    /** Wraps {@code content} into a {@link Mail}, preserving a known protocol type if present. */
    private static Mail buildProtocolMail(String from, String to, String content) {
        String type = MailTypes.USER_MESSAGE;
        try {
            if (Strings.CS.startsWith(content.trim(), "{")) {
                JsonNode n = JsonUtils.getMapper().readTree(content);
                if (n.has("type") && isKnownMailType(n.get("type").asText())) {
                    type = n.get("type").asText();
                }
            }
        } catch (Exception _) {
            // Not JSON / no type — fall through to a plain user_message.
        }
        return Mail.of(type, from, to, content);
    }

    private static boolean isKnownMailType(String t) {
        return MailTypes.SHUTDOWN_REQUEST.equals(t) || MailTypes.SHUTDOWN_RESPONSE.equals(t)
            || MailTypes.PLAN_APPROVAL_REQUEST.equals(t) || MailTypes.PLAN_APPROVAL_RESPONSE.equals(t)
            || MailTypes.PERMISSION_REQUEST.equals(t) || MailTypes.PERMISSION_RESPONSE.equals(t)
            || MailTypes.USER_MESSAGE.equals(t);
    }


    private static boolean looksLikeAgentId(String to) {
        return to.matches("a([a-zA-Z0-9]+-)?[0-9a-fA-F]{16}");
    }

    private static JsonNode buildSchema() {

        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");

        ObjectNode toProp = properties.putObject("to");
        toProp.put("type", "string");
        toProp.put("description", "Recipient: teammate name");

        ObjectNode summaryProp = properties.putObject("summary");
        summaryProp.put("type", "string");
        summaryProp.put("description",
                "A 5-10 word summary shown as a preview in the UI (required when message is a string)");
        summaryProp.put("maxLength", 200);

        ObjectNode messageProp = properties.putObject("message");
        messageProp.put("type", "string");
        messageProp.put("description", "Plain text message content");

        ArrayNode required = schema.putArray("required");
        required.add("to");
        required.add("message");
        return schema;
    }

    private static String text(JsonNode node, String key) {
        return node != null && node.has(key) && !node.get(key).isNull()
            ? node.get(key).asText("") : "";
    }

}
