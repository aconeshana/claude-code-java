package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

class CliInteractiveRuntimeAssemblerTest {

    @Test
    void oneInteractiveAssemblySharesFeatureAndSessionInstancesAcrossConsumers() {
        CliInteractiveRuntimeAssembler assembler = new CliInteractiveRuntimeAssembler();
        var application = assembler.application(null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null);
        var features = assembler.features(null, null, List::of, _ -> {}, null);
        var launch = assembler.launch(null, false, null, "Named session", false, null, false, null,
            null, null, null, null, null);
        var wiring = assembler.assemble(application, features, launch);

        assertSame(assembler.sessions(), wiring.application().sessions());
        assertSame(assembler.tasks(), wiring.features().taskRegistry());
        assertSame(assembler.workflows(), wiring.features().workflowRuns());
        assertSame(assembler.invokedSkills(), wiring.features().invokedSkills());
        assertSame(assembler.loopWakeups(), wiring.features().loopWakeups());
        assertSame(application, wiring.application());
        assertSame(features, wiring.features());
        assertSame(launch, wiring.launch());
        assertEquals("Named session", launch.initialSessionName());
    }

    @Test
    void separateAssemblersOwnSeparateAdapterSets() {
        CliInteractiveRuntimeAssembler first = new CliInteractiveRuntimeAssembler();
        CliInteractiveRuntimeAssembler second = new CliInteractiveRuntimeAssembler();

        assertNotSame(first.sessions(), second.sessions());
        assertNotSame(first.toolingCommands(), second.toolingCommands());
    }
}
