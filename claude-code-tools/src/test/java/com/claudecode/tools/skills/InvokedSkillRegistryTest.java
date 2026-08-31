package com.claudecode.tools.skills;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone (non-{@link InvokedSkillRegistry#global}) instance behavior:
 * per-agent scoping, re-invoke overwrite, and entry field mapping used by
 * {@code DefaultManualCompactStrategy.buildInvokedSkillsAttachment}.
 */
class InvokedSkillRegistryTest {

    private static Skill testSkill(String name, String content) {
        return new Skill(name, "desc", List.of(), content, Path.of("/skills/" + name + ".md"),
            Skill.SkillSource.USER, null, null, null, null);
    }

    @Test
    void entriesForReturnsEmptyWhenNothingRecorded() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();
        assertEquals(List.of(), registry.entriesFor(null));
    }

    @Test
    void recordThenEntriesForReturnsMatchingEntry() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();
        registry.record(null, testSkill("deploy", "steps..."));

        List<InvokedSkillRegistry.Entry> entries = registry.entriesFor(null);
        assertEquals(1, entries.size());
        InvokedSkillRegistry.Entry entry = entries.getFirst();
        assertEquals("deploy", entry.name());
        assertEquals("userSettings:deploy", entry.path());
        assertEquals("steps...", entry.content());
        assertNull(entry.agentId());
    }

    @Test
    void explicitResolvedContentIsPreservedAcrossCompaction() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();
        registry.record(null, testSkill("verify", "raw $ARGUMENTS"), "resolved target=api");

        InvokedSkillRegistry.Entry entry = registry.entriesFor(null).getFirst();
        assertEquals("resolved target=api", entry.content());
        assertEquals("userSettings:verify", entry.path());
    }

    @Test
    void dynamicPromptInvocationCanRecordItsLogicalSourceWithoutSkillObject() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();

        registry.record(null, "plugin:review", "plugin:plugin:review", "resolved review body");

        InvokedSkillRegistry.Entry entry = registry.entriesFor(null).getFirst();
        assertEquals("plugin:review", entry.name());
        assertEquals("plugin:plugin:review", entry.path());
        assertEquals("resolved review body", entry.content());
        assertNull(entry.agentId());
    }

    @Test
    void entriesAreScopedPerAgentId() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();
        registry.record("agent-a", testSkill("deploy", "a's steps"));
        registry.record("agent-b", testSkill("deploy", "b's steps"));

        List<InvokedSkillRegistry.Entry> forA = registry.entriesFor("agent-a");
        assertEquals(1, forA.size());
        assertEquals("a's steps", forA.getFirst().content());

        List<InvokedSkillRegistry.Entry> forB = registry.entriesFor("agent-b");
        assertEquals(1, forB.size());
        assertEquals("b's steps", forB.getFirst().content());
    }

    @Test
    void reinvokingSameSkillOverwritesPreviousEntry() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();
        registry.record(null, testSkill("deploy", "first version"));
        registry.record(null, testSkill("deploy", "second version"));

        List<InvokedSkillRegistry.Entry> entries = registry.entriesFor(null);
        assertEquals(1, entries.size(), "re-invoking the same skill should overwrite, not duplicate");
        assertEquals("second version", entries.getFirst().content());
    }

    @Test
    void clearForNewSessionDropsMainThreadButPreservesSelectedBackgroundAgents() {
        InvokedSkillRegistry registry = new InvokedSkillRegistry();
        registry.record(null, testSkill("main", "main content"));
        registry.record("agent-keep", testSkill("keep", "kept content"));
        registry.record("agent-drop", testSkill("drop", "dropped content"));

        registry.clearForNewSession(Set.of("agent-keep"));

        assertTrue(registry.entriesFor(null).isEmpty());
        assertEquals(1, registry.entriesFor("agent-keep").size());
        assertTrue(registry.entriesFor("agent-drop").isEmpty());
    }

    @Test
    void globalIsASingletonDistinctFromNewInstances() {
        assertSame(InvokedSkillRegistry.global(), InvokedSkillRegistry.global());
        assertNotSame(InvokedSkillRegistry.global(), new InvokedSkillRegistry());
    }
}
