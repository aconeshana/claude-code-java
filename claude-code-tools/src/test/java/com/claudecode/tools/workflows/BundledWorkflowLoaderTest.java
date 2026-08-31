package com.claudecode.tools.workflows;

import com.claudecode.tools.bundled.BundledResourceCatalog;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledWorkflowLoaderTest {

    @Test
    void loadsTheTwoReleased197WorkflowsWithOfficialVisibility() {
        List<WorkflowDefinition> workflows = BundledWorkflowLoader.load();

        assertEquals(List.of("code-review", "deep-research"), workflows.stream()
            .map(definition -> definition.metadata().name()).toList());
        WorkflowDefinition review = workflows.getFirst();
        assertEquals(WorkflowSource.BUILT_IN, review.source());
        assertTrue(review.hidden());
        assertTrue(Strings.CS.contains(review.script(), "const LEVEL_PARAMS"));
        assertEquals("d6296ff243e40fea3bfe4be6437ea9b80dbe0926ad3ec26a895df25bfb4c3769",
            sha256(review.script()));
        WorkflowDefinition research = workflows.get(1);
        assertFalse(research.hidden());
        assertTrue(Strings.CS.contains(research.script(), "const VOTES_PER_CLAIM = 3"));
        assertEquals("7f2adc771416ccfa8a047b2fc3f5be262225f04ca31acaf839a3d1695e89ca6c",
            sha256(research.script()));
    }

    @Test
    void stableWorkflowLoaderAcceptsAnExplicitBundledRelease() {
        List<WorkflowDefinition> workflows = BundledWorkflowLoader.load(
            BundledResourceCatalog.forVersion("9.9.9"));

        assertEquals(List.of("test-workflow"), workflows.stream()
            .map(definition -> definition.metadata().name()).toList());
        assertTrue(Strings.CS.contains(workflows.getFirst().script(), "TEST WORKFLOW"));
    }

    @Test
    void releasedScriptsCompileInTheSandboxBeforeTheyCanBeLaunched() {
        WorkflowCatalog catalog = new WorkflowCatalog(Path.of("/tmp/workflow-test"),
            List.of(), BundledWorkflowLoader::load);
        WorkflowRuntime runtime = new WorkflowRuntime(_ -> WorkflowAgentResult.of("unused"),
            catalog, 2);

        for (WorkflowDefinition workflow : BundledWorkflowLoader.load()) {
            assertTrue(runtime.validate(workflow).isEmpty(), workflow.metadata().name());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
