package com.claudecode.commands;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.claudecode.commands.dream.DreamPort;
import com.claudecode.commands.insights.InsightsPort;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.core.message.Usage;
import com.claudecode.runtime.doctor.DoctorPort;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CommandApplicationPortsTest {

    @Test
    void builderCollectsApplicationRuntimePortsBehindOneBoundary() {
        DoctorPort doctor = () -> null;
        DreamPort dream = new DreamPort() {
            @Override public boolean available() { return true; }
            @Override public String buildPrompt(String workingDirectory) { return workingDirectory; }
        };
        PluginRuntimePort plugins = new PluginRuntimePort() {
            @Override public Summary summary() { return new Summary(0, 0, 0, 0, 0); }
            @Override public RefreshResult refresh() {
                return new RefreshResult(0, 0, 0, 0, 0, 0, 0, 0);
            }
        };
        Supplier<InsightsPort> insights = () -> null;

        CommandContext context = CommandContext.builder(
                "model", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .doctor(doctor)
            .dream(dream)
            .pluginRuntime(plugins)
            .insightsPipeline(insights)
            .build();

        assertSame(doctor, context.application().doctor());
        assertSame(dream, context.application().dream());
        assertSame(plugins, context.application().plugins());
        assertSame(insights, context.application().insights());
        assertSame(context.application().settings(), context.application().settings());
    }
}
