package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SudoCommandAdapterTest {

    @Test
    void directSudoUsesTheSharedInteractionAndTrustedExecutable() {
        AtomicInteger requests = new AtomicInteger();
        SudoCommandAdapter.Result result = SudoCommandAdapter.prepare(
            "sudo -v", Path.of("/usr/bin/sudo"), request -> {
                requests.incrementAndGet();
                assertEquals("/usr/bin/sudo", request.executable());
                assertEquals("sudo -v", request.command());
                return SudoPasswordInteraction.Result.provided("secret".toCharArray());
            });

        SudoCommandAdapter.Result.Prepared prepared = assertInstanceOf(
            SudoCommandAdapter.Result.Prepared.class, result);
        assertEquals("/usr/bin/sudo -S -p '' -v", prepared.command());
        assertEquals(1, requests.get());
        assertTrue(prepared.toString().contains("redacted"));
        prepared.close();
    }

    @Test
    void compoundPasswordSudoFailsClosedWithoutRequestingASecret() {
        AtomicInteger requests = new AtomicInteger();
        SudoCommandAdapter.Result result = SudoCommandAdapter.prepare(
            "sudo -k && sudo -v", Path.of("/usr/bin/sudo"), _ -> {
                requests.incrementAndGet();
                return SudoPasswordInteraction.Result.cancelled();
            });

        SudoCommandAdapter.Result.Rejected rejected = assertInstanceOf(
            SudoCommandAdapter.Result.Rejected.class, result);
        assertTrue(rejected.message().contains("direct sudo command"));
        assertEquals(0, requests.get());
    }

    @Test
    void timestampInvalidationPassesThroughWithoutOpeningAnInteraction() {
        AtomicInteger requests = new AtomicInteger();
        SudoCommandAdapter.Result result = SudoCommandAdapter.prepare(
            "sudo -k", Path.of("/usr/bin/sudo"), _ -> {
                requests.incrementAndGet();
                return SudoPasswordInteraction.Result.cancelled();
            });

        assertInstanceOf(SudoCommandAdapter.Result.Passthrough.class, result);
        assertEquals(0, requests.get());
    }
}
