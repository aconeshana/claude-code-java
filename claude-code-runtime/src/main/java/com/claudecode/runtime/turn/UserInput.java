package com.claudecode.runtime.turn;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.MessageContent;
import java.util.List;
import java.util.Map;

/**
 * A user submission handed to {@link TurnEngine#submit(UserInput)} — the headless input value every
 * front-end produces (TUI keyboard, WebUI request body, API call).
 */
public record UserInput(
        String displayText,
        Object queryContent,
        Map<Integer, PastedContent> pasted,
        String permissionMode,
        boolean isSlashCommand,
        boolean isMeta,
        String progressMessage,
        List<String> allowedTools,
        String modelOverride,
        String effortOverride,
        List<MessageContent> precedingUserMessages,
        List<MessageContent> additionalUserMessages,
        boolean suppressInitialAttachments,
        boolean suppressCommandPermissions,
        boolean interactiveStartupPrompt,
        String querySource,
        @Explanation("Session Host endpoint provenance prevents remote input from being mirrored back to the same IM thread")
        String inputOrigin,
        String planContent) {

    public UserInput {
        pasted = pasted == null ? Map.of() : Map.copyOf(pasted);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        precedingUserMessages = precedingUserMessages == null
            ? List.of() : List.copyOf(precedingUserMessages);
        additionalUserMessages = additionalUserMessages == null
            ? List.of() : List.copyOf(additionalUserMessages);
        querySource = StringUtils.isBlank(querySource) ? "user" : querySource;
        inputOrigin = StringUtils.isBlank(inputOrigin) ? "tui" : inputOrigin;
    }

    public static Builder builder(String displayText, Object queryContent) {
        return new Builder(displayText, queryContent);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Convenience factory; {@code null} pasted becomes an empty map. */
    public static UserInput of(String displayText, String queryContent,
                               Map<Integer, PastedContent> pasted, String permissionMode,
                               boolean isSlashCommand) {
        return builder(displayText, queryContent)
            .pasted(pasted)
            .permissionMode(permissionMode)
            .slashCommand(isSlashCommand)
            .build();
    }

    /** Backwards-compatible overload — not a slash command. */
    public static UserInput of(String displayText, String queryContent,
                               Map<Integer, PastedContent> pasted, String permissionMode) {
        return builder(displayText, queryContent)
            .pasted(pasted)
            .permissionMode(permissionMode)
            .build();
    }

    /** Structured prompt-command submission without flattening model-visible blocks. */
    public static UserInput forPrompt(String displayText, Object queryContent,
                                      Map<Integer, PastedContent> pasted, String permissionMode,
                                      String progressMessage, List<String> allowedTools,
                                      String modelOverride, String effortOverride,
                                      List<MessageContent> precedingUserMessages,
                                      boolean suppressInitialAttachments,
                                      boolean suppressCommandPermissions) {
        return builder(displayText, queryContent)
            .pasted(pasted)
            .permissionMode(permissionMode)
            .slashCommand(true)
            .progressMessage(progressMessage)
            .allowedTools(allowedTools)
            .modelOverride(modelOverride)
            .effortOverride(effortOverride)
            .precedingUserMessages(precedingUserMessages)
            .suppressInitialAttachments(suppressInitialAttachments)
            .suppressCommandPermissions(suppressCommandPermissions)
            .build();
    }

    /** Marks the one-shot positional prompt consumed by the interactive REPL. */
    public UserInput asInteractiveStartupPrompt() {
        return toBuilder().interactiveStartupPrompt(true).build();
    }

    /**
     * Carries commands 2..N of a batched queue drain.
     */
    public UserInput withAdditionalUserMessages(List<MessageContent> messages) {
        return toBuilder().additionalUserMessages(messages).build();
    }

    /** Retains queued-command provenance through the front-end-neutral turn boundary. */
    public UserInput withQuerySource(String source) {
        return toBuilder().querySource(source).build();
    }

    /** Marks which endpoint already rendered this input before the turn began. */
    public UserInput withInputOrigin(String origin) {
        return toBuilder().inputOrigin(origin).build();
    }

    public UserInput withPlanContent(String plan) {
        return toBuilder().planContent(plan).build();
    }

    /** Builder with the same neutral defaults as an ordinary TUI submission. */
    public static final class Builder {
        private String displayText;
        private Object queryContent;
        private Map<Integer, PastedContent> pasted = Map.of();
        private String permissionMode;
        private boolean slashCommand;
        private boolean meta;
        private String progressMessage;
        private List<String> allowedTools = List.of();
        private String modelOverride;
        private String effortOverride;
        private List<MessageContent> precedingUserMessages = List.of();
        private List<MessageContent> additionalUserMessages = List.of();
        private boolean suppressInitialAttachments;
        private boolean suppressCommandPermissions;
        private boolean interactiveStartupPrompt;
        private String querySource = "user";
        private String inputOrigin = "tui";
        private String planContent;

        private Builder(String displayText, Object queryContent) {
            this.displayText = displayText;
            this.queryContent = queryContent;
        }

        private Builder(UserInput source) {
            displayText = source.displayText;
            queryContent = source.queryContent;
            pasted = source.pasted;
            permissionMode = source.permissionMode;
            slashCommand = source.isSlashCommand;
            meta = source.isMeta;
            progressMessage = source.progressMessage;
            allowedTools = source.allowedTools;
            modelOverride = source.modelOverride;
            effortOverride = source.effortOverride;
            precedingUserMessages = source.precedingUserMessages;
            additionalUserMessages = source.additionalUserMessages;
            suppressInitialAttachments = source.suppressInitialAttachments;
            suppressCommandPermissions = source.suppressCommandPermissions;
            interactiveStartupPrompt = source.interactiveStartupPrompt;
            querySource = source.querySource;
            inputOrigin = source.inputOrigin;
            planContent = source.planContent;
        }

        public Builder displayText(String value) { displayText = value; return this; }
        public Builder queryContent(Object value) { queryContent = value; return this; }
        public Builder pasted(Map<Integer, PastedContent> value) { pasted = value; return this; }
        public Builder permissionMode(String value) { permissionMode = value; return this; }
        public Builder slashCommand(boolean value) { slashCommand = value; return this; }
        public Builder meta(boolean value) { meta = value; return this; }
        public Builder progressMessage(String value) { progressMessage = value; return this; }
        public Builder allowedTools(List<String> value) { allowedTools = value; return this; }
        public Builder modelOverride(String value) { modelOverride = value; return this; }
        public Builder effortOverride(String value) { effortOverride = value; return this; }
        public Builder precedingUserMessages(List<MessageContent> value) { precedingUserMessages = value; return this; }
        public Builder additionalUserMessages(List<MessageContent> value) { additionalUserMessages = value; return this; }
        public Builder suppressInitialAttachments(boolean value) { suppressInitialAttachments = value; return this; }
        public Builder suppressCommandPermissions(boolean value) { suppressCommandPermissions = value; return this; }
        public Builder interactiveStartupPrompt(boolean value) { interactiveStartupPrompt = value; return this; }
        public Builder querySource(String value) { querySource = value; return this; }
        public Builder inputOrigin(String value) { inputOrigin = value; return this; }
        public Builder planContent(String value) { planContent = value; return this; }

        public UserInput build() {
            return new UserInput(displayText, queryContent, pasted, permissionMode,
                slashCommand, meta, progressMessage, allowedTools, modelOverride,
                effortOverride, precedingUserMessages, additionalUserMessages,
                suppressInitialAttachments, suppressCommandPermissions,
                interactiveStartupPrompt, querySource, inputOrigin, planContent);
        }
    }
}
