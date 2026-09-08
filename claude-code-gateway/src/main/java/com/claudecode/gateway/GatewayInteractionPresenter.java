package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionResolution;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

/**
 * The gateway's remote presenter for permission asks and user questions.
 *
 * <p>A tool waiting at ASK blocks inside the shared {@code
 * InteractionCoordinator.request} call; the TUI dialog and every connected
 * web client are two observers of the same pending ask — first responder
 * wins, matching the {@code FIRST_RESPONDER} policy of {@code
 * InteractionFeatures.PERMISSION}. This presenter projects the ask into the
 * mirror stream ({@code permission.asked}) and the resolution back ({
 * {@code permission.resolved}), so a web client renders the ask and may
 * answer it through {@code POST /api/permissions/respond}.
 *
 * <p>Projection shape follows the local alignment spec: the ask frame carries
 * the interaction {@code request_id} (the respond endpoint's routing key,
 * not the tool use id), the tool name, the raw input, and the human-facing
 * reason metadata.
 */
@Explanation("Remote web observer of the shared permission ask coordinator")
final class GatewayInteractionPresenter implements InteractionPresenter<
        PermissionAskContext, PermissionAskCallback.Result> {

    private final MirrorHub mirror;

    GatewayInteractionPresenter(MirrorHub mirror) {
        this.mirror = mirror;
    }

    @Override public InteractionEndpoint endpoint() {
        return InteractionEndpoint.REMOTE;
    }

    @Override public InteractionSupport support() {
        return InteractionSupport.SUPPORTED;
    }

    @Override public boolean available(String sessionId) {
        // The ask reaches every connected client through the shared journal
        // ring, so any session id the coordinator names is presentable.
        return !StringUtils.isBlank(sessionId);
    }

    @Override public void present(
            InteractionRequest<PermissionAskContext, PermissionAskCallback.Result> request) {
        PermissionAskContext context = request.payload();
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("request_id", request.descriptor().id());
        payload.put("tool", context.toolName());
        if (context.toolUseId() != null) payload.put("tool_use_id", context.toolUseId());
        if (context.input() != null) payload.set("input", context.input());
        putIfNotBlank(payload, "decision_reason_type", context.decisionReasonType());
        putIfNotBlank(payload, "decision_reason_detail", context.decisionReasonDetail());
        putIfNotBlank(payload, "suggestion_rule_content", context.suggestionRuleContent());
        putIfNotBlank(payload, "suggestion_label", context.suggestionLabel());
        putIfNotBlank(payload, "destructive_warning", context.destructiveWarning());
        putIfNotBlank(payload, "blocked_path", context.blockedPath());
        putIfNotBlank(payload, "custom_message", context.customMessage());
        putIfNotBlank(payload, "tool_description", context.toolDescription());
        putQuestions(payload, context.input());
        mirror.publishPermissionAsked(request.descriptor().sessionId(), payload);
    }

    /**
     * Projects an AskUserQuestion input's question definitions, so a web client
     * renders the same choices the TUI dialog does. Non-AskUserQuestion tools
     * carry no {@code questions} array and the field stays absent.
     */
    private static void putQuestions(ObjectNode payload, JsonNode input) {
        JsonNode raw = input == null ? null : input.get("questions");
        if (raw == null || !raw.isArray() || raw.isEmpty()) return;
        ArrayNode questions = payload.putArray("questions");
        for (JsonNode question : raw) {
            if (!question.isObject() || !question.path("question").isTextual()) continue;
            ObjectNode mapped = questions.addObject();
            mapped.put("question", question.path("question").asText());
            if (question.path("header").isTextual()) {
                mapped.put("header", question.path("header").asText());
            }
            mapped.put("multi_select",
                question.path("multiSelect").asBoolean(
                    question.path("multi_select").asBoolean(false)));
            ArrayNode options = mapped.putArray("options");
            JsonNode rawOptions = question.get("options");
            if (rawOptions == null || !rawOptions.isArray()) continue;
            for (JsonNode option : rawOptions) {
                if (!option.isObject() || !option.path("label").isTextual()) continue;
                ObjectNode mappedOption = options.addObject();
                mappedOption.put("label", option.path("label").asText());
                if (option.path("description").isTextual()) {
                    mappedOption.put("description", option.path("description").asText());
                }
            }
        }
        if (questions.isEmpty()) payload.remove("questions");
    }

    @Override public void resolved(
            InteractionResolution<PermissionAskCallback.Result> resolution) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("request_id", resolution.descriptor().id());
        payload.put("resolution", resolution.result().allowed() ? "allowed" : "denied");
        payload.put("origin", resolution.origin() == InteractionEndpoint.LOCAL
            ? "tui" : "web");
        if (resolution.result().feedback() != null) {
            payload.put("feedback", resolution.result().feedback());
        }
        mirror.publishPermissionResolved(resolution.descriptor().sessionId(), payload);
    }

    private static void putIfNotBlank(ObjectNode payload, String field, String value) {
        if (StringUtils.isNotBlank(value)) payload.put(field, value);
    }
}
