package com.claudecode.services.compact;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.MessageCompactor.CompactionResult;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.message.*;
import com.claudecode.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompactService} — microcompact, autocompact, and full compaction.
 */
class CompactServiceTest {

    private CompactService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new CompactService();
    }

    // --- Helper methods ---

    private AssistantMessage assistantWithToolUse(String toolUseId, String toolName) {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "/tmp/test.txt");
        ToolUseBlock toolUse = new ToolUseBlock(toolUseId, toolName, input);
        AssistantContent content = AssistantContent.of(List.of(toolUse));
        return new AssistantMessage("asst-" + toolUseId, content);
    }

    private AssistantMessage assistantWithId(String uuid, String apiId, String text) {
        TextBlock tb = new TextBlock(text);
        AssistantContent content = AssistantContent.of(apiId, List.of(tb));
        return new AssistantMessage(uuid, content);
    }

    private UserMessage userWithToolResult(String toolUseId, String text) {
        TextBlock textBlock = new TextBlock(text);
        ToolResultBlock resultBlock = new ToolResultBlock(toolUseId, List.of(textBlock), false);
        MessageContent mc = MessageContent.ofBlocks(List.of(resultBlock));
        return new UserMessage("user-" + toolUseId, mc, false, false, null,
                MessageOrigin.TOOL_RESULT, null, Instant.now(), null, null);
    }

    private UserMessage simpleUserMessage(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    private AssistantMessage simpleAssistantMessage(String uuid, String text) {
        return new AssistantMessage(uuid, AssistantContent.of(List.of(new TextBlock(text))));
    }

    private String longString(int length) {
        return "x".repeat(length);
    }

    // ========== MicroCompact Tests ==========

    @Nested
    class MicroCompactTests {

        @Test
        void collectsCompactableToolUseIds() {
            AssistantMessage readMsg = assistantWithToolUse("tu-1", "Read");
            AssistantMessage bashMsg = assistantWithToolUse("tu-2", "Bash");
            AssistantMessage grepMsg = assistantWithToolUse("tu-3", "Grep");

            List<Message> messages = List.of(readMsg, bashMsg, grepMsg);
            Set<String> ids = service.collectCompactableToolIds(messages);

            assertEquals(Set.of("tu-1", "tu-2", "tu-3"), ids);
        }

        @Test
        void ignoresNonCompactableTools() {
            AssistantMessage agentMsg = assistantWithToolUse("tu-agent", "AgentTool");
            AssistantMessage mcpMsg = assistantWithToolUse("tu-mcp", "MCPTool");

            List<Message> messages = List.of(agentMsg, mcpMsg);
            Set<String> ids = service.collectCompactableToolIds(messages);

            assertTrue(ids.isEmpty());
        }

        @Test
        void collectsAllSixCompactableTools() {
            List<Message> messages = List.of(
                    assistantWithToolUse("tu-r", "Read"),
                    assistantWithToolUse("tu-b", "Bash"),
                    assistantWithToolUse("tu-grep", "Grep"),
                    assistantWithToolUse("tu-glob", "Glob"),
                    assistantWithToolUse("tu-e", "Edit"),
                    assistantWithToolUse("tu-w", "Write")
            );
            Set<String> ids = service.collectCompactableToolIds(messages);
            assertEquals(6, ids.size());
        }

        @Test
        void legacyTruncationRemoved_preservesLongToolResult() {
// Long compactable-tool results must pass through unchanged rather
// than being truncated by the legacy per-turn limit.
            String longText = longString(15000);
            AssistantMessage asst = assistantWithToolUse("tu-1", "Read");
            UserMessage user = userWithToolResult("tu-1", longText);

            MessageCompactor.MicrocompactResult result = service.microcompactMessages(List.of(asst, user));

            assertEquals(2, result.messages().size());
            UserMessage resultUser = (UserMessage) result.messages().get(1);
            ToolResultBlock tr = (ToolResultBlock) resultUser.message().blocks().getFirst();
            TextBlock tb = (TextBlock) tr.content().getFirst();

            assertEquals(longText, tb.text());
            assertFalse(Strings.CS.contains(tb.text(), "[truncated,"),
                "legacy per-turn truncation must be gone");
        }

        @Test
        void preservesShortToolResultContent() {
            String shortText = "File contents here";
            AssistantMessage asst = assistantWithToolUse("tu-1", "Read");
            UserMessage user = userWithToolResult("tu-1", shortText);

            MessageCompactor.MicrocompactResult result = service.microcompactMessages(List.of(asst, user));

            UserMessage resultUser = (UserMessage) result.messages().get(1);
            ToolResultBlock tr = (ToolResultBlock) resultUser.message().blocks().getFirst();
            TextBlock tb = (TextBlock) tr.content().getFirst();

            assertEquals(shortText, tb.text());
        }

        @Test
        void preservesContentAtOldThresholdBoundary() {
            // The legacy 10K truncation threshold no longer applies; content of any
            // length is preserved by this (1-arg, no live-context) entry point.
            String exactText = longString(10_000);
            AssistantMessage asst = assistantWithToolUse("tu-1", "Read");
            UserMessage user = userWithToolResult("tu-1", exactText);

            MessageCompactor.MicrocompactResult result = service.microcompactMessages(List.of(asst, user));

            UserMessage resultUser = (UserMessage) result.messages().get(1);
            ToolResultBlock tr = (ToolResultBlock) resultUser.message().blocks().getFirst();
            TextBlock tb = (TextBlock) tr.content().getFirst();

            assertEquals(exactText, tb.text());
        }

        @Test
        void preservesNonCompactableToolResults() {
            String longText = longString(15000);
            AssistantMessage asst = assistantWithToolUse("tu-agent", "AgentTool");
            UserMessage user = userWithToolResult("tu-agent", longText);

            MessageCompactor.MicrocompactResult result = service.microcompactMessages(List.of(asst, user));

            UserMessage resultUser = (UserMessage) result.messages().get(1);
            ToolResultBlock tr = (ToolResultBlock) resultUser.message().blocks().getFirst();
            TextBlock tb = (TextBlock) tr.content().getFirst();

            assertEquals(longText, tb.text());
        }

        @Test
        void handlesMixedCompactableAndNonCompactable() {
            // With legacy truncation removed, both compactable (Read) and
            // non-compactable (AgentTool) results pass through unchanged.
            String longText = longString(15000);
            AssistantMessage readAsst = assistantWithToolUse("tu-read", "Read");
            AssistantMessage agentAsst = assistantWithToolUse("tu-agent", "AgentTool");
            UserMessage readUser = userWithToolResult("tu-read", longText);
            UserMessage agentUser = userWithToolResult("tu-agent", longText);

            MessageCompactor.MicrocompactResult result = service.microcompactMessages(
                    List.of(readAsst, readUser, agentAsst, agentUser));

            UserMessage readResult = (UserMessage) result.messages().get(1);
            ToolResultBlock readTr = (ToolResultBlock) readResult.message().blocks().getFirst();
            TextBlock readTb = (TextBlock) readTr.content().getFirst();
            assertEquals(longText, readTb.text());

            UserMessage agentResult = (UserMessage) result.messages().get(3);
            ToolResultBlock agentTr = (ToolResultBlock) agentResult.message().blocks().getFirst();
            TextBlock agentTb = (TextBlock) agentTr.content().getFirst();
            assertEquals(longText, agentTb.text());
        }

        @Test
        void handlesEmptyMessageList() {
            MessageCompactor.MicrocompactResult result = service.microcompactMessages(List.of());
            assertTrue(result.messages().isEmpty());
        }

        @Test
        void handlesMessagesWithNoToolUse() {
            UserMessage textMsg = new UserMessage("u1",
                    MessageContent.ofText("Hello, how are you?"));
            AssistantMessage asstMsg = new AssistantMessage("a1",
                    AssistantContent.of(List.of(new TextBlock("I'm fine!"))));

            MessageCompactor.MicrocompactResult result = service.microcompactMessages(List.of(textMsg, asstMsg));
            assertEquals(2, result.messages().size());
        }
    }

    // ========== AutoCompact Tests ==========

    @Nested
    class AutoCompactTests {

        @Test
        void shouldAutoCompactWhenTokensExceedThreshold() {
            // Effective window = 200000 - 20000 = 180000; legacy threshold = 167000.
            String bigText = "x".repeat(680_000);
            UserMessage msg = new UserMessage("u1", MessageContent.ofText(bigText));

            assertTrue(service.shouldAutoCompact(List.of(msg), "claude-sonnet-4-6", "user"));
        }

        @Test
        void shouldNotAutoCompactWhenTokensBelowThreshold() {
            UserMessage msg = simpleUserMessage("u1", "Hello world");
            assertFalse(service.shouldAutoCompact(List.of(msg), "claude-sonnet-4-6", "user"));
        }

        @Test
        void shouldAutoCompactFromLatestApiReportedContextUsage() {
            AssistantMessage measured = new AssistantMessage("a1",
                AssistantContent.of("msg-api-1", List.of(new TextBlock("OK")),
                    new Usage(170_000, 1, 0, 0)));

            assertTrue(service.shouldAutoCompact(
                List.of(measured), "claude-sonnet-4-6", "user"),
                "2.1.197 thresholds use the latest API context usage, not just visible message text");
        }

        @Test
        void shouldNotAutoCompactWhenQuerySourceIsCompact() {
            String bigText = "x".repeat(680_000);
            UserMessage msg = new UserMessage("u1", MessageContent.ofText(bigText));

            assertFalse(service.shouldAutoCompact(List.of(msg), "claude-sonnet-4-6", "compact"));
        }

        @Test
        void shouldNotAutoCompactWhenQuerySourceIsSessionMemory() {
            String bigText = "x".repeat(680_000);
            UserMessage msg = new UserMessage("u1", MessageContent.ofText(bigText));

            assertFalse(service.shouldAutoCompact(List.of(msg), "claude-sonnet-4-6", "session_memory"));
        }

        @Test
        void shouldNotAutoCompactWhenDisabled() {
            service.setAutoCompactEnabled(false);
            String bigText = "x".repeat(680_000);
            UserMessage msg = new UserMessage("u1", MessageContent.ofText(bigText));

            assertFalse(service.shouldAutoCompact(List.of(msg), "claude-sonnet-4-6", "user"));
        }

        @Test
        void shouldUseOfficialDefaultWindowForUnknownModel() {
            String bigText = "x".repeat(680_000);
            UserMessage msg = new UserMessage("u1", MessageContent.ofText(bigText));

            assertTrue(service.shouldAutoCompact(List.of(msg), "unknown-model", "user"));
        }

        @Test
        void shouldUseOfficialDefaultWindowForMissingModel() {
            String bigText = "x".repeat(680_000);
            UserMessage msg = new UserMessage("u1", MessageContent.ofText(bigText));

            assertTrue(service.shouldAutoCompact(List.of(msg), null, "user"));
        }
    }

    // ========== Full Compaction Tests ==========

    @Nested
    class FullCompactionTests {

        @Test
        void compactConversationWithNoOpSummarizer() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);

            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = simpleAssistantMessage("a1", "Hi there");

            CompactionResult result = svc.compactConversation(List.of(u1, a1), false, null);

            assertNotNull(result.boundaryMarker());
            assertEquals("compact_boundary", result.boundaryMarker().subtype());
            assertEquals("manual", result.boundaryMarker().compactMetadata().trigger());
            assertFalse(result.summaryMessages().isEmpty());
            assertTrue(result.attachments().isEmpty());
            assertTrue(result.preCompactTokenCount() > 0);
        }

        @Test
        void compactSummaryIncludesReplayableTranscriptPath() {
            CompactSummarizer noOp = (_, _) -> "summary body";
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);
            svc.setSessionIdentity(SessionIdentity.of("session-197"));

            CompactionResult result = svc.compactConversation(
                List.of(simpleUserMessage("u1", "hello")), false, null);

            UserMessage summary = (UserMessage) result.summaryMessages().getFirst();
            String expectedPath = new SessionManager(System.getProperty("user.dir"))
                .getSessionFile("session-197").toString();
            assertTrue(Strings.CS.contains(summary.message().text(), expectedPath),
                "post-compact summary must point to the persisted JSONL for exact-detail recovery");
        }

        @Test
        void compactConversationAutoType() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);

            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = assistantWithId("a1", "msg-1", "Hi there");
            UserMessage u2 = simpleUserMessage("u2", "Continue");
            AssistantMessage a2 = assistantWithId("a2", "msg-2", "Continuing");

            CompactionResult result = svc.compactConversation(
                List.of(u1, a1, u2, a2), true, null, "claude-sonnet-4-6");

            assertEquals("auto", result.boundaryMarker().compactMetadata().trigger());
            assertEquals(1, result.messagesToKeep().size());
            AssistantMessage preserved = (AssistantMessage) result.messagesToKeep().getFirst();
            assertEquals("a2", preserved.uuid());
            assertEquals(a2.message().content(), preserved.message().content());
            assertEquals(Usage.EMPTY, preserved.message().usage());
        }

        @Test
        void compactConversationWithExplicitSummarizer() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            // Service has no summarizer, but we pass one explicitly
            CompactService svc = new CompactService(TokenEstimator.getInstance(), null, true);

            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = simpleAssistantMessage("a1", "Hi there");

            CompactionResult result = svc.compactConversation(List.of(u1, a1), noOp, false);

            assertNotNull(result.boundaryMarker());
            assertFalse(result.summaryMessages().isEmpty());
        }

        @Test
        void compactConversationThrowsOnEmptyMessages() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);

            assertThrows(CompactException.class,
                    () -> svc.compactConversation(List.of(), false, null));
        }

        @Test
        void compactConversationThrowsWithNoSummarizer() {
            CompactService svc = new CompactService(TokenEstimator.getInstance(), null, true);

            UserMessage u1 = simpleUserMessage("u1", "Hello");
            assertThrows(CompactException.class,
                    () -> svc.compactConversation(List.of(u1), false, null));
        }

        @Test
        void failedSummaryDoesNotReadPostCompactAttachmentState() {
            CompactService svc = new CompactService(
                TokenEstimator.getInstance(), (_, _) -> "", true);
            AtomicInteger snapshots = new AtomicInteger();
            svc.setAttachmentStateProvider((_, agentId, subAgent) -> {
                snapshots.incrementAndGet();
                return CompactAttachmentStateProvider.Snapshot.empty(agentId, subAgent);
            });

            assertThrows(CompactException.class,
                () -> svc.compactConversation(
                    List.of(simpleUserMessage("u1", "Hello")), false, null));

            assertEquals(0, snapshots.get(),
                "2.1.197 reads plan/task/skill state only after summary generation succeeds");
        }

        @Test
        void autoCompactWithoutSummarizerPassesThrough() {
            // Auto-compact fires unattended — a missing summarizer must not
            // kill the engine; messages pass through unchanged behind a
            // passthrough boundary (pre-existing 2-arg behavior, preserved
            // after the auto path moved to the 3-arg overload).
            CompactService svc = new CompactService(TokenEstimator.getInstance(), null, true);
            UserMessage u1 = simpleUserMessage("u1", "Hello");

            CompactionResult result = svc.compactConversation(List.of(u1), true, null);

            assertEquals("passthrough", result.boundaryMarker().compactMetadata().trigger());
            assertEquals(List.of(u1), result.messagesToKeep());
        }

        @Test
        void compactConversationHandlesPTLRetry() {
            // Summarizer that returns the PTL marker on first call, then succeeds
            var callCount = new int[]{0};
            CompactSummarizer retrySummarizer = (_, _) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return CompactService.PROMPT_TOO_LONG_MARKER;
                }
                return "Summary of conversation";
            };

            CompactService svc = new CompactService(TokenEstimator.getInstance(), retrySummarizer, true);

            // Need at least 2 groups for truncation to work
            UserMessage u1 = simpleUserMessage("u1", "First message");
            AssistantMessage a1 = assistantWithId("a1", "api-1", "First response");
            UserMessage u2 = simpleUserMessage("u2", "Second message");
            AssistantMessage a2 = assistantWithId("a2", "api-2", "Second response");

            CompactionResult result = svc.compactConversation(List.of(u1, a1, u2, a2), false, null);

            assertNotNull(result);
            assertEquals(2, callCount[0]);
        }

        @Test
        void compactConversationThrowsAfterMaxPTLRetries() {
            CompactSummarizer alwaysPTL = (_, _) -> CompactService.PROMPT_TOO_LONG_MARKER;

            CompactService svc = new CompactService(TokenEstimator.getInstance(), alwaysPTL, true);

            // Need enough groups for 3 retries + the initial attempt
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                messages.add(simpleUserMessage("u" + i, "Message " + i));
                messages.add(assistantWithId("a" + i, "api-" + i, "Response " + i));
            }

            assertThrows(CompactException.class,
                    () -> svc.compactConversation(messages, false, null));
        }

        @Test
        void compactPrompt_isTheFullNineSectionPromptWithNoToolsSandwich() {
            String prompt = CompactService.buildCompactPrompt(null);

            assertTrue(Strings.CS.startsWith(prompt, "CRITICAL: Respond with TEXT ONLY. Do NOT call any tools."));
            assertTrue(Strings.CS.endsWith(prompt, "Tool calls will be rejected and you will fail the task."));
            // all nine required section headers, verbatim
            for (String section : new String[] {
                    "1. Primary Request and Intent:", "2. Key Technical Concepts:",
                    "3. Files and Code Sections:", "4. Errors and fixes:",
                    "5. Problem Solving:", "6. All user messages:",
                    "7. Pending Tasks:", "8. Current Work:", "9. Optional Next Step:"}) {
                assertTrue(Strings.CS.contains(prompt, section), "missing section: " + section);
            }
            assertTrue(Strings.CS.contains(prompt, "<analysis>"), "must instruct the analysis scratchpad block");
        }

        @Test
        void compactPrompt_insertsCustomInstructionsUnderLabel() {
            String prompt = CompactService.buildCompactPrompt("focus on auth");
            assertTrue(Strings.CS.contains(prompt, "\n\nAdditional Instructions:\nfocus on auth"),
                "TS getCompactPrompt inserts instructions under an 'Additional Instructions:' label");
            // still before the trailer
            assertTrue(prompt.indexOf("Additional Instructions:")
                    < prompt.indexOf("REMINDER: Do NOT call any tools"));
        }

        @Test
        void compactPrompt_preservesSecurityConstraintsVerbatim() {
            String prompt = CompactService.buildCompactPrompt(null);

            assertTrue(Strings.CS.contains(prompt,
                "Note any security-relevant instructions or constraints the user stated"),
                "2.1.197 explicitly requires security constraints to survive compaction");
            assertTrue(Strings.CS.contains(prompt,
                "Preserve any security-relevant instructions or constraints verbatim"),
                "the all-user-messages section must keep those constraints in force");
        }

        @Test
        void formatCompactSummary_stripsAnalysisAndRewritesSummaryTags() {
            String raw = """
                <analysis>
                scratchpad thoughts
                </analysis>

                <summary>
                1. Primary Request:
                   stuff
                </summary>""";
            String formatted = CompactService.formatCompactSummary(raw);
            assertFalse(Strings.CS.contains(formatted, "scratchpad thoughts"), "analysis block must be stripped");
            assertFalse(Strings.CS.contains(formatted, "<summary>"), "summary tags must be replaced");
            assertTrue(Strings.CS.startsWith(formatted, "Summary:\n1. Primary Request:"),
                "summary tag becomes a 'Summary:' header; got: " + formatted);
        }

        @Test
        void summaryMessage_wrapsInSessionContinuationTemplate() {
            CompactSummarizer summarizer = (_, _) -> "the raw summary";
            CompactService svc = new CompactService(TokenEstimator.getInstance(), summarizer, true);
            UserMessage u1 = simpleUserMessage("u1", "Hello");

            CompactionResult manual = svc.compactConversation(List.of(u1), false, null);
            UserMessage manualSummary = (UserMessage) manual.summaryMessages().getFirst();
            String manualText = manualSummary.message().text();
            assertEquals(Boolean.TRUE, manualSummary.isVisibleInTranscriptOnly(),
                "compact summaries are hidden from the normal transcript but remain available via ctrl+o");
            assertTrue(Strings.CS.startsWith(manualText, "This session is being continued from a previous conversation that ran out of context."),
                "summary must be wrapped in the continuation preamble; got: " + manualText);
            assertTrue(Strings.CS.contains(manualText, "without asking the user any further questions"),
                "2.1.197 wire adds the resume-directly line for manual /compact too");

            AssistantMessage a1 = assistantWithId("a1", "msg-1", "First answer");
            UserMessage u2 = simpleUserMessage("u2", "Continue");
            AssistantMessage a2 = assistantWithId("a2", "msg-2", "Latest answer");
            CompactionResult auto = svc.compactConversation(
                List.of(u1, a1, u2, a2), true, null, "claude-sonnet-4-6");
            String autoText = ((UserMessage) auto.summaryMessages().getFirst()).message().text();
            assertTrue(Strings.CS.contains(autoText, "Continue the conversation from where it left off without asking"),
                "auto-compact adds the resume-directly line (suppressFollowUpQuestions=true)");
        }

        @Test
        void rawSummary_surfacesUnwrappedTextForHookPayload() {
            CompactSummarizer summarizer = (_, _) -> "the raw summary";
            CompactService svc = new CompactService(TokenEstimator.getInstance(), summarizer, true);
            UserMessage u1 = simpleUserMessage("u1", "Hello");

            CompactionResult result = svc.compactConversation(List.of(u1), false, null);


            // executePostCompactHooks as compact_summary.
            assertEquals("the raw summary", result.summaryText());
        }

        @Test
        void streamCompactSummary_projectsAwayMessagesBeforePriorBoundary() {
// A second compaction must not re-summarize the pre-boundary original
// conversation.
            List<List<Message>> seen = new ArrayList<>();
            CompactSummarizer capturing = new CompactSummarizer() {
                @Override
                public String summarize(List<Message> msgs, String prompt) {
                    seen.add(List.copyOf(msgs));
                    return "second summary";
                }
            };
            CompactService svc = new CompactService(TokenEstimator.getInstance(), capturing, true);

            UserMessage preBoundary = simpleUserMessage("ancient", "pre-boundary original message");
            SystemMessage oldBoundary = CompactService.createCompactBoundaryMarker("manual", 100);
            UserMessage oldSummary = simpleUserMessage("old", "old summary text");
            UserMessage fresh = simpleUserMessage("u-new", "new conversation");

            svc.compactConversation(List.of(preBoundary, oldBoundary, oldSummary, fresh), false, null);

            List<Message> sent = seen.getFirst();
            assertFalse(sent.contains(preBoundary), "pre-boundary messages must be projected away");
            assertTrue(sent.contains(oldBoundary), "the boundary itself is retained (TS slice(boundaryIndex))");
            assertTrue(sent.contains(fresh), "post-boundary messages must survive projection");
        }

        @Test
        void buildPostCompactMessagesOrder() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);

            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = simpleAssistantMessage("a1", "Hi");

            CompactionResult result = svc.compactConversation(List.of(u1, a1), false, null);
            List<Message> postCompact = result.buildPostCompactMessages();

            // First message should be the boundary marker
            assertInstanceOf(SystemMessage.class, postCompact.getFirst());
            assertEquals("compact_boundary", ((SystemMessage) postCompact.getFirst()).subtype());

            // Second message should be the summary
            assertInstanceOf(UserMessage.class, postCompact.get(1));
            assertTrue(((UserMessage) postCompact.get(1)).isCompactSummary());
        }

        @Test
        void compactionUsage_threadsRealApiUsageThrough() {
            Usage usage = new Usage(500, 100, 50, 20);
            CompactSummarizer summarizer = new CompactSummarizer() {
                @Override
                public String summarize(List<Message> messages, String compactPrompt) {
                    return "summary text";
                }

                @Override
                public SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
                    return new SummaryResult("summary text", usage);
                }
            };
            CompactService svc = new CompactService(TokenEstimator.getInstance(), summarizer, true);
            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = simpleAssistantMessage("a1", "Hi there");

            CompactionResult result = svc.compactConversation(List.of(u1, a1), false, null);

            assertEquals(usage, result.compactionUsage());
        }

        @Test
        void compactionUsage_defaultsToEmptyForPlainSummarizer() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);
            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = simpleAssistantMessage("a1", "Hi there");

            CompactionResult result = svc.compactConversation(List.of(u1, a1), false, null);

            assertEquals(Usage.EMPTY, result.compactionUsage());
        }
    }

    // ========== estimateTokenCount Tests ==========

    @Nested
    class EstimateTokenCountTests {

        @Test
        void delegatesToRealTokenEstimator() {
            UserMessage u1 = simpleUserMessage("u1", "Hello there, this is a test message.");
            AssistantMessage a1 = simpleAssistantMessage("a1", "Hi! How can I help?");
            List<Message> messages = List.of(u1, a1);

            long expected = TokenEstimator.getInstance().estimateTokenCount(messages);
            MessageCompactor compactor = service;

            assertEquals(expected, compactor.estimateTokenCount(messages));
            // Content-aware, not the interface's flat 200/message fallback.
            assertNotEquals((long) messages.size() * 200, compactor.estimateTokenCount(messages));
        }
    }

    // ========== MessageGrouping Tests ==========

    @Nested
    class MessageGroupingTests {

        @Test
        void groupByApiRoundEmptyList() {
            List<List<Message>> groups = MessageGrouping.groupByApiRound(List.of());
            assertTrue(groups.isEmpty());
        }

        @Test
        void groupByApiRoundSingleAssistantGroup() {
            // A user message followed by an assistant message with a new ID
            // creates two groups: [user] and [assistant]
            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = assistantWithId("a1", "api-1", "Hi");

            List<List<Message>> groups = MessageGrouping.groupByApiRound(List.of(u1, a1));
            // u1 goes into first group, a1 starts a new group (new assistant ID)
            assertEquals(2, groups.size());
            assertEquals(1, groups.getFirst().size()); // [u1]
            assertEquals(1, groups.get(1).size()); // [a1]
        }

        @Test
        void groupByApiRoundMultipleGroups() {
            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = assistantWithId("a1", "api-1", "Hi");
            UserMessage u2 = simpleUserMessage("u2", "How are you?");
            AssistantMessage a2 = assistantWithId("a2", "api-2", "Fine");

            List<List<Message>> groups = MessageGrouping.groupByApiRound(List.of(u1, a1, u2, a2));
            // [u1], [a1, u2], [a2]
            assertEquals(3, groups.size());
        }

        @Test
        void groupByApiRoundSameAssistantIdStaysTogether() {
            UserMessage u1 = simpleUserMessage("u1", "Hello");
            // Two assistant messages with same API ID (same API response)
            AssistantMessage a1a = assistantWithId("a1a", "api-1", "Part 1");
            AssistantMessage a1b = assistantWithId("a1b", "api-1", "Part 2");

            List<List<Message>> groups = MessageGrouping.groupByApiRound(List.of(u1, a1a, a1b));
            // [u1], [a1a, a1b] — same API ID stays together
            assertEquals(2, groups.size());
            assertEquals(1, groups.getFirst().size()); // [u1]
            assertEquals(2, groups.get(1).size()); // [a1a, a1b]
        }

        @Test
        void groupByApiRoundPreservesAllMessages() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                messages.add(simpleUserMessage("u" + i, "Msg " + i));
                messages.add(assistantWithId("a" + i, "api-" + i, "Resp " + i));
            }

            List<List<Message>> groups = MessageGrouping.groupByApiRound(messages);

            int totalMessages = groups.stream().mapToInt(List::size).sum();
            assertEquals(messages.size(), totalMessages);
        }
    }

    // ========== Compact Boundary Tests ==========

    @Nested
    class CompactBoundaryTests {

        @Test
        void createCompactBoundaryMarkerAuto() {
            SystemMessage marker = CompactService.createCompactBoundaryMarker("auto", 50000);

            assertEquals("compact_boundary", marker.subtype());
            assertEquals("info", marker.level());
            assertEquals("Conversation compacted", marker.content());
            assertNotNull(marker.compactMetadata());
            assertEquals("auto", marker.compactMetadata().trigger());
            assertEquals(50_000L, marker.compactMetadata().preTokens());
            assertNotNull(marker.uuid());
        }

        @Test
        void createCompactBoundaryMarkerManual() {
            SystemMessage marker = CompactService.createCompactBoundaryMarker("manual", 75000);

            assertEquals("Conversation compacted", marker.content());
            assertEquals("manual", marker.compactMetadata().trigger());
            assertEquals(75_000L, marker.compactMetadata().preTokens());
        }
    }

    // ========== TruncateHeadForPTLRetry Tests ==========

    @Nested
    class TruncateHeadTests {

        @Test
        void truncateHeadRemovesOldestGroup() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);

            UserMessage u1 = simpleUserMessage("u1", "First");
            AssistantMessage a1 = assistantWithId("a1", "api-1", "Response 1");
            UserMessage u2 = simpleUserMessage("u2", "Second");
            AssistantMessage a2 = assistantWithId("a2", "api-2", "Response 2");

            // Groups: [u1], [a1, u2], [a2] — removing first group [u1] leaves [a1, u2, a2]
            List<Message> truncated = svc.truncateHeadForPTLRetry(List.of(u1, a1, u2, a2));

            assertEquals(3, truncated.size());
            assertEquals("a1", truncated.getFirst().uuid());
        }

        @Test
        void truncateHeadThrowsWithSingleGroup() {
            CompactSummarizer noOp = new NoOpCompactSummarizer();
            CompactService svc = new CompactService(TokenEstimator.getInstance(), noOp, true);

            UserMessage u1 = simpleUserMessage("u1", "Only message");

            assertThrows(CompactException.class,
                    () -> svc.truncateHeadForPTLRetry(List.of(u1)));
        }
    }

    // ========== NoOpCompactSummarizer Tests ==========

    @Nested
    class NoOpCompactSummarizerTests {

        @Test
        void summarizeConcatenatesText() {
            NoOpCompactSummarizer noOp = new NoOpCompactSummarizer();

            UserMessage u1 = simpleUserMessage("u1", "Hello");
            AssistantMessage a1 = simpleAssistantMessage("a1", "World");

            String summary = noOp.summarize(List.of(u1, a1), "prompt");
            assertTrue(Strings.CS.contains(summary, "Hello"));
            assertTrue(Strings.CS.contains(summary, "World"));
        }

        @Test
        void summarizeHandlesEmptyList() {
            NoOpCompactSummarizer noOp = new NoOpCompactSummarizer();
            String summary = noOp.summarize(List.of(), "prompt");
            assertEquals("", summary);
        }
    }

    // ========== createPostCompactAttachments Tests ==========

    @Nested
    class PostCompactAttachmentsTests {

        @Test
        void createPostCompactAttachmentsReturnsEmptyList() {
            List<Message> attachments = service.createPostCompactAttachments();
            assertNotNull(attachments);
            assertTrue(attachments.isEmpty());
        }
    }

// ========== Token warning state.

    @Nested
    class TokenWarningStateTests {

        @Test
        void suppressDefaultsToFalse() {
            assertFalse(service.isCompactWarningSuppressed());
        }

        @Test
        void suppressThenClearTogglesState() {
            service.suppressCompactWarning();
            assertTrue(service.isCompactWarningSuppressed());
            service.clearCompactWarningSuppression();
            assertFalse(service.isCompactWarningSuppressed());
        }

        @Test
        void microcompactAttemptClearsPreviousSuppression() {
            service.suppressCompactWarning();
            assertTrue(service.isCompactWarningSuppressed());

            service.microcompactMessages(List.of(simpleUserMessage("u1", "hello")));

            assertFalse(service.isCompactWarningSuppressed(),
                "TS microcompactMessages clears suppression at the start of every new attempt");
        }

        @Test
        void calculateTokenWarningStateDelegatesToStrategy() {
            // No-op summarizer keeps compaction from throwing; token usage 0 is well
            // below every threshold, so the warning must not be raised.
            CompactService.TokenWarningState state = service.calculateTokenWarningState(0L, "claude-sonnet-4-6");
            assertNotNull(state);
            assertFalse(state.isAboveWarningThreshold());
            assertFalse(state.isAtBlockingLimit());
        }

        @Test
        void successfulAutoCompactLeavesWarningUnsuppressed() {
            // Past the blocking limit so the warning is genuinely raised first.
            CompactService.TokenWarningState before =
                service.calculateTokenWarningState(1_000_000L, "claude-sonnet-4-6");
            assertTrue(before.isAtBlockingLimit());

            service.suppressCompactWarning();
            assertTrue(service.isCompactWarningSuppressed());

            // Auto-compact does not call the manual command's post-success suppressor.
            List<Message> messages = List.of(simpleUserMessage("u1", "hello"));
            service.compactConversation(messages, true);
            assertFalse(service.isCompactWarningSuppressed(),
                "suppression should be cleared after a successful compaction");
        }
    }
}
