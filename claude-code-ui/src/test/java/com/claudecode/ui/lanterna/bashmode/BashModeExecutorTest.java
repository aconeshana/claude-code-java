package com.claudecode.ui.lanterna.bashmode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.tools.bash.SudoCommandAdapter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class BashModeExecutorTest {

    @Test
    void recognizesExplicitImgcatExecutablesOnly() {
        assertTrue(BashModeExecutor.isImgcatCommand("imgcat image.png"));
        assertTrue(BashModeExecutor.isImgcatCommand("  command imgcat < image.png"));
        assertTrue(BashModeExecutor.isImgcatCommand(
            "/Applications/iTerm.app/Contents/Resources/utilities/imgcat image.png"));

        assertFalse(BashModeExecutor.isImgcatCommand("echo imgcat image.png"));
        assertFalse(BashModeExecutor.isImgcatCommand("imgcatapult image.png"));
        assertFalse(BashModeExecutor.isImgcatCommand("sudo imgcat image.png"));
    }

    @Test
    void resolvesQuotedRelativeImagePathWithoutRunningShell() {
        Path cwd = Path.of("/tmp/project");
        assertEquals(cwd.resolve("generated image.png"),
            BashModeExecutor.resolveImgcatPath(
                "imgcat 'generated image.png'", cwd));
        assertEquals(Path.of("/tmp/image.png"),
            BashModeExecutor.resolveImgcatPath(
                "/Applications/iTerm.app/Contents/Resources/utilities/imgcat -- /tmp/image.png",
                cwd));
        assertEquals(cwd.resolve("image.png"),
            BashModeExecutor.resolveImgcatPath("imgcat < image.png", cwd));
    }

    @Test
    void bashModeDirectSudoUsesTheSharedPasswordInteraction() {
        AtomicInteger requests = new AtomicInteger();

        SudoCommandAdapter.Result result = BashModeExecutor.prepareSudoCommand(
            "sudo -v", _ -> {
                requests.incrementAndGet();
                return SudoPasswordInteraction.Result.provided("secret".toCharArray());
            });

        // This path resolves the trusted sudo from the filesystem rather than
        // taking it as an argument, so the absolute path is the host's business:
        // Debian/Ubuntu symlink /usr/bin/sudo into /usr/lib, and some CI images
        // put the real file elsewhere entirely. Pinning it here made the test
        // assert the capture host instead of the product contract — that the
        // executable is resolved without consulting PATH and is driven with the
        // non-interactive prompt. Skip when no trusted sudo exists at all, which
        // is a supported outcome (rejection) rather than a failure.
        Assumptions.assumeTrue(result instanceof SudoCommandAdapter.Result.Prepared,
            "no trusted system sudo on this host: " + result);

        SudoCommandAdapter.Result.Prepared prepared =
            assertInstanceOf(SudoCommandAdapter.Result.Prepared.class, result);
        assertTrue(Strings.CS.startsWith(prepared.command(), "/"),
            "resolved without PATH, so the command carries an absolute path: "
                + prepared.command());
        assertTrue(Strings.CS.endsWith(prepared.command(), "/sudo -S -p '' -v"),
            prepared.command());
        assertEquals(1, requests.get());
        prepared.close();
    }
}
