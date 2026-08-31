package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CompactFileReferenceAttachment.class, name = "compact_file_reference"),
    @JsonSubTypes.Type(value = FileContentAttachment.class, name = "file"),
    @JsonSubTypes.Type(value = ImageFileAttachment.class, name = "image_file"),
    @JsonSubTypes.Type(value = PlanFileReferenceAttachment.class, name = "plan_file_reference"),
    @JsonSubTypes.Type(value = PlanModeReminderAttachment.class, name = "plan_mode"),
    @JsonSubTypes.Type(value = PlanModeReentryAttachment.class, name = "plan_mode_reentry"),
    @JsonSubTypes.Type(value = AutoModeReminderAttachment.class, name = "auto_mode"),
    @JsonSubTypes.Type(value = InvokedSkillsAttachment.class, name = "invoked_skills"),
    @JsonSubTypes.Type(value = TaskStatusAttachment.class, name = "task_status"),
    @JsonSubTypes.Type(value = NestedMemoryAttachment.class, name = "nested_memory"),
    @JsonSubTypes.Type(value = TextReminderAttachment.class, name = "text_reminder"),
    @JsonSubTypes.Type(value = QueuedCommandAttachment.class, name = "queued_command"),
    @JsonSubTypes.Type(value = EditedFileAttachment.class, name = "edited_text_file"),
    @JsonSubTypes.Type(value = AgentListingDeltaAttachment.class, name = "agent_listing_delta"),
    @JsonSubTypes.Type(value = McpInstructionsDeltaAttachment.class, name = "mcp_instructions_delta"),
    @JsonSubTypes.Type(value = DeferredToolsDeltaAttachment.class, name = "deferred_tools_delta"),
    @JsonSubTypes.Type(value = CompactionReminderAttachment.class, name = "compaction_reminder"),
    @JsonSubTypes.Type(value = ContextEfficiencyAttachment.class, name = "context_efficiency"),
    @JsonSubTypes.Type(value = OutputStyleAttachment.class, name = "output_style"),
    @JsonSubTypes.Type(value = AgentMentionAttachment.class, name = "agent_mention"),
    @JsonSubTypes.Type(value = McpResourceAttachment.class, name = "mcp_resource"),
    @JsonSubTypes.Type(value = TodoReminderAttachment.class, name = "todo_reminder"),
    @JsonSubTypes.Type(value = TaskReminderAttachment.class, name = "task_reminder"),
    @JsonSubTypes.Type(value = PlanModeExitAttachment.class, name = "plan_mode_exit"),
    @JsonSubTypes.Type(value = DynamicSkillAttachment.class, name = "dynamic_skill"),
    @JsonSubTypes.Type(value = SkillListingAttachment.class, name = "skill_listing"),
    @JsonSubTypes.Type(value = TokenUsageAttachment.class, name = "token_usage"),
    @JsonSubTypes.Type(value = BudgetUsdAttachment.class, name = "budget_usd"),
    @JsonSubTypes.Type(value = OutputTokenUsageAttachment.class, name = "output_token_usage"),
    @JsonSubTypes.Type(value = AsyncHookResponseAttachment.class, name = "async_hook_response"),
    @JsonSubTypes.Type(value = CommandPermissionsAttachment.class, name = "command_permissions"),
    @JsonSubTypes.Type(value = GoalStatusAttachment.class, name = "goal_status"),
    @JsonSubTypes.Type(value = HookNonBlockingErrorAttachment.class, name = "hook_non_blocking_error"),
    @JsonSubTypes.Type(value = HookAdditionalContextAttachment.class, name = "hook_additional_context"),
    @JsonSubTypes.Type(value = HookSuccessAttachment.class, name = "hook_success"),
    @JsonSubTypes.Type(value = HookErrorDuringExecutionAttachment.class, name = "hook_error_during_execution"),
    @JsonSubTypes.Type(value = HookSystemMessageAttachment.class, name = "hook_system_message")
})
public sealed interface AttachmentPayload permits
    CompactFileReferenceAttachment, FileContentAttachment, ImageFileAttachment, PlanFileReferenceAttachment,
    PlanModeReminderAttachment, PlanModeReentryAttachment, AutoModeReminderAttachment,
    InvokedSkillsAttachment, TaskStatusAttachment,
    NestedMemoryAttachment, TextReminderAttachment, QueuedCommandAttachment,
    EditedFileAttachment, AgentListingDeltaAttachment, McpInstructionsDeltaAttachment,
    DeferredToolsDeltaAttachment, CompactionReminderAttachment, ContextEfficiencyAttachment,
    OutputStyleAttachment, AgentMentionAttachment, McpResourceAttachment,
    TodoReminderAttachment, TaskReminderAttachment, PlanModeExitAttachment, DynamicSkillAttachment,
    SkillListingAttachment, TokenUsageAttachment, BudgetUsdAttachment,
    OutputTokenUsageAttachment, AsyncHookResponseAttachment, CommandPermissionsAttachment,
    GoalStatusAttachment,
    HookNonBlockingErrorAttachment, HookAdditionalContextAttachment,
    HookSuccessAttachment, HookErrorDuringExecutionAttachment, HookSystemMessageAttachment {
}
