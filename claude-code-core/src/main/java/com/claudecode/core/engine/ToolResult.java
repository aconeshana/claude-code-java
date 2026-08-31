package com.claudecode.core.engine;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TextBlock;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a tool.
 */
public record ToolResult(
    List<ContentBlock> content,
    boolean isError,
    String acceptFeedback,
    Object toolUseResult,
    JsonNode structuredOutput,
    List<Message> newMessages,
    ToolContextModifier contextModifier,
    boolean includeIsErrorField,
    Runnable afterResultEmitted,
    Map<String, Object> mcpMeta,
    ToolResultContentForm contentForm,
    List<ContentBlock> userFeedbackBlocks
) {

    /** Backward-compatible canonical constructor before image-bearing permission feedback. */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback,
            Object toolUseResult, JsonNode structuredOutput, List<Message> newMessages,
            ToolContextModifier contextModifier, boolean includeIsErrorField,
            Runnable afterResultEmitted, Map<String, Object> mcpMeta,
            ToolResultContentForm contentForm) {
        this(content, isError, acceptFeedback, toolUseResult, structuredOutput, newMessages,
            contextModifier, includeIsErrorField, afterResultEmitted, mcpMeta, contentForm,
            List.of());
    }

    /** Backward-compatible canonical constructor before content-form tracking. */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback,
            Object toolUseResult, JsonNode structuredOutput, List<Message> newMessages,
            ToolContextModifier contextModifier, boolean includeIsErrorField,
            Runnable afterResultEmitted, Map<String, Object> mcpMeta) {
        this(content, isError, acceptFeedback, toolUseResult, structuredOutput,
            newMessages, contextModifier, includeIsErrorField, afterResultEmitted,
            mcpMeta, ToolResultContentForm.STRING, List.of());
    }

    public ToolResult {
        contentForm = contentForm == null ? ToolResultContentForm.STRING : contentForm;
        userFeedbackBlocks = List.copyOf(
            userFeedbackBlocks == null ? List.of() : userFeedbackBlocks);
    }

    /** Backward-compatible canonical constructor before post-emit callbacks. */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback,
            Object toolUseResult, JsonNode structuredOutput, List<Message> newMessages,
            ToolContextModifier contextModifier, boolean includeIsErrorField) {
        this(content, isError, acceptFeedback, toolUseResult, structuredOutput,
            newMessages, contextModifier, includeIsErrorField, null, null);
    }

    /** Backward-compatible canonical constructor before explicit false-error support. */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback,
            Object toolUseResult, JsonNode structuredOutput, List<Message> newMessages,
            ToolContextModifier contextModifier) {
        this(content, isError, acceptFeedback, toolUseResult, structuredOutput,
            newMessages, contextModifier, isError, null, null);
    }

    /** Backward-compatible 4-arg constructor (pre-structuredOutput/newMessages). Delegates with nulls. */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback, Object toolUseResult) {
        this(content, isError, acceptFeedback, toolUseResult, null, null, null, isError);
    }

    /** Backward-compatible 6-arg constructor (pre-contextModifier). Delegates with null modifier. */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback,
            Object toolUseResult, JsonNode structuredOutput, List<Message> newMessages) {
        this(content, isError, acceptFeedback, toolUseResult, structuredOutput, newMessages, null);
    }

    /** Backward-compatible 3-arg constructor (pre-acceptFeedback/structuredOutput/newMessages). */
    public ToolResult(List<ContentBlock> content, boolean isError, String acceptFeedback) {
        this(content, isError, acceptFeedback, null, null, null, null);
    }

    /** Backward-compatible 2-arg constructor (pre-acceptFeedback/structuredOutput/newMessages). */
    public ToolResult(List<ContentBlock> content, boolean isError) {
        this(content, isError, null, null, null, null, null);
    }

    /**
     * Creates a successful text result.
     */
    public static ToolResult success(String text) {
        return new ToolResult(List.of(new TextBlock(text)), false, null, null, null, null, null, false);
    }

    /**
     * Creates an error result.
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(List.of(new TextBlock(errorMessage)), true, null, null, null, null, null, true);
    }

    /** Attach acceptFeedback to a successful result. */
    public ToolResult withAcceptFeedback(String feedback) {
        return new ToolResult(this.content, this.isError, feedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Attach non-text permission feedback blocks beside the tool_result block. */
    public ToolResult withUserFeedbackBlocks(List<ContentBlock> blocks) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            blocks);
    }

    /** Attach a structured toolUseResult payload, preserving all other fields. */
    public ToolResult withToolUseResult(Object toolUseResult) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Attach a generic {@code structured_output} payload, preserving all other fields. */
    public ToolResult withStructuredOutput(JsonNode structuredOutput) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Attach injected conversation messages ({@code newMessages}), preserving all other fields. */
    public ToolResult withNewMessages(List<Message> newMessages) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Attach a context modifier ({@code contextModifier}) applied to subsequent turns. */
    public ToolResult withContextModifier(ToolContextModifier contextModifier) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Preserve an explicit {@code is_error:false} for tool-specific wire contracts. */
    public ToolResult withExplicitIsErrorField() {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            true, this.afterResultEmitted, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Run {@code callback} immediately after the SDK tool_result user is emitted. */
    public ToolResult withAfterResultEmitted(Runnable callback) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, callback, this.mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Attach MCP protocol metadata to the persisted user-message envelope. */
    public ToolResult withMcpMeta(Map<String, Object> mcpMeta) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, mcpMeta, this.contentForm,
            this.userFeedbackBlocks);
    }

    /** Preserve an array-valued mapped result even when it contains one block. */
    public ToolResult withContentForm(ToolResultContentForm contentForm) {
        return new ToolResult(this.content, this.isError, this.acceptFeedback, this.toolUseResult,
            this.structuredOutput, this.newMessages, this.contextModifier,
            this.includeIsErrorField, this.afterResultEmitted, this.mcpMeta, contentForm,
            this.userFeedbackBlocks);
    }
}
