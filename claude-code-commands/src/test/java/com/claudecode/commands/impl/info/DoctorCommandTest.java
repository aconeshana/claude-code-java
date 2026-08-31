package com.claudecode.commands.impl.info;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.message.Usage;
import com.claudecode.runtime.doctor.DoctorReport;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DoctorCommandTest {

    private final DoctorCommand cmd = new DoctorCommand();

    @Test
    void ripgrepStatusDistinguishesVendorAndSystemModes() {
        assertEquals("available (vendor)", DoctorCommand.formatRipgrepStatus(
            new DoctorReport.RipgrepStatus(
                true,
                DoctorReport.RipgrepMode.BUILTIN,
                null)));
        assertEquals("available (rg)", DoctorCommand.formatRipgrepStatus(
            new DoctorReport.RipgrepStatus(
                true,
                DoctorReport.RipgrepMode.SYSTEM,
                "rg")));
    }

    @Test
    void description_matchesTsCopy() {
        assertEquals("Diagnose and verify your Claude Code installation and settings", cmd.description());
    }

    @Test
    void isAvailable_trueByDefault() {
        assertTrue(cmd.isAvailable(CommandContext.minimal()));
    }

    @Test
    void isAvailable_falseWhenDisableEnvSet() {

        // we can't set process env from a unit test, so verify the pure predicate instead.
        assertFalse(invokeIsEnvTruthy(null));
        assertTrue(invokeIsEnvTruthy("1"));
        assertTrue(invokeIsEnvTruthy("true"));
        assertTrue(invokeIsEnvTruthy("YES"));
        assertTrue(invokeIsEnvTruthy("on"));
        assertFalse(invokeIsEnvTruthy("0"));
        assertFalse(invokeIsEnvTruthy("false"));
        assertFalse(invokeIsEnvTruthy("maybe"));
    }

    private static boolean invokeIsEnvTruthy(String v) {
        return EnvUtils.isEnvTruthy(v);
    }

    @Test
    void execute_withNullLauncherFallsBackToTextReport(@TempDir Path tmp) {
        CommandContext ctx = CommandContext.builder(
            "claude-sonnet-4-20250514",
            List::of,
            () -> {},
            _ -> {},
            () -> Usage.EMPTY,
            _ -> 0.0,
            tmp.toString(),
            false)
            .doctor(() -> new DoctorReport(
                new DoctorReport.RuntimeInfo("test"),
                new DoctorReport.RipgrepStatus(true, DoctorReport.RipgrepMode.BUILTIN, null),
                List.of(), List.of(), List.of(),
                new DoctorReport.ContextUsage(null, null, null),
                List.of(), List.of(), List.of(), List.of()))
            .build();

        CommandResult r = cmd.execute(ctx, "");

        assertNotNull(r.output());
        assertTrue(Strings.CS.contains(r.output(), "Diagnostics"));
        assertTrue(Strings.CS.contains(r.output(), "Search:"));
        assertFalse(Strings.CS.contains(r.output(), "Maven:"));
        assertFalse(Strings.CS.contains(r.output(), "Gradle:"));

        // along with the old bogus hardcoded placeholder URL.
        assertFalse(Strings.CS.contains(r.output(), "Network:"));
        assertFalse(Strings.CS.contains(r.output(), "minimaxi.com"));
    }

    @Test
    void execute_withLauncherSkipsTextOutput() {
        boolean[] called = {false};
        CommandContext ctx = CommandContext.builder(
            "claude-sonnet-4-20250514",
            List::of,
            () -> {},
            _ -> {},
            () -> Usage.EMPTY,
            _ -> 0.0,
            System.getProperty("user.dir"),
            false)
            .doctorDialogLauncher(() -> called[0] = true)
            .build();

        CommandResult r = cmd.execute(ctx, "");

        assertTrue(called[0]);
        assertEquals("", r.output());
    }
}
