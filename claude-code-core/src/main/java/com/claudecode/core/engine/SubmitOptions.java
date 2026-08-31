package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.CommandPermissionsAttachment;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Options for submitting a message to the QueryEngine.
 *
 * <ul>
 *   <li>{@code QueryEngine#submitMessage} —
 *       one-turn query source, schema, prompt UUID, slash/meta state, and
 *       model/effort overrides, including prompt-command preamble messages.</li>
 *   <li>the extra user
 *       messages a batched queue drain contributes to a single turn.</li>
 * </ul>
 */
public record SubmitOptions(
    String querySource,
    JsonNode jsonSchema,
    Map<Integer, PastedContent> pastedContents,
/**
     * Current permission mode at submit time — stored in UserMessage for rewind restore.
     */
    String permissionMode,
/**
     * When true, skip adding this submit to history.
     */
    boolean fromKeybinding,
/**
     * When true, the submit originated from a slash/prompt/skill command re-submission
     * (SlashCommandDispatcher → executeQuery).
     */
    boolean isSlashCommand,
    /** System-generated prompt hidden from the visible human-input transcript. */
    boolean isMeta,
    /** One-turn model override from a prompt command; never mutates session config. */
    String modelOverride,
    /** One-turn effort override from a prompt command; never mutates session config. */
    String effortOverride,
    /** User-visible slash-command metadata/result messages placed immediately before
     *  the hidden model-facing prompt. */
    List<MessageContent> precedingUserMessages,
/**
     * Commands 2..N of a batched queue drain, materialized as their own user messages after this turn's
     * attachment pass.
     */
    List<MessageContent> additionalUserMessages,
    /** Transcript-only slash-command permission declaration appended after the
     *  command body and argument-derived attachments. */
    CommandPermissionsAttachment commandPermissions,
    boolean suppressInitialAttachments,

    String promptUuid,
    /** Local receive time used when materializing the source user message. */
    Instant promptTimestamp,

    String planContent
) {
    public static final SubmitOptions DEFAULT = builder().build();

    public SubmitOptions {
        querySource = querySource == null ? "user" : querySource;
        pastedContents = pastedContents == null ? Map.of() : Map.copyOf(pastedContents);
        precedingUserMessages = precedingUserMessages == null
            ? List.of() : List.copyOf(precedingUserMessages);
        additionalUserMessages = additionalUserMessages == null
            ? List.of() : List.copyOf(additionalUserMessages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static SubmitOptions of(String querySource) {
        return builder().querySource(querySource).build();
    }

    public static SubmitOptions withSchema(String querySource, JsonNode jsonSchema) {
        return builder().querySource(querySource).jsonSchema(jsonSchema).build();
    }

    public static SubmitOptions withPastedContents(String querySource, Map<Integer, PastedContent> pastedContents) {
        return builder().querySource(querySource).pastedContents(pastedContents).build();
    }

    /** Returns a copy with the given permissionMode. */
    public SubmitOptions withPermissionMode(String mode) {
        return toBuilder().permissionMode(mode).build();
    }

    public SubmitOptions withQuerySource(String source) {
        return toBuilder().querySource(source).build();
    }

    /** Returns a copy flagged as a slash/prompt/skill command re-submission. */
    public SubmitOptions asSlashCommand() {
        return toBuilder().slashCommand(true).build();
    }

    public SubmitOptions asMeta() {
        return toBuilder().meta(true).build();
    }

    /** Returns a copy with one-turn prompt-command model/effort overrides. */
    public SubmitOptions withPromptOverrides(String model, String effort) {
        return toBuilder().modelOverride(model).effortOverride(effort).build();
    }

    public SubmitOptions withPrecedingUserMessages(List<MessageContent> messages) {
        return toBuilder().precedingUserMessages(messages).build();
    }

    public SubmitOptions withAdditionalUserMessages(List<MessageContent> messages) {
        return toBuilder().additionalUserMessages(messages).build();
    }

    public SubmitOptions withCommandPermissions(List<String> allowedTools, String model) {
        return toBuilder()
            .commandPermissions(new CommandPermissionsAttachment(allowedTools, model))
            .build();
    }

    public SubmitOptions withoutInitialAttachments() {
        return toBuilder().suppressInitialAttachments(true).build();
    }

    /** Returns a copy carrying the SDK source identity for replay acknowledgements. */
    public SubmitOptions withPromptIdentity(String uuid, Instant timestamp) {
        return toBuilder().promptIdentity(uuid, timestamp).build();
    }

    public SubmitOptions withPlanContent(String plan) {
        return toBuilder().planContent(plan).build();
    }

    public boolean hasJsonSchema()     { return jsonSchema != null; }
    public boolean hasPastedContents() { return pastedContents != null && !pastedContents.isEmpty(); }

    /** Named construction avoids positional default/null tails as options evolve. */
    public static final class Builder {
        private String querySource = "user";
        private JsonNode jsonSchema;
        private Map<Integer, PastedContent> pastedContents = Map.of();
        private String permissionMode;
        private boolean fromKeybinding;
        private boolean slashCommand;
        private boolean meta;
        private String modelOverride;
        private String effortOverride;
        private List<MessageContent> precedingUserMessages = List.of();
        private List<MessageContent> additionalUserMessages = List.of();
        private CommandPermissionsAttachment commandPermissions;
        private boolean suppressInitialAttachments;
        private String promptUuid;
        private Instant promptTimestamp;
        private String planContent;

        private Builder() {}

        private Builder(SubmitOptions source) {
            querySource = source.querySource;
            jsonSchema = source.jsonSchema;
            pastedContents = source.pastedContents;
            permissionMode = source.permissionMode;
            fromKeybinding = source.fromKeybinding;
            slashCommand = source.isSlashCommand;
            meta = source.isMeta;
            modelOverride = source.modelOverride;
            effortOverride = source.effortOverride;
            precedingUserMessages = source.precedingUserMessages;
            additionalUserMessages = source.additionalUserMessages;
            commandPermissions = source.commandPermissions;
            suppressInitialAttachments = source.suppressInitialAttachments;
            promptUuid = source.promptUuid;
            promptTimestamp = source.promptTimestamp;
            planContent = source.planContent;
        }

        public Builder querySource(String value) { querySource = value; return this; }
        public Builder jsonSchema(JsonNode value) { jsonSchema = value; return this; }
        public Builder pastedContents(Map<Integer, PastedContent> value) {
            pastedContents = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }
        public Builder permissionMode(String value) { permissionMode = value; return this; }
        public Builder fromKeybinding(boolean value) { fromKeybinding = value; return this; }
        public Builder slashCommand(boolean value) { slashCommand = value; return this; }
        public Builder meta(boolean value) { meta = value; return this; }
        public Builder modelOverride(String value) { modelOverride = value; return this; }
        public Builder effortOverride(String value) { effortOverride = value; return this; }
        public Builder precedingUserMessages(List<MessageContent> value) {
            precedingUserMessages = value == null ? List.of() : List.copyOf(value);
            return this;
        }
        public Builder additionalUserMessages(List<MessageContent> value) {
            additionalUserMessages = value == null ? List.of() : List.copyOf(value);
            return this;
        }
        public Builder commandPermissions(CommandPermissionsAttachment value) {
            commandPermissions = value;
            return this;
        }
        public Builder suppressInitialAttachments(boolean value) {
            suppressInitialAttachments = value;
            return this;
        }
        public Builder promptIdentity(String uuid, Instant timestamp) {
            promptUuid = uuid;
            promptTimestamp = timestamp;
            return this;
        }
        public Builder planContent(String value) { planContent = value; return this; }

        public SubmitOptions build() {
            return new SubmitOptions(querySource, jsonSchema,
                pastedContents, permissionMode, fromKeybinding, slashCommand,
                meta, modelOverride, effortOverride, precedingUserMessages,
                additionalUserMessages, commandPermissions,
                suppressInitialAttachments, promptUuid, promptTimestamp, planContent);
        }
    }
}
