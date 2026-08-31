package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowJournalTest {

    @TempDir Path temp;

    @Test
    void persistsOfficialStartedAndResultEntriesWithChainedV2Keys() throws Exception {
        WorkflowJournal journal = new WorkflowJournal(temp);
        String first = WorkflowJournal.key("", "inspect", JsonUtils.parseTree(
            "{\"label\":\"ui-only\",\"model\":\"haiku\",\"schema\":{\"type\":\"object\"}}"));
        String sameSemanticOptions = WorkflowJournal.key("", "inspect", JsonUtils.parseTree(
            "{\"schema\":{\"type\":\"object\"},\"model\":\"haiku\",\"phase\":\"ignored\"}"));
        String second = WorkflowJournal.key(first, "verify", JsonUtils.parseTree("{}"));

        assertEquals(first, sameSemanticOptions);
        assertNotEquals(first, second);

        journal.appendStarted(first, "agent-a");
        journal.appendResult(first, "agent-a", "cached output");

        WorkflowJournal.Snapshot loaded = journal.load();
        assertEquals("cached output", loaded.results().get(first).result());
        assertEquals("agent-a", loaded.results().get(first).agentId());
        assertEquals(1, loaded.started().get(first).size());
        assertTrue(Strings.CS.contains(Files.readString(temp.resolve("journal.jsonl")), "\"type\":\"result\""));
    }
}
