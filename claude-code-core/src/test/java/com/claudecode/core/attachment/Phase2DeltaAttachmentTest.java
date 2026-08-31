package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Map;

import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.CompactionReminderAttachment;
import com.claudecode.core.message.DeferredToolsDeltaAttachment;
import com.claudecode.core.message.McpInstructionsDeltaAttachment;
import com.claudecode.core.message.Message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-2 cache-efficiency delta attachments: {@code agent_listing_delta}, {@code
 * mcp_instructions_delta}, {@code deferred_tools_delta}, {@code compaction_reminder}, {@code
 * context_efficiency}.
 */
class Phase2DeltaAttachmentTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private static BuiltInAgentDefinitions.AgentDefinition agent(String type, String when, List<String> tools) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(type, when)
            .tools(tools).source(null).build();
    }

    private static BuiltInAgentDefinitions.AgentDefinition agentWithMcp(
            String type, String when, List<String> tools, List<String> mcpServers) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(type, when)
            .tools(tools).mcpServers(mcpServers).source(null).build();
    }

    private static AttachmentContext ctx(
            List<Message> messages, List<String> toolNames,
            List<BuiltInAgentDefinitions.AgentDefinition> activeAgents,
            Map<String, String> mcpInstructions, List<String> previousTurnTools,
            boolean compactionOccurred) {
        return AttachmentContext.builder(".")
            .messages(messages)
            .toolNames(toolNames)
            .activeAgents(activeAgents)
            .mcpServerInstructions(mcpInstructions)
            .previousTurnTools(previousTurnTools)
            .compactionOccurred(compactionOccurred)
            .build();
    }

    private static FeatureFlagRegistry on(FeatureFlag flag) {
        return FeatureFlagRegistry.builder().enable(flag).build();
    }

    // ── agent_listing_delta ──────────────────────────────────────────────

    @Test
    void agentListing_isEnabledGated() {
        var p = new AgentListingDeltaAttachmentProvider();
        assertTrue(p.isEnabled(on(FeatureFlag.AGENT_LISTING_DELTA)));
        assertFalse(p.isEnabled(FeatureFlagRegistry.allOff()));
    }

    @Test
    void agentListing_emitsInitialListingForNewAgents() {
        var p = new AgentListingDeltaAttachmentProvider();
        var ctx = ctx(List.of(), List.of("Agent"),
            List.of(agent("general-purpose", "does research", List.of("Read")),
                agent("Explore", "fast read-only", List.of("Read", "Grep"))),
            Map.of(), null, false);
        List<AttachmentPayload> out = p.collect(ctx);
        assertEquals(1, out.size());
        var a = (AgentListingDeltaAttachment) out.getFirst();
        assertTrue(a.isInitial());
        // Sorted by agentType (Explore < general-purpose).
        assertEquals(List.of("Explore", "general-purpose"), a.addedTypes());
        assertEquals(2, a.addedLines().size());
        assertTrue(Strings.CS.startsWith(a.addedLines().getFirst(), "- Explore:"));
        assertTrue(Strings.CS.startsWith(a.addedLines().get(1), "- general-purpose:"));
        assertTrue(Strings.CS.contains(a.addedLines().get(1), "(Tools: Read)"));
        assertTrue(a.removedTypes().isEmpty());
    }

    @Test
    void agentListing_persistsTheSameLeanDenyListLinesUsedOnTheWire() {
        var p = new AgentListingDeltaAttachmentProvider();
        var ctx = ctx(List.of(), List.of("Agent"),
            List.of(BuiltInAgentDefinitions.EXPLORE, BuiltInAgentDefinitions.PLAN),
            Map.of(), null, false);

        var attachment = (AgentListingDeltaAttachment) p.collect(ctx).getFirst();

        assertEquals(List.of(
            BuiltInAgentDefinitions.EXPLORE.toPromptLine(true),
            BuiltInAgentDefinitions.PLAN.toPromptLine(true)),
            attachment.addedLines());
        assertEquals(
            "- Explore: " + BuiltInAgentDefinitions.EXPLORE_SDK_DESCRIPTION
                + " (Tools: All tools except Agent, Artifact, ExitPlanMode, Edit, Write, NotebookEdit)",
            attachment.addedLines().getFirst());
    }

    @Test
    void agentListing_noEmitWhenAgentToolAbsent() {
        var p = new AgentListingDeltaAttachmentProvider();
        var ctx = ctx(List.of(), List.of("Read", "Write"),
            List.of(agent("general-purpose", "x", List.of())), Map.of(), null, false);
        assertTrue(p.collect(ctx).isEmpty());
    }

    @Test
    void agentListing_reconstructsAnnouncedFromTranscript() {
        var p = new AgentListingDeltaAttachmentProvider();
        Message prev = new AttachmentMessage("u1", new AgentListingDeltaAttachment(
            List.of("general-purpose"), List.of("- general-purpose: x"), List.of(), true, false));
        var ctx = ctx(List.of(prev), List.of("Agent"),
            List.of(agent("general-purpose", "x", List.of()),
                agent("Explore", "y", List.of())), Map.of(), null, false);
        var a = (AgentListingDeltaAttachment) p.collect(ctx).getFirst();
        assertFalse(a.isInitial());
        assertEquals(List.of("Explore"), a.addedTypes());
        assertTrue(a.removedTypes().isEmpty());
    }

    @Test
    void agentListing_emitsRemovedWhenAgentDisappears() {
        var p = new AgentListingDeltaAttachmentProvider();
        Message prev = new AttachmentMessage("u1", new AgentListingDeltaAttachment(
            List.of("general-purpose"), List.of("- general-purpose: x"), List.of(), true, false));
        var ctx = ctx(List.of(prev), List.of("Agent"), List.of(), Map.of(), null, false);
        var a = (AgentListingDeltaAttachment) p.collect(ctx).getFirst();
        assertEquals(List.of("general-purpose"), a.removedTypes());
        assertTrue(a.addedTypes().isEmpty());
    }

    @Test
    void agentListing_excludesAgentsWhoseRequiredMcpServerIsNotConnected() {
        var p = new AgentListingDeltaAttachmentProvider();
        var ctx = ctx(List.of(), List.of("Agent"),
            List.of(agentWithMcp("db-reviewer", "reviews db changes", List.of("Read"), List.of("postgres")),
                agent("general-purpose", "does research", List.of("Read"))),
            Map.of(), null, false);
        var a = (AgentListingDeltaAttachment) p.collect(ctx).getFirst();
        assertEquals(List.of("general-purpose"), a.addedTypes());
        assertFalse(a.addedLines().stream().anyMatch(l -> Strings.CS.contains(l, "db-reviewer")));
    }

    @Test
    void agentListing_includesAgentWhoseRequiredMcpServerIsConnected() {
        var p = new AgentListingDeltaAttachmentProvider();
        var ctx = ctx(List.of(), List.of("Agent", "mcp__postgres__query"),
            List.of(agentWithMcp("db-reviewer", "reviews db changes", List.of("Read"), List.of("postgres"))),
            Map.of(), null, false);
        var a = (AgentListingDeltaAttachment) p.collect(ctx).getFirst();
        assertEquals(List.of("db-reviewer"), a.addedTypes());
    }

    // ── mcp_instructions_delta ───────────────────────────────────────────

    @Test
    void mcpInstructions_isEnabledGated() {
        var p = new McpInstructionsDeltaAttachmentProvider();
        assertTrue(p.isEnabled(on(FeatureFlag.MCP_INSTRUCTIONS_DELTA)));
        assertFalse(p.isEnabled(FeatureFlagRegistry.allOff()));
    }

    @Test
    void mcpInstructions_emitsAddedInstructions() {
        var p = new McpInstructionsDeltaAttachmentProvider();
        var ctx = ctx(List.of(), List.of(), List.of(), Map.of("s1", "do X"), null, false);
        var a = (McpInstructionsDeltaAttachment) p.collect(ctx).getFirst();
        assertEquals(List.of("s1"), a.addedNames());
        assertEquals(List.of("## s1\ndo X"), a.addedBlocks());
        assertTrue(a.removedNames().isEmpty());
    }

    @Test
    void mcpInstructions_emitsRemovedWhenDisconnected() {
        var p = new McpInstructionsDeltaAttachmentProvider();
        Message prev = new AttachmentMessage("u1", new McpInstructionsDeltaAttachment(
            List.of("s1"), List.of("## s1\ndo X"), List.of()));
        var ctx = ctx(List.of(prev), List.of(), List.of(), Map.of(), null, false);
        var a = (McpInstructionsDeltaAttachment) p.collect(ctx).getFirst();
        assertEquals(List.of("s1"), a.removedNames());
        assertTrue(a.addedNames().isEmpty());
    }

    // ── deferred_tools_delta ─────────────────────────────────────────────

    @Test
    void deferredTools_isEnabledGated() {
        var p = new DeferredToolsDeltaAttachmentProvider();
        assertTrue(p.isEnabled(on(FeatureFlag.DEFERRED_TOOLS_DELTA)));
        assertFalse(p.isEnabled(FeatureFlagRegistry.allOff()));
    }

    @Test
    void deferredTools_firstTurnAnnouncesAllTools() {
        var p = new DeferredToolsDeltaAttachmentProvider();
        var a = (DeferredToolsDeltaAttachment) p.collect(
            ctx(List.of(), List.of("A", "B"), List.of(), Map.of(), null, false)).getFirst();
        assertEquals(List.of("A", "B"), a.addedNames());
        assertEquals(List.of("A", "B"), a.addedLines());
        assertTrue(a.removedNames().isEmpty());
    }

    @Test
    void deferredTools_diffsAdditions() {
        var p = new DeferredToolsDeltaAttachmentProvider();
        var a = (DeferredToolsDeltaAttachment) p.collect(
            ctx(List.of(), List.of("A", "B", "C"), List.of(), Map.of(), List.of("A", "B"), false)).getFirst();
        assertEquals(List.of("C"), a.addedNames());
        assertTrue(a.removedNames().isEmpty());
    }

    @Test
    void deferredTools_diffsRemovals() {
        var p = new DeferredToolsDeltaAttachmentProvider();
        var a = (DeferredToolsDeltaAttachment) p.collect(
            ctx(List.of(), List.of("A"), List.of(), Map.of(), List.of("A", "B"), false)).getFirst();
        assertEquals(List.of("B"), a.removedNames());
        assertTrue(a.addedNames().isEmpty());
    }

    // ── compaction_reminder ──────────────────────────────────────────────

    @Test
    void compactionReminder_isEnabledGated() {
        var p = new CompactionReminderAttachmentProvider();
        assertTrue(p.isEnabled(on(FeatureFlag.COMPACTION_REMINDERS)));
        assertFalse(p.isEnabled(FeatureFlagRegistry.allOff()));
    }

    @Test
    void compactionReminder_emitsAfterCompaction() {
        var p = new CompactionReminderAttachmentProvider();
        var out = p.collect(ctx(List.of(), List.of(), List.of(), Map.of(), null, true));
        assertEquals(1, out.size());
        assertInstanceOf(CompactionReminderAttachment.class, out.getFirst());
    }

    @Test
    void compactionReminder_noEmitBeforeCompaction() {
        var p = new CompactionReminderAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), List.of(), List.of(), Map.of(), null, false)).isEmpty());
    }

    // ── context_efficiency ───────────────────────────────────────────────

    @Test
    void contextEfficiency_isEnabledGatedYetAlwaysNoOp() {
        var p = new ContextEfficiencyAttachmentProvider();
        assertTrue(p.isEnabled(on(FeatureFlag.HISTORY_SNIP)));
        
        assertTrue(p.collect(ctx(List.of(), List.of(), List.of(), Map.of(), null, false)).isEmpty());
    }



    @Test
    void serviceWithAllFlagsOffCollectsNoPhase2() {
// The CLI wires all five providers but FeatureFlagRegistry.allOff

        var svc = new AttachmentService(List.of(
            new DeferredToolsDeltaAttachmentProvider(),
            new AgentListingDeltaAttachmentProvider(),
            new McpInstructionsDeltaAttachmentProvider(),
            new CompactionReminderAttachmentProvider(),
            new ContextEfficiencyAttachmentProvider()),
            FeatureFlagRegistry.allOff());
        var ctx = ctx(List.of(), List.of("Agent", "A", "B"),
            List.of(agent("general-purpose", "x", List.of())),
            Map.of("s1", "do X"), List.of("A"), true);
        assertTrue(svc.collect(ctx).isEmpty());
    }
}
