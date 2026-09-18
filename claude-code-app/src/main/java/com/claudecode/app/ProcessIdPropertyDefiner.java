package com.claudecode.app;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * Supplies the launching JVM's process id to {@code logback.xml} as the {@code ccPid}
 * configuration property, so every line written to the shared
 * {@code /tmp/claude-code-java.log} can be attributed to one CLI process.
 *
 * <p>The log file is opened in append mode and is shared by every concurrently running
 * session (interactive TUI, headless {@code -p} runs, SDK-spawned child processes,
 * daemon workers). Without a per-process discriminator the interleaved lines cannot be
 * separated, which makes the file useless for diagnosing anything that involves more
 * than one process.
 *
 * <p>Resolution must happen while Joran parses the configuration, not from application
 * code. A {@code System.setProperty("ccPid", …)} at the top of {@code main()} loses the
 * race: any class with a {@code static final Logger} field — {@code ClaudeCodeCli}
 * itself included — triggers logback initialisation during class loading of the entry
 * point, i.e. strictly before the first statement of {@code main()} runs, and the
 * pattern then renders its literal fallback. A {@code <define>} element evaluates this
 * definer at parse time, so the value is always present.
 *
 * <p>GraalVM note: logback instantiates definers reflectively, so this type is
 * registered in {@code META-INF/native-image/com.claudecode/claude-code-app/
 * reachability-metadata.json}. Without that registration the native image silently
 * renders {@code ccPid_IS_UNDEFINED}.
 *
 * <ul>
 *   <li>No original-product counterpart. The TypeScript client has no equivalent shared
 *       logback-style debug file, so this class covers no upstream source; it is a
 *       Java-side diagnostic facility introduced by this port.</li>
 * </ul>
 */
public final class ProcessIdPropertyDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return String.valueOf(ProcessHandle.current().pid());
    }
}
