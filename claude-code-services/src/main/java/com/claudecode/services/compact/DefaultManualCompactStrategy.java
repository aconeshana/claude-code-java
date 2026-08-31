package com.claudecode.services.compact;

import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.CompactFileReferenceAttachment;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DeferredToolsDeltaAttachment;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.InvokedSkillsAttachment;
import com.claudecode.core.message.McpInstructionsDeltaAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.PlanFileReferenceAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TaskStatusAttachment;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.services.compact.CompactAttachmentStateProvider.AsyncTask;
import com.claudecode.services.compact.CompactAttachmentStateProvider.InvokedSkill;
import com.claudecode.services.compact.CompactAttachmentStateProvider.PlanFile;
import com.claudecode.services.compact.CompactAttachmentStateProvider.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Default {@link ManualCompactStrategy}: full, partial, and.
 */
final class DefaultManualCompactStrategy implements ManualCompactStrategy {


    private static final int POST_COMPACT_MAX_FILES_TO_RESTORE = 5;

    private static final long POST_COMPACT_TOKEN_BUDGET = 50_000;

    private static final long POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;

    private static final long POST_COMPACT_MAX_TOKENS_PER_SKILL = 5_000;

    private static final long POST_COMPACT_SKILLS_TOKEN_BUDGET = 25_000;

    private static final int MAX_RELEASED_MEDIA_CARRIER_RETRIES = 3;

    private static final String PTL_RETRY_MARKER =
        "[earlier conversation truncated for compaction retry]";

    private static final Pattern PTL_TOKEN_COUNTS = Pattern.compile(
        "prompt is too long[^0-9]*(\\d+)\\s*tokens?\\s*>\\s*(\\d+)",
        Pattern.CASE_INSENSITIVE);

    private static final String SKILL_TRUNCATION_MARKER =
        "\n\n[... skill content truncated for compaction; use Read on the skill path if you need the full text]";

    private final TokenEstimator tokenEstimator;

    DefaultManualCompactStrategy(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public MessageCompactor.CompactionResult compact(List<Message> messages, CompactSummarizer compactSummarizer,
                                                       boolean isAutoCompact, String customInstructions,
                                                       CompactAttachmentContext attachmentContext,
                                                       String model) {
        return compactWithPrompt(messages, compactSummarizer, isAutoCompact,
                CompactService.buildCompactPrompt(customInstructions), attachmentContext, model,
                null);
    }

    /**
     * Core compaction body shared by {@link #compact} (full prompt) and {@link #partialCompact}
     * (partial prompt).
     */
    private MessageCompactor.CompactionResult compactWithPrompt(List<Message> messages, CompactSummarizer compactSummarizer,
                                                                  boolean isAutoCompact, String compactPrompt,
                                                                  CompactAttachmentContext attachmentContext,
                                                                  String model,
                                                                  List<Message> attachmentPreservedMessages) {
        if (messages.isEmpty()) {

            throw new CompactException("Not enough messages to compact.");
        }

        if (compactSummarizer == null) {
            throw new CompactException("No CompactSummarizer configured");
        }

        long preCompactTokenCount = tokenEstimator.tokenCountWithEstimation(
            messages, model, DefaultAutoCompactStrategy.charsPerTokenForModel(model));
        long compactStartedNanos = System.nanoTime();


        ReactiveSummary reactive = isAutoCompact
            ? streamReactiveCompactSummary(messages, compactPrompt, compactSummarizer)
            : null;
        CompactSummarizer.SummaryResult result = reactive != null
            ? reactive.summary()
            : streamCompactSummary(new ArrayList<>(messages), compactPrompt, compactSummarizer);
        String summary = result.text();

        if (StringUtils.isBlank(summary)) {

            throw new CompactException(
                "Compaction interrupted · This may be due to network issues — please try again.");
        }

        // Create compact_boundary marker
        String compactType = isAutoCompact ? "auto" : "manual";
        SystemMessage boundaryMarker = CompactService.createCompactBoundaryMarker(
                compactType, preCompactTokenCount);

        // Create summary message — the raw LLM output goes through the
        // session-continuation wrapper (analysis stripped, "This session is
        // being continued..." preamble; auto-compact adds the resume-directly



        UserMessage summaryMessage = CompactService.createCompactSummaryMessage(
                CompactService.buildCompactUserSummaryText(summary, true,
                    attachmentContext != null ? attachmentContext.transcriptPath() : null));

        List<Message> messagesToKeep = reactive != null
            ? clearPreservedAssistantUsage(reactive.messagesToKeep())
            : List.of();

        // Generate post-compact attachments — see the 5 producer methods below.
        List<Message> attachments = buildPostCompactAttachments(
            attachmentContext,
            attachmentPreservedMessages != null
                ? attachmentPreservedMessages : messagesToKeep);
        Usage compactionUsage = result.usage() != null ? result.usage() : Usage.EMPTY;
        List<Message> postPayload = new ArrayList<>();
        postPayload.add(summaryMessage);
        postPayload.addAll(messagesToKeep);
        postPayload.addAll(attachments);
        long postTokens = tokenEstimator.estimatePostCompactTokenCount(postPayload);
        long durationMs = (System.nanoTime() - compactStartedNanos) / 1_000_000L;
        boundaryMarker = CompactService.finalizeCompactBoundaryMetadata(
            boundaryMarker, messages, durationMs, postTokens);
        boundaryMarker = CompactService.annotatePreCompactDiscoveredTools(
            boundaryMarker, messages);
        if (!messagesToKeep.isEmpty()) {
            boundaryMarker = CompactService.annotateBoundaryWithPreservedSegment(
                boundaryMarker, summaryMessage.uuid(), messagesToKeep);
        }

        return new MessageCompactor.CompactionResult(
                boundaryMarker,
                List.of(summaryMessage),
                attachments,
                List.of(),
                messagesToKeep,
                preCompactTokenCount,
                compactionUsage,
                summary
        );
    }

    /** Result of a successful reactive prefix summary. */
    private record ReactiveSummary(CompactSummarizer.SummaryResult summary,
                                   List<Message> messagesToKeep) {}


    private ReactiveSummary streamReactiveCompactSummary(
            List<Message> messages, String compactPrompt, CompactSummarizer compactSummarizer) {
        List<Message> projected = MessageConstants.getMessagesAfterCompactBoundary(messages).stream()
            .filter(message -> !(message instanceof ProgressMessage))
            .toList();
        List<List<Message>> groups = MessageGrouping.groupByApiRound(projected);
        if (groups.size() < 2) {
            throw new CompactException("Not enough API-round groups for reactive compact");
        }

        int groupsToPreserve = 1;
        List<Message> mediaRetryMessages = null;
        int releasedCarrierRetries = 0;
        boolean genericMediaRetry = false;
        boolean sawPromptTooLong = false;
        while (groupsToPreserve < groups.size()) {
            int split = groups.size() - groupsToPreserve;
            List<Message> toSummarize = groups.subList(0, split).stream()
                .flatMap(List::stream)
                .toList();
            if (toSummarize.stream().noneMatch(AssistantMessage.class::isInstance)) {
                if (sawPromptTooLong) {
                    throw new CompactException("Reactive compact exhausted all API-round groups");
                }
                throw new CompactException("Not enough completed API rounds for reactive compact");
            }

            CompactSummarizer.SummaryResult response;
            try {
                response = compactSummarizer.summarizeWithUsage(
                    mediaRetryMessages != null ? mediaRetryMessages : toSummarize,
                    compactPrompt);
            } catch (RuntimeException failure) {
                ReleasedMediaRetry.MediaError mediaError = ReleasedMediaRetry.classify(failure);
                if (mediaError == null) throw failure;
                if (!genericMediaRetry) {
                    List<Message> currentAttempt = mediaRetryMessages != null
                        ? mediaRetryMessages : toSummarize;
                    boolean targeted = mediaError.messageIndex() != null
                        && mediaError.contentIndex() != null;
                    if (targeted || releasedCarrierRetries < MAX_RELEASED_MEDIA_CARRIER_RETRIES) {
                        List<Message> stripped = ReleasedMediaRetry.stripForRetry(
                            currentAttempt, mediaError);
                        if (stripped != currentAttempt) {
                            mediaRetryMessages = stripped;
                            if (!targeted) releasedCarrierRetries++;
                            continue;
                        }
                    }


                    List<Message> stripped = stripImagesFromMessages(toSummarize);
                    if (stripped != toSummarize) {
                        mediaRetryMessages = stripped;
                        genericMediaRetry = true;
                        continue;
                    }
                }
                throw new CompactException("media_unstrippable");
            }
            mediaRetryMessages = null;
            releasedCarrierRetries = 0;
            genericMediaRetry = false;
            if (StringUtils.isBlank(response.text())) {
                throw new CompactException(
                    "summarization produced empty response", response.usage());
            }
            if (!Strings.CS.startsWith(response.text(), CompactService.PROMPT_TOO_LONG_MARKER)) {
                List<Message> keep = groups.subList(split, groups.size()).stream()
                    .flatMap(List::stream)
                    .toList();
                return new ReactiveSummary(response, keep);
            }

            sawPromptTooLong = true;
            groupsToPreserve++;
        }
        throw new CompactException("Reactive compact exhausted all API-round groups");
    }

    private static List<Message> clearPreservedAssistantUsage(List<Message> messages) {
        return messages.stream().map(message -> {
            if (!(message instanceof AssistantMessage assistant) || assistant.message() == null) {
                return message;
            }
            AssistantContent content = assistant.message();
            return new AssistantMessage(
                assistant.uuid(),
                AssistantContent.of(content.id(), content.content(), Usage.EMPTY),
                assistant.isApiErrorMessage(),
                assistant.parentUuidValue(),
                assistant.timestampValue(),
                assistant.attributionSkill(),
                assistant.attributionPlugin(),
                assistant.attributionMcpServer(),
                assistant.attributionMcpTool(),
                assistant.apiError(),
                assistant.error());
        }).toList();
    }


    private static List<Message> stripImagesFromMessages(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        boolean changed = false;
        for (Message msg : messages) {
            if (!(msg instanceof UserMessage um) || um.message() == null || um.message().blocks() == null) {
                result.add(msg);
                continue;
            }
            List<ContentBlock> newBlocks = stripMediaBlocks(um.message().blocks());
            if (newBlocks != um.message().blocks()) {
                changed = true;
                result.add(new UserMessage(
                    um.uuid(), MessageContent.ofBlocks(newBlocks), um.isMeta(), um.isCompactSummary(),
                    um.toolUseResult(), um.origin(), um.parentUuidValue(),
                    um.timestampValue(), um.imagePasteIds(), um.permissionMode(),
                    um.sessionIdValue(), um.sourceToolAssistantUUID(), um.sourceToolUseID(),
                    um.isVirtual(), um.mcpMeta(), um.isVisibleInTranscriptOnly(),
                    um.planContent(), um.summarizeMetadata()));
            } else {
                result.add(msg);
            }
        }
        return changed ? result : messages;
    }

    private static List<ContentBlock> stripMediaBlocks(List<ContentBlock> blocks) {
        boolean changed = false;
        List<ContentBlock> result = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            switch (block) {
                case ImageBlock _ -> {
                    changed = true;
                    result.add(new TextBlock("[image]"));
                }
                case DocumentBlock _ -> {
                    changed = true;
                    result.add(new TextBlock("[document]"));
                }
                case ToolResultBlock tr -> {
                    if (tr.content() == null) {
                        result.add(block);
                        continue;
                    }
                    List<ContentBlock> newContent = stripMediaBlocks(tr.content());
                    if (newContent != tr.content()) {
                        changed = true;
                        result.add(new ToolResultBlock(
                            tr.toolUseId(), newContent, tr.isError(),
                            tr.includeIsErrorField(), tr.preserveContentBlocks()));
                    } else {
                        result.add(block);
                    }
                }
                case null, default -> result.add(block);
            }
        }
        return changed ? result : blocks;
    }

    /**
     * Call the LLM summarizer with prompt-too-long retry logic.
     */
    CompactSummarizer.SummaryResult streamCompactSummary(List<Message> messages, String compactPrompt,
                                CompactSummarizer compactSummarizer) {

        List<Message> current = MessageConstants.getMessagesAfterCompactBoundary(messages);
        int ptlAttempts = 0;

        while (true) {
            CompactSummarizer.SummaryResult response = compactSummarizer.summarizeWithUsage(current, compactPrompt);

            // Null/empty text exits the loop (the caller reports the
            // incomplete-response error) — only the PTL marker retries.

            if (response.text() == null || !Strings.CS.startsWith(response.text(), CompactService.PROMPT_TOO_LONG_MARKER)) {
                return response;
            }

            // Prompt too long — truncate oldest message groups and retry
            ptlAttempts++;
            if (ptlAttempts > MAX_PTL_RETRIES) {
                throw new CompactException(CompactService.ERROR_MESSAGE_PROMPT_TOO_LONG);
            }
            try {
                current = truncateHeadForPTLRetry(current, response.text());
            } catch (CompactException failure) {
                throw new CompactException(
                    CompactService.ERROR_MESSAGE_PROMPT_TOO_LONG,
                    failure.compactionUsage());
            }
        }
    }

    /**
     * Truncate the oldest message group to reduce prompt size for retry.
     * Uses {@link MessageGrouping#groupByApiRound} to identify groups,
     * then removes the first group.
     *
     * @throws CompactException if there are not enough groups to truncate
     */
    List<Message> truncateHeadForPTLRetry(List<Message> messages) {
        List<List<Message>> groups = MessageGrouping.groupByApiRound(messages);

        if (groups.size() <= 1) {
            throw new CompactException(
                    "Cannot truncate further — only one message group remaining");
        }

        // Remove the first (oldest) group
        List<Message> result = new ArrayList<>();
        for (int i = 1; i < groups.size(); i++) {
            result.addAll(groups.get(i));
        }
        return result;
    }

    /**
     * 2.1.197 PTL recovery: strip a prior retry marker, drop enough complete API-round groups to
     * cover the reported token gap (or 20% when the provider does not expose counts), and prepend
     * a synthetic user marker when the retained suffix would otherwise begin with an assistant.
     */
    List<Message> truncateHeadForPTLRetry(List<Message> messages, String promptTooLongResponse) {
        List<Message> input = messages;
        if (!messages.isEmpty() && messages.getFirst() instanceof UserMessage user
                && user.isMeta() && user.message() != null
                && Strings.CS.equals(PTL_RETRY_MARKER, user.message().text())) {
            input = messages.subList(1, messages.size());
        }

        List<List<Message>> groups = MessageGrouping.groupByApiRound(input);
        if (groups.size() < 2) {
            throw new CompactException(
                "Cannot truncate further — only one message group remaining");
        }

        Long tokenGap = promptTooLongTokenGap(promptTooLongResponse);
        int dropCount;
        if (tokenGap != null) {
            long accumulated = 0;
            dropCount = 0;
            for (List<Message> group : groups) {
                accumulated += tokenEstimator.estimateTokenCount(group);
                dropCount++;
                if (accumulated >= tokenGap) break;
            }
        } else {
            dropCount = Math.max(1, Math.floorDiv(groups.size(), 5));
        }
        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) {
            throw new CompactException(
                "Cannot truncate further — only one message group remaining");
        }

        List<Message> result = new ArrayList<>();
        for (int index = dropCount; index < groups.size(); index++) {
            result.addAll(groups.get(index));
        }
        if (!result.isEmpty() && result.getFirst() instanceof AssistantMessage) {
            result.addFirst(MessageFactory.createUserMessage(PTL_RETRY_MARKER, true));
        }
        return result;
    }

    private static Long promptTooLongTokenGap(String response) {
        if (response == null) return null;
        Matcher matcher = PTL_TOKEN_COUNTS.matcher(response);
        if (!matcher.find()) return null;
        try {
            long actual = Long.parseLong(matcher.group(1));
            long limit = Long.parseLong(matcher.group(2));
            long gap = actual - limit;
            return gap > 0 ? gap : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    @Override
    public PartialCompactResult partialCompact(List<Message> messages, int pivotIndex, String direction,
                                                String feedback, String customInstructions,
                                                CompactSummarizer compactSummarizer,
                                                CompactAttachmentContext attachmentContext) {
        if (messages.isEmpty()) {
            throw new CompactException("Not enough messages to compact");
        }
        if (pivotIndex < 0 || pivotIndex >= messages.size()) {
            throw new CompactException("Pivot index out of bounds: " + pivotIndex);
        }

        List<Message> toCompact;
        List<Message> toKeep;

        if (Strings.CS.equals("from", direction)) {
            toCompact = new ArrayList<>(messages.subList(pivotIndex, messages.size()));
            toKeep = new ArrayList<>(messages.subList(0, pivotIndex));
        } else if (Strings.CS.equals("up_to", direction)) {
            toCompact = new ArrayList<>(messages.subList(0, pivotIndex));
            toKeep = new ArrayList<>(messages.subList(pivotIndex, messages.size()));
        } else {
            throw new CompactException("Invalid direction: " + direction + ". Must be 'from' or 'up_to'");
        }

        if (toCompact.isEmpty()) {
            throw new CompactException(Strings.CS.equals("up_to", direction)
                ? "Nothing to summarize before the selected message."
                : "Nothing to summarize after the selected message.");
        }

        // 2.1.197 keeps the prior compact chain for prefix-preserving "from" compacts;
        // removing that boundary/summary would discard all history it represents. "up_to"
        // places the new summary before the kept suffix, so stale boundaries and summaries in
        // that suffix must be removed or the backward boundary scan would select the wrong one.
        List<Message> filteredKeep = Strings.CS.equals("from", direction)
            ? filterProgressMessages(toKeep)
            : filterKeptMessages(toKeep);

        // Compact the target portion
        MessageCompactor.CompactionResult compactionResult = null;
        if (!toCompact.isEmpty() && compactSummarizer != null) {

            String partialPrompt = CompactService.buildPartialCompactPrompt(
                customInstructions, direction);
            List<Message> summaryRequestMessages = Strings.CS.equals("from", direction)
                ? messages : toCompact;
            try {
                compactionResult = compactWithPrompt(
                    summaryRequestMessages, compactSummarizer, false, partialPrompt,
                    attachmentContext, null, filteredKeep);
            } catch (CompactException failure) {
                if (Strings.CS.equals(
                        "Compaction interrupted · This may be due to network issues — please try again.",
                        failure.getMessage())) {
                    throw new CompactException(
                        "Failed to generate conversation summary - response did not contain "
                            + "valid text content",
                        failure.compactionUsage());
                }
                throw failure;
            }

            String logicalParentUuid = Strings.CS.equals("up_to", direction)
                ? lastNonProgressUuid(toCompact)
                : lastNonProgressUuid(filteredKeep);
            SystemMessage contextualBoundary = CompactService.annotatePartialCompactBoundary(
                compactionResult.boundaryMarker(), logicalParentUuid, feedback, toCompact.size());
            contextualBoundary = CompactService.annotatePreCompactDiscoveredTools(
                contextualBoundary, messages);
            List<Message> contextualSummaries = compactionResult.summaryMessages().stream()
                .map(message -> message instanceof UserMessage summary
                    ? CompactService.annotatePartialCompactSummary(
                        summary, toCompact.size(), feedback, direction, !filteredKeep.isEmpty())
                    : message)
                .toList();
            compactionResult = new MessageCompactor.CompactionResult(
                contextualBoundary,
                contextualSummaries,
                compactionResult.attachments(),
                compactionResult.hookResults(),
                compactionResult.messagesToKeep(),
                compactionResult.preCompactTokenCount(),
                compactionResult.compactionUsage(),
                compactionResult.rawSummary());
        }

        // Annotate the boundary with the preserved segment (head/anchor/tail
        // uuids of the kept portion) so the kept messages survive session replay


        if (compactionResult != null) {
            String anchorUuid = Strings.CS.equals("up_to", direction)
                ? compactionResult.summaryMessages().stream().reduce((_, b) -> b)
                    .map(Message::uuid).orElse(compactionResult.boundaryMarker().uuid())
                : compactionResult.boundaryMarker().uuid();
            SystemMessage annotatedBoundary = CompactService.annotateBoundaryWithPreservedSegment(
                compactionResult.boundaryMarker(), anchorUuid, filteredKeep);
            compactionResult = new MessageCompactor.CompactionResult(
                annotatedBoundary,
                compactionResult.summaryMessages(),
                compactionResult.attachments(),
                compactionResult.hookResults(),
                filteredKeep,
                compactionResult.preCompactTokenCount(),
                compactionResult.compactionUsage(),
                compactionResult.rawSummary());
        }

        return new PartialCompactResult(filteredKeep, compactionResult, direction, pivotIndex);
    }

    /**
     * Filter messages to remove progress, compact_boundary, and compact_summary types
     * from the kept portion of a partial compact.
     */
    List<Message> filterKeptMessages(List<Message> messages) {
        return messages.stream()
                .filter(msg -> !CompactService.isFilterableMessage(msg))
                .toList();
    }

    private static List<Message> filterProgressMessages(List<Message> messages) {
        return messages.stream()
            .filter(message -> !(message instanceof ProgressMessage))
            .filter(message -> !(message instanceof SystemMessage system)
                || !Strings.CS.equals("progress", system.subtype()))
            .toList();
    }

    private static String lastNonProgressUuid(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof ProgressMessage) continue;
            if (message instanceof SystemMessage system
                    && Strings.CS.equals("progress", system.subtype())) {
                continue;
            }
            return message.uuid();
        }
        return null;
    }


    List<Message> buildPostCompactAttachments(CompactAttachmentContext ctx) {
        return buildPostCompactAttachments(ctx, List.of());
    }


    List<Message> buildPostCompactAttachments(
            CompactAttachmentContext ctx, List<Message> preservedMessages) {
        if (ctx == null) {
            return List.of();
        }
        Snapshot state = ctx.state();
        List<Message> preserved = preservedMessages == null ? List.of() : preservedMessages;
        List<AttachmentPayload> payloads = new ArrayList<>(
            buildFileAttachments(ctx.fileStateCache(), state.planFile(), preserved));
        payloads.addAll(buildTaskStatusAttachments(state.tasks(), state.agentId()));
        payloads.addAll(buildPlanFileAttachment(state.planFile()));
        payloads.addAll(buildPlanModeAttachment(state));
        payloads.addAll(buildInvokedSkillsAttachment(state.invokedSkills()));
        payloads.addAll(buildDeferredToolsAttachment(ctx.toolNames(), preserved));
        payloads.addAll(buildAgentListingAttachment(ctx.agentListingMessage(), preserved));
        payloads.addAll(buildMcpInstructionsAttachment(ctx.mcpInstructions(), preserved));

        List<Message> attachments = new ArrayList<>();
        for (AttachmentPayload payload : payloads) {
            attachments.add(new AttachmentMessage(UUID.randomUUID().toString(), payload));
        }
        return attachments;
    }


    List<AttachmentPayload> buildMcpInstructionsAttachment(Map<String, String> instructions) {
        return buildMcpInstructionsAttachment(instructions, List.of());
    }


    List<AttachmentPayload> buildMcpInstructionsAttachment(
            Map<String, String> instructions, List<Message> preservedMessages) {
        if (instructions == null || instructions.isEmpty()) return List.of();
        Set<String> announced = announcedMcpServers(preservedMessages);
        Set<String> current = new TreeSet<>(instructions.keySet());
        List<String> names = current.stream().filter(name -> !announced.contains(name)).toList();
        List<String> blocks = names.stream()
            .map(name -> "## " + name + "\n" + instructions.get(name))
            .toList();
        List<String> removed = announced.stream()
            .filter(name -> !current.contains(name)).sorted().toList();
        if (names.isEmpty() && removed.isEmpty()) return List.of();
        return List.of(new McpInstructionsDeltaAttachment(names, blocks, removed));
    }


    List<AttachmentPayload> buildAgentListingAttachment(String fullListing) {
        return buildAgentListingAttachment(fullListing, List.of());
    }


    List<AttachmentPayload> buildAgentListingAttachment(
            String fullListing, List<Message> preservedMessages) {
        if (StringUtils.isBlank(fullListing)) return List.of();
        String skillMarker = "\n\nThe following skills are available for use with the Skill tool:";
        String agentSection = fullListing;
        int skillIndex = agentSection.indexOf(skillMarker);
        if (skillIndex >= 0) agentSection = agentSection.substring(0, skillIndex);

        String header = "Available agent types for the Agent tool:\n";
        if (!Strings.CS.startsWith(agentSection, header)) return List.of();
        String noteMarker = "\n\nWhen you launch multiple agents for independent work,";
        int noteIndex = agentSection.indexOf(noteMarker);
        String linesText = noteIndex >= 0
            ? agentSection.substring(header.length(), noteIndex)
            : agentSection.substring(header.length());
        List<String> lines = linesText.lines()
            .filter(line -> Strings.CS.startsWith(line, "- "))
            .toList();
        if (lines.isEmpty()) return List.of();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : lines) {
            current.put(agentTypeFromPromptLine(line), line);
        }
        Set<String> announced = announcedAgentTypes(preservedMessages);
        List<String> types = current.keySet().stream()
            .filter(type -> !announced.contains(type))
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> addedLines = types.stream().map(current::get).toList();
        List<String> removed = announced.stream()
            .filter(type -> !current.containsKey(type))
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (types.isEmpty() && removed.isEmpty()) return List.of();
        return List.of(new AgentListingDeltaAttachment(
            types, addedLines, removed, announced.isEmpty(), true));
    }


    List<AttachmentPayload> buildDeferredToolsAttachment(
            List<String> toolNames, List<Message> preservedMessages) {
        Set<String> current = toolNames == null
            ? Set.of() : new TreeSet<>(toolNames);
        Set<String> announced = announcedDeferredTools(preservedMessages);
        List<String> added = current.stream()
            .filter(name -> !announced.contains(name)).toList();
        List<String> removed = announced.stream()
            .filter(name -> !current.contains(name)).sorted().toList();
        if (added.isEmpty() && removed.isEmpty()) return List.of();
        return List.of(new DeferredToolsDeltaAttachment(added, added, removed));
    }


    private static Set<String> announcedDeferredTools(List<Message> messages) {
        Set<String> announced = new HashSet<>();
        for (Message message : safeMessages(messages)) {
            if (!(message instanceof AttachmentMessage attachment)
                    || !(attachment.payload() instanceof DeferredToolsDeltaAttachment delta)) {
                continue;
            }
            announced.addAll(delta.addedNames());
            delta.removedNames().forEach(announced::remove);
        }
        return announced;
    }


    private static Set<String> announcedAgentTypes(List<Message> messages) {
        Set<String> announced = new HashSet<>();
        for (Message message : safeMessages(messages)) {
            if (!(message instanceof AttachmentMessage attachment)
                    || !(attachment.payload() instanceof AgentListingDeltaAttachment delta)) {
                continue;
            }
            announced.addAll(delta.addedTypes());
            delta.removedTypes().forEach(announced::remove);
        }
        return announced;
    }


    private static Set<String> announcedMcpServers(List<Message> messages) {
        Set<String> announced = new HashSet<>();
        for (Message message : safeMessages(messages)) {
            if (!(message instanceof AttachmentMessage attachment)
                    || !(attachment.payload() instanceof McpInstructionsDeltaAttachment delta)) {
                continue;
            }
            announced.addAll(delta.addedNames());
            delta.removedNames().forEach(announced::remove);
        }
        return announced;
    }


    private static List<Message> safeMessages(List<Message> messages) {
        return messages == null ? List.of() : messages;
    }

    private static String agentTypeFromPromptLine(String line) {
        int colon = line.indexOf(':', 2);
        return colon > 2 ? line.substring(2, colon).trim() : line.substring(2).trim();
    }


    List<AttachmentPayload> buildFileAttachments(FileStateCache fileStateCache) {
        return buildFileAttachments(fileStateCache, null, List.of());
    }


    List<AttachmentPayload> buildFileAttachments(FileStateCache fileStateCache, PlanFile planFile) {
        return buildFileAttachments(fileStateCache, planFile, List.of());
    }


    List<AttachmentPayload> buildFileAttachments(
            FileStateCache fileStateCache, PlanFile planFile,
            List<Message> preservedMessages) {
        if (fileStateCache == null) {
            return List.of();
        }
        Set<String> preservedReadPaths = collectReadToolFilePaths(preservedMessages);
        Map<String, FileStateCache.FileState> preCompactState = fileStateCache.entries();
        fileStateCache.clear();
        List<Map.Entry<String, FileStateCache.FileState>> sorted = preCompactState.entrySet().stream()
            .filter(entry -> !shouldExcludeFromPostCompactRestore(entry.getKey(), planFile))
            .filter(entry -> !preservedReadPaths.contains(normalizeReadPath(entry.getKey())))
            .sorted(Comparator.<Map.Entry<String, FileStateCache.FileState>>comparingLong(
                e -> e.getValue().timestampMs()).reversed())
            .limit(POST_COMPACT_MAX_FILES_TO_RESTORE)
            .toList();

        List<AttachmentPayload> result = new ArrayList<>();
        long budgetUsed = 0;
        for (Map.Entry<String, FileStateCache.FileState> entry : sorted) {
            String filename = entry.getKey();
            String rawContent;
            long modifiedAt;
            try {
                Path path = Path.of(filename);
                rawContent = Files.readString(path);
                modifiedAt = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException _) {
                continue;
            }
            String modelContent = addCompactLineNumbers(rawContent);
            long tokens = tokenEstimator.estimateTokenCount(modelContent);
            AttachmentPayload payload;
            long payloadTokens;
            if (tokens > POST_COMPACT_MAX_TOKENS_PER_FILE) {
                payload = new CompactFileReferenceAttachment(filename);
                payloadTokens = tokenEstimator.estimateTokenCount(filename);
            } else {
                payload = new FileContentAttachment(filename, rawContent);
                payloadTokens = tokens;
                fileStateCache.set(filename, new FileStateCache.FileState(
                    rawContent, modifiedAt, null, null, false));
            }
            if (budgetUsed + payloadTokens > POST_COMPACT_TOKEN_BUDGET) {
                continue;
            }
            budgetUsed += payloadTokens;
            result.add(payload);
        }
        return result;
    }


    private static Set<String> collectReadToolFilePaths(List<Message> messages) {
        Set<String> unchangedStubIds = new HashSet<>();
        for (Message message : safeMessages(messages)) {
            if (!(message instanceof UserMessage user)
                    || user.message() == null || user.message().blocks() == null) {
                continue;
            }
            for (ContentBlock block : user.message().blocks()) {
                if (block instanceof ToolResultBlock result
                        && startsWithFileUnchangedStub(result.content())) {
                    unchangedStubIds.add(result.toolUseId());
                }
            }
        }

        Set<String> paths = new HashSet<>();
        for (Message message : safeMessages(messages)) {
            if (!(message instanceof AssistantMessage assistant)
                    || assistant.message() == null || assistant.message().content() == null) {
                continue;
            }
            for (ContentBlock block : assistant.message().content()) {
                if (!(block instanceof ToolUseBlock toolUse)
                        || !Strings.CS.equals("Read", toolUse.name())
                        || unchangedStubIds.contains(toolUse.id())
                        || toolUse.input() == null
                        || !toolUse.input().path("file_path").isTextual()) {
                    continue;
                }
                paths.add(normalizeReadPath(toolUse.input().path("file_path").asText()));
            }
        }
        return paths;
    }


    private static boolean startsWithFileUnchangedStub(List<ContentBlock> content) {
        if (content == null) return false;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text) {
                return Strings.CS.startsWith(text.text(), "[file_unchanged]");
            }
        }
        return false;
    }


    private static String normalizeReadPath(String filename) {
        if (StringUtils.isBlank(filename)) return "";
        String expanded = filename;
        if (Strings.CS.startsWith(filename, "~/")) {
            expanded = Path.of(System.getProperty("user.home"), filename.substring(2)).toString();
        }
        try {
            return Path.of(expanded).toAbsolutePath().normalize().toString();
        } catch (RuntimeException _) {
            return expanded;
        }
    }


    private static boolean shouldExcludeFromPostCompactRestore(String filename, PlanFile planFile) {
        if (StringUtils.isBlank(filename)) {
            return true;
        }
        Path normalized = normalizePath(filename);
        if (planFile != null && planFile.path() != null
                && normalized.equals(normalizePath(planFile.path().toString()))) {
            return true;
        }

        Path fileName = normalized.getFileName();
        String basename = fileName == null ? "" : fileName.toString();
        if (Strings.CS.equals("CLAUDE.md", basename) || Strings.CS.equals("CLAUDE.local.md", basename)) {
            return true;
        }
        String normalizedText = normalized.toString().replace('\\', '/');
        return Strings.CS.endsWith(basename, ".md") && Strings.CS.contains(normalizedText, "/.claude/rules/");
    }

    private static Path normalizePath(String filename) {
        try {
            return Path.of(filename).toAbsolutePath().normalize();
        } catch (RuntimeException _) {
            return Path.of(filename.replace('\\', '/')).toAbsolutePath().normalize();
        }
    }


    private static String addCompactLineNumbers(String content) {
        if (StringUtils.isEmpty(content)) return "";
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder numbered = new StringBuilder(content.length() + lines.length * 4);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) numbered.append('\n');
            numbered.append(i + 1).append('\t').append(lines[i]);
        }
        return numbered.toString();
    }


    List<AttachmentPayload> buildTaskStatusAttachments(List<AsyncTask> tasks, String agentId) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<AttachmentPayload> result = new ArrayList<>();
        for (AsyncTask task : tasks) {
            if (!Strings.CS.equals("local_agent", task.type()) || Strings.CS.equals("pending", task.status())) {
                continue;
            }
            if (agentId != null && agentId.equals(task.id())) {
                continue;
            }
            result.add(new TaskStatusAttachment(
                task.id(), task.type(), task.status(), task.description(),
                task.deltaSummary(), task.outputFilePath()));
        }
        return result;
    }


    List<AttachmentPayload> buildPlanFileAttachment(PlanFile planFile) {
        if (planFile == null || !planFile.exists()) {
            return List.of();
        }
        return List.of(new PlanFileReferenceAttachment(
            planFile.path().toString(), planFile.content()));
    }


    List<AttachmentPayload> buildPlanModeAttachment(Snapshot state) {
        if (state == null || !state.planModeActive() || state.planFile() == null) {
            return List.of();
        }
        PlanFile plan = state.planFile();
        PlanCatalogContext catalog = state.planCatalog();
        if (catalog != null && catalog.planId() != null) {
            return List.of(new PlanModeReminderAttachment(
                "full", state.subAgent(), plan.path().toString(), plan.exists(),
                catalog.planId(), catalog.planStatus(), catalog.resumedDraft(),
                catalog.recentPlans()));
        }
        return List.of(new PlanModeReminderAttachment(
            state.subAgent(), plan.path().toString(), plan.exists()));
    }


    List<AttachmentPayload> buildInvokedSkillsAttachment(List<InvokedSkill> invokedSkills) {
        if (invokedSkills == null || invokedSkills.isEmpty()) {
            return List.of();
        }
        List<InvokedSkill> sorted = invokedSkills.stream()
            .sorted(Comparator.comparing(InvokedSkill::invokedAt).reversed())
            .toList();

        List<InvokedSkillsAttachment.InvokedSkillEntry> skills = new ArrayList<>();
        long budgetUsed = 0;
        for (InvokedSkill entry : sorted) {
            String truncated = truncateToTokens(entry.content(), POST_COMPACT_MAX_TOKENS_PER_SKILL);
            long tokens = tokenEstimator.estimateTokenCount(truncated);
            if (budgetUsed + tokens > POST_COMPACT_SKILLS_TOKEN_BUDGET) {
                break;
            }
            budgetUsed += tokens;
            skills.add(new InvokedSkillsAttachment.InvokedSkillEntry(entry.name(), entry.path(), truncated));
        }
        if (skills.isEmpty()) {
            return List.of();
        }
        return List.of(new InvokedSkillsAttachment(skills));
    }

    /**
     * Head-truncates to roughly {@code maxTokens}, appending {@link #SKILL_TRUNCATION_MARKER}.
     */
    String truncateToTokens(String content, long maxTokens) {
        if (content == null) {
            return "";
        }
        if (tokenEstimator.estimateTokenCount(content) <= maxTokens) {
            return content;
        }
        long charBudget = maxTokens * 4 - SKILL_TRUNCATION_MARKER.length();
        int cut = (int) Math.max(0, Math.min(content.length(), charBudget));
        return content.substring(0, cut) + SKILL_TRUNCATION_MARKER;
    }
}
