package com.claudecode.commands.prompt;

import com.claudecode.commands.CommandResult;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.engine.HookDispatcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public record PromptInvocation(
    List<ContentBlock> content,
    String progressMessage,
    List<String> allowedTools,
    String model,
    String effort,
    boolean disableModelInvocation,
    String source,
    String loadedFrom,
    String userFacingName,
    boolean hasUserSpecifiedDescription,
    String whenToUse,
    String version,
    int contentLength,
    boolean isMcp,
    HookDispatcher.InvocationHooks hooks,
    Path skillRoot,
    String context,
    String agent,
    List<String> paths,
    List<MessageContent> precedingUserMessages,
    boolean scalarTextContent,
    boolean suppressInitialAttachments,
    boolean suppressCommandPermissions,
    boolean suppressSkillAttribution,
    boolean suppressLastPrompt) {

    public PromptInvocation {
        content = content == null ? List.of() : List.copyOf(content);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        paths = paths == null ? List.of() : List.copyOf(paths);
        precedingUserMessages = precedingUserMessages == null
            ? List.of() : List.copyOf(precedingUserMessages);
        if (contentLength < 0) contentLength = 0;
    }

    public static Builder builder(List<ContentBlock> content) {
        return new Builder(content);
    }

    public static PromptInvocation text(String text) {
        String value = text == null ? "" : text;
        return builder(List.of(new TextBlock(value)))
            .contentLength(value.length())
            .build();
    }

    /** Text-only compatibility projection for legacy renderers and diagnostics. */
    public String textContent() {
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock(String text1) && text1 != null) {
                parts.add(text1);
            }
        }
        return String.join("\n\n", parts);
    }

    public static final class Builder {
        private final List<ContentBlock> content;
        private String progressMessage;
        private List<String> allowedTools = List.of();
        private String model;
        private String effort;
        private boolean disableModelInvocation;
        private String source;
        private String loadedFrom;
        private String userFacingName;
        private boolean hasUserSpecifiedDescription;
        private String whenToUse;
        private String version;
        private int contentLength;
        private boolean isMcp;
        private HookDispatcher.InvocationHooks hooks;
        private Path skillRoot;
        private String context;
        private String agent;
        private List<String> paths = List.of();
        private List<MessageContent> precedingUserMessages = List.of();
        private boolean scalarTextContent;
        private boolean suppressInitialAttachments;
        private boolean suppressCommandPermissions;
        private boolean suppressSkillAttribution;
        private boolean suppressLastPrompt;

        private Builder(List<ContentBlock> content) {
            this.content = content == null ? List.of() : List.copyOf(content);
        }

        public Builder progressMessage(String value) { progressMessage = value; return this; }
        public Builder allowedTools(List<String> value) { allowedTools = value; return this; }
        public Builder model(String value) { model = value; return this; }
        public Builder effort(String value) { effort = value; return this; }
        public Builder disableModelInvocation(boolean value) { disableModelInvocation = value; return this; }
        public Builder source(String value) { source = value; return this; }
        public Builder loadedFrom(String value) { loadedFrom = value; return this; }
        public Builder userFacingName(String value) { userFacingName = value; return this; }
        public Builder hasUserSpecifiedDescription(boolean value) { hasUserSpecifiedDescription = value; return this; }
        public Builder whenToUse(String value) { whenToUse = value; return this; }
        public Builder version(String value) { version = value; return this; }
        public Builder contentLength(int value) { contentLength = value; return this; }
        public Builder isMcp(boolean value) { isMcp = value; return this; }
        public Builder hooks(HookDispatcher.InvocationHooks value) { hooks = value; return this; }
        public Builder skillRoot(Path value) { skillRoot = value; return this; }
        public Builder context(String value) { context = value; return this; }
        public Builder agent(String value) { agent = value; return this; }
        public Builder paths(List<String> value) { paths = value; return this; }
        public Builder precedingUserMessages(List<MessageContent> value) {
            precedingUserMessages = value;
            return this;
        }
        public Builder scalarTextContent(boolean value) { scalarTextContent = value; return this; }
        public Builder suppressInitialAttachments(boolean value) { suppressInitialAttachments = value; return this; }
        public Builder suppressCommandPermissions(boolean value) { suppressCommandPermissions = value; return this; }
        public Builder suppressSkillAttribution(boolean value) { suppressSkillAttribution = value; return this; }
        public Builder suppressLastPrompt(boolean value) { suppressLastPrompt = value; return this; }

        public PromptInvocation build() {
            return new PromptInvocation(content, progressMessage, allowedTools, model, effort,
                disableModelInvocation, source, loadedFrom, userFacingName,
                hasUserSpecifiedDescription, whenToUse, version, contentLength, isMcp,
                hooks, skillRoot, context, agent, paths, precedingUserMessages,
                scalarTextContent, suppressInitialAttachments, suppressCommandPermissions,
                suppressSkillAttribution, suppressLastPrompt);
        }
    }
}
