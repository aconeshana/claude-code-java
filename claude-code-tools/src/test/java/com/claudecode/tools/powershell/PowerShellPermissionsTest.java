package com.claudecode.tools.powershell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;


class PowerShellPermissionsTest {

    private static final Path CWD = Path.of("/home/user/project");

    private static ToolPermissionContext ctx() {
        return ToolPermissionContext.of(CWD);
    }

    private static PermissionDecision.Ask ask(String command) {
        PermissionDecision d = PowerShellPermissions.check(command, ctx());
        assertInstanceOf(PermissionDecision.Ask.class, d, "expected Ask for: " + command);
        return (PermissionDecision.Ask) d;
    }

    @Test
    void emptyCommandDenied() {
        assertInstanceOf(PermissionDecision.Deny.class, PowerShellPermissions.check("", ctx()));
    }

    @Test
    void dangerousRemovalPathBlocked() {

        PermissionDecision.Deny denial = assertInstanceOf(PermissionDecision.Deny.class,
            PowerShellPermissions.check("Remove-Item /etc", ctx()));
        assertTrue(Strings.CS.contains(denial.message(), "/etc"));
    }

    @Test
    void readOnlyCommandAllowed() {
        PermissionDecision decision = PowerShellPermissions.check("Get-Content file", ctx());
        if (PowerShellParser.parse("Get-Content file").available()) {
            assertInstanceOf(PermissionDecision.Allow.class, decision);
        } else {
            assertInstanceOf(PermissionDecision.Ask.class, decision,
                "parser-unavailable PowerShell must not be auto-allowed");
        }
    }

    @Test
    void aliasResolvesToDangerousRemoval() {
        // rm → Remove-Item; still catches the dangerous removal.
        PermissionDecision.Deny denial = assertInstanceOf(PermissionDecision.Deny.class,
            PowerShellPermissions.check("rm /etc", ctx()));
        assertTrue(Strings.CS.contains(denial.message(), "/etc"));
    }

    @Test
    void colonSyntaxExtractsPathButNotDangerous() {
        // -Path:<value> colon syntax is parsed; a benign target is merely
        // routed to validation and yields manual approval (no blocked path).
        PermissionDecision.Ask a = ask("Remove-Item -Path:/home/user/project/x");
        assertNull(a.blockedPath());
    }

    @Test
    void providerPathBlocked() {
        // env: provider-qualified path cannot be statically validated → Ask.
        PermissionDecision.Ask a = ask("Set-Content env:HOME x");
        assertEquals("env:HOME", a.blockedPath());
    }

    @Test
    void uncPathBlocked() {
        PermissionDecision.Ask a = ask("Remove-Item //s/share");
        assertEquals("//s/share", a.blockedPath());
    }

    @Test
    void backtickEscapeBlocked() {
        // Backtick escapes cannot be statically validated → Ask.
        PermissionDecision.Ask a = ask("Set-Content a\u0060b.txt x");
        assertEquals("a\u0060b.txt", a.blockedPath());
    }

    @Test
    void cdCompoundWriteRequiresApproval() {
        // Any path operation inside a compound that changes location → Ask.
        PermissionDecision.Ask a = ask("Set-Location x; Remove-Item y");
        assertNull(a.blockedPath());
    }

    @Test
    void redirectTargetValidated() {
        PermissionDecision.Ask a = ask("Set-Content file > //evil");
        assertEquals("//evil", a.blockedPath());
    }

    @Test
    void benignWriteInsideWorkingDirNotBlocked() {
        // A benign write resolves cleanly and is merely routed to manual approval.
        PermissionDecision.Ask a = ask("Set-Content ./notes.txt hi");
        assertNull(a.blockedPath());
    }

    @Test
    void noContextSkipsPathConstraints() {
        // Legacy shape (permCtx == null) preserves classification without validation.
        PermissionDecision read = PowerShellPermissions.check("Get-Content file");
        if (PowerShellParser.parse("Get-Content file").available()) {
            assertInstanceOf(PermissionDecision.Allow.class, read);
        } else {
            assertInstanceOf(PermissionDecision.Ask.class, read);
        }
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Remove-Item file.txt"));
    }

    @Test
    void dynamicAndEncodedPowerShellSyntaxRequiresApproval() {
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Get-Content $(Get-Item secret)", ctx()));
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("& $command", ctx()));
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("pwsh -EncodedCommand AAAA", ctx()));
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Get-Process | ForEach-Object { $_.Name }", ctx()));
    }

    @Test
    void malformedPowerShellSyntaxCannotBeAutoAllowed() {
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Get-Content 'unterminated", ctx()));
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Get-Content @(", ctx()));
    }

    @Test
    void mutatingPipelineCannotUseReadOnlyFastPath() {
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Get-Process | Remove-Item secret.txt", ctx()));
        assertInstanceOf(PermissionDecision.Ask.class,
            PowerShellPermissions.check("Get-Content file.txt | Set-Content out.txt", ctx()));
    }

    @Test
    void newerPowerShellSecurityConstructsRequireApproval() {
        String[] commands = {
            "Get-Content \"$env:HOME\\secret\"",
            "Get-Content file --% ; Remove-Item secret",
            "[System.Diagnostics.Process]::Start('x')",
            "Get-Content file | ForEach-Object { $_.Length }",
            "Get-Process | ForEach-Object -MemberName Kill",
            "Get-Process | ForEach-Object Kill",
            "Register-ScheduledTask -TaskName bad",
            "Invoke-CimMethod -ClassName Win32_Process -MethodName Create",
            "Invoke-Command -ComputerName host -ScriptBlock { Get-Process }",
            "Invoke-Command -ComputerName host -FilePath ./payload.ps1"
        };
        for (String command : commands) {
            assertInstanceOf(PermissionDecision.Ask.class,
                PowerShellPermissions.check(command, ctx()), command);
        }
    }
}
