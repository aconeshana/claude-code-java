package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.ToolExecutionContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Bridges engine progress updates to their.
 */
public class LanternaProgressSink implements ToolExecutionContext.ProgressSink {

    private volatile LanternaReplScreen screen;

    public void setScreen(LanternaReplScreen screen) {
        this.screen = screen;
    }

    @Override
    public void accept(ToolExecutionContext.ProgressUpdate update) {
        if (screen != null && update != null) {
            if (update.complete()) {
                screen.clearAgentProgress(update.toolUseId());
            } else if (Strings.CS.equals("agent_background_hint", update.dataType())
                    && update.toolUseId() != null) {
                screen.showAgentBackgroundHint(update.toolUseId());
            } else if (!Strings.CS.equals("agent_progress", update.dataType())) {
                screen.showAgentProgress(compose(update));
            }
        }
    }

    /**
     * Renders an update into the status-line string.
     */
    private static String compose(ToolExecutionContext.ProgressUpdate update) {
        if (StringUtils.isEmpty(update.dataType())) {
            return update.message() == null ? "" : update.message();
        }
        StringBuilder sb = new StringBuilder();
        if (update.message() != null) {
            sb.append(update.message());
        }
        String out = update.output();
        if (StringUtils.isNotBlank(out)) {
            sb.append("\n").append(out);
        }
        return sb.toString();
    }
}
