package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code claude web status}: reports the gateway and the sessions it serves.
 *
 * <p>Read-only. It answers the question that {@code claude web} itself cannot —
 * which build is actually serving the browser, and how long it has been up —
 * without changing anything, so it is safe to run out of curiosity or while
 * diagnosing a stale gateway.
 *
 * <p>The access token is redacted here even though {@code claude web} prints it
 * in full: that URL exists to be opened, whereas this output is for reading,
 * and would otherwise end up in shell history and pasted issue reports.
 * {@code --show-url} is the explicit way to get it back — once the URL has
 * scrolled away at startup there is no other route to it.
 */
@Command(
    name = "status",
    description = "Report the running claude web gateway and its registered sessions",
    mixinStandardHelpOptions = true
)
public class WebCliStatusCommand implements Callable<Integer> {

    private final CliOutput out = CliOutput.systemOut();

    @Option(names = "--show-url",
        description = "Print the full URL including its access token; the default redacts it,"
            + " because this output otherwise ends up in shell history and bug reports")
    boolean showUrl;

    @Override
    public Integer call() {
        try {
            return WebCliLifecycle.status(
                ClaudePaths.WEB_GATEWAY_JSON, ClaudePaths.WEB_WORKERS_DIR, out, showUrl);
        } finally {
            // System.exit follows and does not drain the stream buffers; see
            // WebCliCommand#call for why this is kept despite nothing being
            // measurably lost at these sizes.
            out.flush();
        }
    }
}
