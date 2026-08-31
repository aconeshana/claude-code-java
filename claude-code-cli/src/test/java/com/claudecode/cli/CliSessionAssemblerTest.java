package com.claudecode.cli;

import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliSessionAssemblerTest {

    @Test
    void setupHookAppliesBlockingAndDisplayMessages(@TempDir Path cwd) {
        HookEngine hooks = new HookEngine(HooksSettings.EMPTY, cwd.toString()) {
            @Override
            public HookDispatcher.HookOutcome dispatchSetupWithOutcome(String trigger) {
                return new HookDispatcher.HookOutcome(
                    false, null, List.of("setup blocked"), false, null,
                    "setup status");
            }
        };
        StringWriter rendered = new StringWriter();
        CliOutput output = CliOutput.borrowed(new PrintWriter(rendered, true));

        CliSessionAssembler.runSetupHook("init", hooks, null, output);

        assertEquals("setup blocked\nsetup status\n", rendered.toString());
    }
}
