package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * {@code claude web stop}: ends the running gateway.
 *
 * <p>Only the gateway. It serves the web UI by forwarding to TUI sessions and
 * owns no session of its own, so stopping it closes browser tabs and leaves
 * every TUI running; the next {@code claude web} picks those sessions back up
 * from the worker directory they never left.
 *
 * <p>Idempotent: stopping a gateway that is not running succeeds and says so.
 */
@Command(
    name = "stop",
    description = "Stop the running claude web gateway (running TUI sessions are left alone)",
    mixinStandardHelpOptions = true
)
public class WebCliStopCommand implements Callable<Integer> {

    private final CliOutput out = CliOutput.systemOut();
    private final CliOutput err = CliOutput.systemErr();

    @Override
    public Integer call() {
        try {
            return WebCliLifecycle.stop(ClaudePaths.WEB_GATEWAY_JSON, out, err);
        } finally {
            // System.exit follows and does not drain the stream buffers; see
            // WebCliCommand#call for why this is kept despite nothing being
            // measurably lost at these sizes.
            out.flush();
            err.flush();
        }
    }
}
