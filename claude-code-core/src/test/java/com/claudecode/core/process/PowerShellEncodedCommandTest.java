package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PowerShellEncodedCommandTest {

    @Test
    void encodingProducesAStableDirectInvocationAndRoundTripsTheScript() {
        String script = "$raw=[Console]::In.ReadToEnd(); Write-Output $raw.Length";

        String command = PowerShellEncodedCommand.encode(script);
        List<String> argv = PowerShellEncodedCommand.argv(command).orElseThrow();

        assertEquals(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
            "-EncodedCommand", argv.getLast()), argv);
        assertEquals(script, new String(Base64.getDecoder().decode(argv.getLast()),
            StandardCharsets.UTF_16LE));
        assertTrue(PowerShellEncodedCommand.argv("powershell.exe -Command unsafe").isEmpty());
    }
}
