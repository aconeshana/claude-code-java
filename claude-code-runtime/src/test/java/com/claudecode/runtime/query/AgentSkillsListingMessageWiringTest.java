package com.claudecode.runtime.query;

import com.claudecode.core.engine.*;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.AttachmentProvider;
import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.attachment.AgentListingDeltaAttachmentProvider;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.attachment.McpInstructionsDeltaAttachmentProvider;
import com.claudecode.core.attachment.SkillListingAttachmentProvider;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.BudgetUsdAttachment;
import com.claudecode.core.message.HookAdditionalContextAttachment;
import com.claudecode.core.message.McpInstructionsDeltaAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.AutoModeReminderAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TextReminderAttachment;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards {@code QueryLoop.buildRequestMessages}' insertion of the agent-types + skills listing as a
 * {@code role:system} message right after the first real user turn.
 */
class AgentSkillsListingMessageWiringTest {

    private static final String LISTING_TEXT =
        "Available agent types for the Agent tool:\n- claude: Catch-all agent.";

    @Test
    void sonnet46RendersInitialListingAsLeadingUserReminder() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        AttachmentProvider listing = new AttachmentProvider() {
            @Override public String name() { return "listings"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                return List.of(
                    new AgentListingDeltaAttachment(
                        List.of("claude"), List.of("- claude: Catch-all agent."),
                        List.of(), true, true),
                    new McpInstructionsDeltaAttachment(
                        List.of("wire-skills"),
                        List.of("## wire-skills\nWIRE197 MCP skill server instructions."),
                        List.of()),
                    new SkillListingAttachment("- review: Review code", 1, true,
                        List.of("review")));
            }
        };

        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(
                List.of(listing), FeatureFlagRegistry.allOff()))
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, true, null, false, List.of(), List.of(), false, null, LISTING_TEXT))
            .build());

        var iter = engine.submitMessage("Hi there", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        List<StreamingClient.StreamRequest.RequestMessage> messages =
            captured.getFirst().messages();
        assertEquals(List.of("user"),
            messages.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        assertInstanceOf(List.class, messages.getFirst().content());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks =
            (List<Map<String, Object>>) messages.getFirst().content();
        assertTrue(Strings.CS.contains(String.valueOf(blocks.getFirst().get("text")), "Available agent types for the Agent tool:"),
            "2.1.197 emits attachment reminders before date/CLAUDE context");
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(1).get("text")), "# MCP Server Instructions"),
            "2.1.197 keeps MCP instructions between Agent and Skill listings");
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(2).get("text")), "The following skills are available for use with the Skill tool:"));
        assertFalse(Strings.CS.endsWith(String.valueOf(blocks.getFirst().get("text")), "\n"),
            "Agent, MCP, and Skill reminders originate in one leading user turn; no merge seam between them");
        assertFalse(Strings.CS.endsWith(String.valueOf(blocks.get(1).get("text")), "\n"),
            "there is no merge seam between MCP instructions and Skill listing");
        assertTrue(Strings.CS.endsWith(String.valueOf(blocks.get(2).get("text")), "\n"),
            "the merge seam belongs after the last listing reminder, before currentDate");
        assertEquals("Hi there", blocks.getLast().get("text"));
    }

    @Test
    void userPromptSubmitContextFollowsListingsButPrecedesClaudeMdAndPrompt() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        List<Message> transcript = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-hook", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        AttachmentProvider listing = new AttachmentProvider() {
            @Override public String name() { return "listings"; }
            @Override public List<AttachmentPayload> collect(AttachmentContext context) {
                return List.of(
                    new AgentListingDeltaAttachment(
                        List.of("claude"), List.of("- claude: Catch-all agent."),
                        List.of(), true, true),
                    new SkillListingAttachment("- review: Review code", 1, true,
                        List.of("review")));
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .workingDirectory("/tmp/proj")
            .claudeMdContentSupplier(() -> "PROJECT_RULES")
            .attachmentService(new AttachmentService(
                List.of(listing), FeatureFlagRegistry.allOff()))
            .build());
        engine.setTranscriptSink((_, message) -> transcript.add(message));
        engine.setHookDispatcher(new HookDispatcher() {
            @Override public boolean dispatchPreToolUse(
                    String toolName, JsonNode input, String toolUseId) { return true; }
            @Override public void dispatchPostToolUse(
                    String toolName, JsonNode input, JsonNode output, String toolUseId) { }
            @Override public void dispatchUserPromptSubmit(String prompt) { }
            @Override public HookOutcome dispatchUserPromptSubmitWithOutcome(String prompt) {
                return new HookOutcome(true, "WIRE_HOOK_CONTEXT", List.of(),
                    false, null, null, List.of("WIRE_HOOK_CONTEXT"));
            }
            @Override public void dispatchSessionStart(String trigger) { }
            @Override public void dispatchStop(String reason) { }
        });

        var iter = engine.submitMessage("Inspect this project", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>)
            captured.getFirst().messages().getFirst().content();
        assertTrue(Strings.CS.contains(String.valueOf(blocks.getFirst().get("text")), "Available agent types for the Agent tool:"), String.valueOf(blocks));
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(1).get("text")), "The following skills are available for use with the Skill tool:"));
        assertEquals(
            """
            <system-reminder>
            UserPromptSubmit hook additional context: \
            WIRE_HOOK_CONTEXT
            </system-reminder>
            """,
            blocks.get(2).get("text"));
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(3).get("text")), "PROJECT_RULES"));
        assertEquals("Inspect this project", blocks.getLast().get("text"));

        HookAdditionalContextAttachment attachment = transcript.stream()
            .filter(AttachmentMessage.class::isInstance)
            .map(AttachmentMessage.class::cast)
            .map(AttachmentMessage::payload)
            .filter(HookAdditionalContextAttachment.class::isInstance)
            .map(HookAdditionalContextAttachment.class::cast)
            .findFirst().orElseThrow();
        assertEquals(List.of("WIRE_HOOK_CONTEXT"), attachment.content());
        assertEquals("UserPromptSubmit", attachment.hookName());
        assertTrue(Strings.CS.startsWith(attachment.toolUseID(), "hook-"));
        assertEquals("UserPromptSubmit", attachment.hookEvent());
    }

    @Test
    void configuredBlankClaudeMdSupplierDoesNotFallbackToFilesystem() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }
                @Override public String getModel() { return "claude-sonnet-4-6"; }
            })
            .model("claude-sonnet-4-6")
            .workingDirectory(System.getProperty("user.dir"))
            .claudeMdContentSupplier(() -> "")
            .build());

        String context = QueryHelpers
            .buildClaudeMdUserContext(engine);

        assertTrue(Strings.CS.contains(context, "# currentDate"));
        assertFalse(Strings.CS.contains(context, "# claudeMd"),
            "an app-wired blank supplier means CLAUDE.md is disabled by setting sources");
    }

    @Test
    void modelRequestReadyCallbackRunsAfterTypedUserAndInitialAttachmentsPersist() {
        List<String> events = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                events.add("call-model");
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        AttachmentProvider listing = new AttachmentProvider() {
            @Override public String name() { return "listings"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                return List.of(
                    new AgentListingDeltaAttachment(
                        List.of("claude"), List.of("- claude: Catch-all agent."),
                        List.of(), true, true),
                    new SkillListingAttachment("- review: Review code", 1, true,
                        List.of("review")));
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(
                List.of(listing), FeatureFlagRegistry.allOff()))
            .build());
        engine.setTranscriptSink((_, message) -> {
            if (message instanceof UserMessage) {
                events.add("user");
            } else if (message instanceof AttachmentMessage) {
                events.add("attachment");
            }
        });
        engine.setBeforeModelRequestCallback(() -> events.add("request-ready"));

        var iter = engine.submitMessage("Inspect this project", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        assertEquals(List.of(
            "user", "attachment", "attachment", "request-ready", "call-model"), events);
    }

    @Test
    void sonnet46KeepsInitialPlanReminderBetweenSkillListingAndCurrentDate() {
        List<Map<String, Object>> blocks = captureInitialBlocks(
            new PlanModeReminderAttachment("full", false, "/plans/wire.md", false));

        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(1).get("text")), "The following skills are available for use with the Skill tool:"));
        assertFalse(Strings.CS.endsWith(String.valueOf(blocks.get(1).get("text")), "\n"),
            "the Skill-to-plan boundary is inside one released attachment turn");
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(2).get("text")), "Plan mode is active."));
        assertTrue(Strings.CS.endsWith(String.valueOf(blocks.get(2).get("text")), "\n"),
            "the only merge seam belongs after plan mode, before currentDate");
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(3).get("text")), "# currentDate"));
        assertEquals("Hi there", blocks.getLast().get("text"));
    }

    @Test
    void sonnet46KeepsInitialAutoReminderBetweenSkillListingAndCurrentDate() {
        List<Map<String, Object>> blocks = captureInitialBlocks(
            new AutoModeReminderAttachment("full"));

        assertFalse(Strings.CS.endsWith(String.valueOf(blocks.get(1).get("text")), "\n"));
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(2).get("text")), "## Auto Mode Active"));
        assertTrue(Strings.CS.endsWith(String.valueOf(blocks.get(2).get("text")), "\n"));
        assertTrue(Strings.CS.contains(String.valueOf(blocks.get(3).get("text")), "# currentDate"));
        assertEquals("Hi there", blocks.getLast().get("text"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> captureInitialBlocks(
            AttachmentPayload modeReminder) {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        AttachmentProvider provider = new AttachmentProvider() {
            @Override public String name() { return "released-leading-order"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                return List.of(
                    new AgentListingDeltaAttachment(
                        List.of("claude"), List.of("- claude: Catch-all agent."),
                        List.of(), true, true),
                    new SkillListingAttachment("- review: Review code", 1, true,
                        List.of("review")),
                    modeReminder);
            }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(
                List.of(provider), FeatureFlagRegistry.allOff()))
            .build());

        var iter = engine.submitMessage("Hi there", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        return (List<Map<String, Object>>) captured.getFirst().messages().getFirst().content();
    }

    @Test
    void sonnet46RetainsInitialAgentAndSkillListingsAcrossUserSubmissions() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-" + captured.size(), request.model(), List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "OK"),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };
        AttachmentProvider listing = new AttachmentProvider() {
            private boolean emitted;

            @Override public String name() { return "listings"; }

            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                if (emitted) return List.of();
                emitted = true;
                return List.of(
                    new AgentListingDeltaAttachment(
                        List.of("claude"), List.of("- claude: Catch-all agent."),
                        List.of(), true, true),
                    new SkillListingAttachment("- review: Review code", 1, true,
                        List.of("review")));
            }
        };

        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(
                List.of(listing), FeatureFlagRegistry.allOff()))
            .build());

        var first = engine.submitMessage("first", SubmitOptions.DEFAULT);
        while (first.hasNext()) first.next();
        var second = engine.submitMessage("second", SubmitOptions.DEFAULT);
        while (second.hasNext()) second.next();

        assertEquals(2, captured.size());
        List<StreamingClient.StreamRequest.RequestMessage> secondRequest =
            captured.get(1).messages();
        assertEquals(List.of("user", "assistant", "user"),
            secondRequest.stream()
                .map(StreamingClient.StreamRequest.RequestMessage::role)
                .toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstTurnBlocks =
            (List<Map<String, Object>>) secondRequest.getFirst().content();
        String firstTurn = firstTurnBlocks.stream()
            .map(block -> String.valueOf(block.get("text")))
            .collect(Collectors.joining("\n"));
        assertEquals(1, occurrences(firstTurn, "Available agent types for the Agent tool:"));
        assertEquals(1, occurrences(firstTurn,
            "The following skills are available for use with the Skill tool:"));
        assertTrue(Strings.CS.endsWith(firstTurn, "first"),
            "the retained reminders must remain attached to the first user turn");
        assertEquals("second", secondRequest.getLast().content());
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    @Test
    void listingMessageInsertedAfterFirstUserTurn() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .workingDirectory("/tmp/proj")
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, false, null, false, List.of(), List.of(), false, null, LISTING_TEXT))
            .build());

        var iter = engine.submitMessage("Hi there", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        assertEquals(1, captured.size(), "expected exactly one API request");
        List<StreamingClient.StreamRequest.RequestMessage> messages = captured.getFirst().messages();

        // messages[0] is the context-reminder user message (currentDate, and
        // CLAUDE.md when configured) merged with the real submitted turn into
        // one role:user entry with a multi-block content array; the listing
        // must land right after that merged message.
        assertEquals("user", messages.getFirst().role(), "context-reminder message must be first");
        assertInstanceOf(List.class, messages.getFirst().content(),
            "merged user message content must be a block array, not a plain string");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.getFirst().content();
        assertEquals("Hi there", blocks.getLast().get("text"),
            "the real submitted turn must be the trailing text block");

        assertEquals("system", messages.get(1).role(),
            "agent+skills listing must land right after the merged user turn");
        assertEquals(LISTING_TEXT, messages.get(1).content());
    }

    @Test
    void noListingMessageWhenSupplierOmitsIt() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()
                ).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .workingDirectory("/tmp/proj")
            .build());

        var iter = engine.submitMessage("Hi there", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        List<StreamingClient.StreamRequest.RequestMessage> messages = captured.getFirst().messages();
        assertTrue(messages.stream().noneMatch(m -> Strings.CS.equals("system", m.role())),
            "no promptRuntimeSupplier means no listing message");
    }

    @Test
    void productionInventoryProvidersSuppressLegacyListingWhenAgentAndSkillToolsAreExcluded() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
        AttachmentService productionInventory = new AttachmentService(
            List.of(new AgentListingDeltaAttachmentProvider(),
                new SkillListingAttachmentProvider()),
            FeatureFlagRegistry.builder().enable(FeatureFlag.AGENT_LISTING_DELTA).build());
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .workingDirectory("/tmp/proj")
            .tools(List.of("Read", "Bash"))
            .attachmentService(productionInventory)
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, false, null, false, List.of(), List.of(), false, null, LISTING_TEXT))
            .build());

        var iter = engine.submitMessage("Hi there", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        assertTrue(captured.getFirst().messages().stream()
            .map(StreamingClient.StreamRequest.RequestMessage::content)
            .map(String::valueOf)
            .noneMatch(text -> Strings.CS.contains(text, "Available agent types for the Agent tool:")),
            "--tools without Agent/Skill must not fall back to the combined legacy listing");
    }

    @Test
    void budgetAttachmentIsRecordedButRenderedInTheSystemListingOnly() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        List<Message> transcript = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
        AttachmentProvider budget = new AttachmentProvider() {
            @Override public String name() { return "budget_usd"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                return List.of(new BudgetUsdAttachment(0, 0.05, 0.05));
            }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .workingDirectory("/tmp/proj")
            .maxBudgetUsd(0.05)
            .attachmentService(new AttachmentService(
                List.of(budget), FeatureFlagRegistry.allOff()))
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, false, null, true, List.of(), List.of(), false, null, LISTING_TEXT))
            .build());
        engine.setTranscriptSink((_, message) -> transcript.add(message));

        var iter = engine.submitMessage("Hi there", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        var messages = captured.getFirst().messages();
        assertTrue(Strings.CS.endsWith(messages.get(1).content().toString(), "USD budget: $0/$0.05; $0.05 remaining"));
        assertFalse(Strings.CS.contains(messages.getFirst().content().toString(), "USD budget:"),
            "budget attachment must not be duplicated into the merged user reminder");
        assertTrue(transcript.stream().anyMatch(m -> m instanceof AttachmentMessage am
            && am.payload() instanceof BudgetUsdAttachment));
    }

    @Test
    void postCompactAgentListingAttachmentSuppressesStandaloneListingInjection() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .workingDirectory("/tmp/proj")
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, false, null, false, List.of(), List.of(), false, null, LISTING_TEXT))
            .build());
        engine.loadMessages(List.of(
            MessageFactory.createUserMessage("summary", false),
            new AssistantMessage("a1", AssistantContent.of(List.of(new TextBlock("OK")))),
            MessageFactory.createUserMessage("<command-name>/compact</command-name>", false),
            new AttachmentMessage("att1", new AgentListingDeltaAttachment(
                List.of("claude"), List.of("- claude: Catch-all agent."),
                List.of(), true, true))));

        var iter = engine.submitMessage("continue", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        var messages = captured.getFirst().messages();
        long listingOccurrences = messages.stream()
            .map(StreamingClient.StreamRequest.RequestMessage::content)
            .map(String::valueOf)
            .filter(s -> Strings.CS.contains(s, "Available agent types for the Agent tool:"))
            .count();
        assertEquals(1, listingOccurrences,
            "the post-compact agent_listing_delta must be the only listing on the wire");
        assertEquals(List.of("user", "assistant", "user", "system"),
            messages.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
    }

    @Test
    void sonnet46MovesPostCompactInitialListingBeforeLocalCommandBlocks() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "claude-sonnet-4-6", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "claude-sonnet-4-6"; }
        };

        var engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-4-6")
            .workingDirectory("/tmp/proj")
            .build());
        engine.loadMessages(List.of(
            MessageFactory.createUserMessage("summary", false),
            new AssistantMessage("a1", AssistantContent.of(List.of(new TextBlock("OK")))),
            MessageFactory.createUserMessage("<command-name>/compact</command-name>", false),
            new AttachmentMessage("att1", new AgentListingDeltaAttachment(
                List.of("claude"), List.of("- claude: Catch-all agent."),
                List.of(), true, true)),
            MessageFactory.createUserMessage("Continue from where you left off.", false)));

        var iter = engine.submitMessage("continue", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        Object content = captured.getFirst().messages().get(2).content();
        String rendered = String.valueOf(content);
        assertTrue(rendered.indexOf("Available agent types for the Agent tool:")
                < rendered.indexOf("<command-name>/compact</command-name>"),
            "post-compact initial inventory must lead its user segment");
    }

    @Test
    void transcriptOnlyAttachmentIsRememberedAcrossSeparateUserSubmissions() {
        List<Message> transcript = new ArrayList<>();
        AttachmentProvider once = new AttachmentProvider() {
            @Override public String name() { return "agent_listing_delta"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                boolean alreadyRecorded = context.messages().stream().anyMatch(
                    m -> m instanceof AttachmentMessage am
                        && am.payload() instanceof AgentListingDeltaAttachment);
                return alreadyRecorded ? List.of() : List.of(new AgentListingDeltaAttachment(
                    List.of("claude"), List.of("- claude: Catch-all agent."),
                    List.of(), true, true));
            }
        };
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(List.of(once), FeatureFlagRegistry.allOff()))
            .build());
        engine.setTranscriptSink((_, message) -> transcript.add(message));

        var first = engine.submitMessage("first", SubmitOptions.DEFAULT);
        while (first.hasNext()) first.next();
        var second = engine.submitMessage("second", SubmitOptions.DEFAULT);
        while (second.hasNext()) second.next();

        assertEquals(1, transcript.stream().filter(m -> m instanceof AttachmentMessage am
            && am.payload() instanceof AgentListingDeltaAttachment).count(),
            "transcript-only deltas must be available to the next submission's diff provider");
    }

    @Test
    void mcpInstructionsDeltaIsPersistedAndUsesMidConversationSystem() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        List<Message> transcript = new ArrayList<>();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent("msg", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .model("test-model")
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(
                List.of(new McpInstructionsDeltaAttachmentProvider()),
                FeatureFlagRegistry.builder()
                    .enable(FeatureFlag.MCP_INSTRUCTIONS_DELTA)
                    .build()))
            .mcpServerInstructionsSupplier(() -> Map.of("server", "MCP_DELTA_SENTINEL"))
            .build());
        engine.setTranscriptSink((_, message) -> transcript.add(message));

        var iter = engine.submitMessage("hello", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        assertTrue(transcript.stream().anyMatch(m -> m instanceof AttachmentMessage am
            && am.payload() instanceof McpInstructionsDeltaAttachment));
        var messages = captured.getFirst().messages();
        assertEquals(List.of("user", "system"),
            messages.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        assertTrue(Strings.CS.contains(String.valueOf(messages.get(1).content()), "MCP_DELTA_SENTINEL"));
        assertFalse(Strings.CS.contains(String.valueOf(messages.getFirst().content()), "MCP_DELTA_SENTINEL"),
            "the raw system projection must not be duplicated as user prose");
    }

    @Test
    void attachmentsAreRecomputedAfterToolExecutionWithoutRepeatingInitialDeltas() {
        List<StreamingClient.StreamRequest> captured = new ArrayList<>();
        List<Message> transcript = new ArrayList<>();
        AtomicInteger attachmentPass = new AtomicInteger();
        AttachmentProvider staged = new AttachmentProvider() {
            @Override public String name() { return "staged"; }
            @Override public List<AttachmentPayload> collect(
                    AttachmentContext context) {
                return switch (attachmentPass.getAndIncrement()) {
                    case 0 -> List.of(new AgentListingDeltaAttachment(
                        List.of("claude"), List.of("- claude: Catch-all agent."),
                        List.of(), true, true));
                    case 1 -> List.of(new TextReminderAttachment("POST_TOOL_ATTACHMENT"));
                    default -> List.of();
                };
            }
        };
        AtomicInteger modelCall = new AtomicInteger();
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                captured.add(request);
                if (modelCall.getAndIncrement() == 0) {
                    return List.<StreamingEvent>of(
                        new StreamingEvent.MessageStartEvent(
                            "msg-tool", "test-model", List.of(), Usage.EMPTY),
                        new StreamingEvent.ContentBlockStartEvent(0, "tool_use", "tu-1", "Probe"),
                        new StreamingEvent.ContentBlockDeltaEvent(0, "input_json_delta", "{}"),
                        new StreamingEvent.ContentBlockStopEvent(0),
                        new StreamingEvent.MessageDeltaEvent("tool_use", Usage.EMPTY),
                        new StreamingEvent.MessageStopEvent()).iterator();
                }
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-done", "test-model", List.of(), Usage.EMPTY),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "done"),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
        ToolExecutor executor = (_, _, _) -> ToolResult.success("probe-ok");
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(client)
            .toolExecutor(executor)
            .workingDirectory("/tmp/proj")
            .attachmentService(new AttachmentService(
                List.of(staged), FeatureFlagRegistry.allOff()))
            .build());
        engine.setTranscriptSink((_, message) -> transcript.add(message));

        var iter = engine.submitMessage("run probe", SubmitOptions.DEFAULT);
        while (iter.hasNext()) iter.next();

        assertEquals(2, captured.size());
        assertTrue(attachmentPass.get() >= 2,
            "TS query.ts recomputes input=null attachments after each tool batch");
        assertEquals(1, transcript.stream().filter(m -> m instanceof AttachmentMessage am
            && am.payload() instanceof AgentListingDeltaAttachment).count());
        assertEquals(1, transcript.stream().filter(m -> m instanceof AttachmentMessage am
            && am.payload() instanceof TextReminderAttachment).count());
        assertFalse(Strings.CS.contains(String.valueOf(captured.getFirst().messages()), "POST_TOOL_ATTACHMENT"));
        assertTrue(Strings.CS.contains(String.valueOf(captured.get(1).messages()), "POST_TOOL_ATTACHMENT"),
            "the post-tool attachment must be visible on the continuation request");
    }
}
