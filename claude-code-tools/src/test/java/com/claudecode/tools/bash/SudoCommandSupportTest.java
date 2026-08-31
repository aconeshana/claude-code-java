package com.claudecode.tools.bash;

import com.claudecode.runtime.interaction.SudoPasswordInteraction;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RED tests for the deliberately narrow phase-one sudo interaction boundary. */
class SudoCommandSupportTest {

    @Test
    void preparesOnlyDirectTopLevelSudoAndRequestsPasswordOnStdin() {
        SudoCommandSupport.Prepared prepared = SudoCommandSupport
            .prepare("sudo -u root id", Path.of("/usr/bin/sudo"))
            .orElseThrow();

        assertEquals("/usr/bin/sudo -S -p '' -u root id", prepared.command());
        assertTrue(prepared.requiresPassword());
    }

    @Test
    void acceptsTheTrustedAbsoluteSudoExecutable() {
        SudoCommandSupport.Prepared prepared = SudoCommandSupport
            .prepare("/usr/bin/sudo id", Path.of("/usr/bin/sudo"))
            .orElseThrow();

        assertEquals("/usr/bin/sudo -S -p '' id", prepared.command());
    }

    @Test
    void rejectsAnAbsoluteSudoPathThatDoesNotMatchTheTrustedExecutable() {
        assertFalse(SudoCommandSupport
            .prepare("/bin/sudo id", Path.of("/usr/bin/sudo"))
            .isPresent());

        assertTrue(SudoCommandSupport
            .prepare("/bin/sudo id", Path.of("/bin/sudo"))
            .isPresent());
    }

    @Test
    void doesNotDuplicateExistingNonInteractiveStdinFlags() {
        SudoCommandSupport.Prepared prepared = SudoCommandSupport
            .prepare("sudo -S -p '' id", Path.of("/usr/bin/sudo"))
            .orElseThrow();

        assertEquals("/usr/bin/sudo -S -p '' id", prepared.command());
    }

    @Test
    void rejectsCommandsWhereAnotherProgramCouldImpersonateSudoPrompt() {
        Path trusted = Path.of("/usr/bin/sudo");
        assertFalse(SudoCommandSupport.prepare("./sudo id", trusted).isPresent());
        assertFalse(SudoCommandSupport.prepare("/tmp/repo/sudo id", trusted).isPresent());
        assertFalse(SudoCommandSupport.prepare("env sudo id", trusted).isPresent());
        assertFalse(SudoCommandSupport.prepare("command sudo id", trusted).isPresent());
        assertFalse(SudoCommandSupport.prepare("PATH=./bin:$PATH sudo id", trusted).isPresent());
        assertFalse(SudoCommandSupport.prepare("printf fake | sudo id", trusted).isPresent());
        assertFalse(SudoCommandSupport.prepare("sh -c 'sudo id'", trusted).isPresent());
    }

    @Test
    void identifiesDirectSudoEvenWhenNoTrustedExecutableCanBeResolved() {
        assertTrue(SudoCommandSupport.isDirectPasswordCommand("sudo id"));
        assertTrue(SudoCommandSupport.isDirectPasswordCommand("/bin/sudo id"));
        assertFalse(SudoCommandSupport.isDirectPasswordCommand("sudo -n id"));
        assertFalse(SudoCommandSupport.isDirectPasswordCommand("sudo -k"));
        assertFalse(SudoCommandSupport.isDirectPasswordCommand("sudo -K"));
        assertFalse(SudoCommandSupport.isDirectPasswordCommand("env sudo id"));
        assertFalse(SudoCommandSupport.isDirectPasswordCommand("printf x | sudo id"));
    }

    @Test
    void detectsPasswordRequiringSudoInsideACompoundCommandWithoutTreatingItAsDirect() {
        String command = "sudo -k && sudo -v && sudo -n true && echo \"sudo 密码验证成功\"";

        assertTrue(SudoCommandSupport.containsPasswordRequiringSudo(command));
        assertFalse(SudoCommandSupport.isDirectPasswordCommand(command));
        assertFalse(SudoCommandSupport.prepare(command, Path.of("/usr/bin/sudo")).isPresent());
    }

    @Test
    void ignoresSudoOperationsThatCannotRequestAPassword() {
        assertFalse(SudoCommandSupport.containsPasswordRequiringSudo("sudo -k"));
        assertFalse(SudoCommandSupport.containsPasswordRequiringSudo("sudo -K"));
        assertFalse(SudoCommandSupport.containsPasswordRequiringSudo("sudo -n true"));
        assertFalse(SudoCommandSupport.containsPasswordRequiringSudo("echo safe"));
    }

    @Test
    void neverPlacesCredentialMaterialInThePreparedCommandOrDiagnostics() {
        char[] secret = "correct horse battery staple".toCharArray();
        SudoPasswordInteraction.Result result = SudoPasswordInteraction.Result.provided(secret);
        SudoCommandSupport.Prepared prepared = SudoCommandSupport
            .prepare("sudo printf safe-output", Path.of("/usr/bin/sudo"))
            .orElseThrow();

        assertFalse(prepared.command().contains(new String(secret)));
        assertFalse(prepared.toString().contains(new String(secret)));
        assertFalse(result.toString().contains(new String(secret)));
    }
}
