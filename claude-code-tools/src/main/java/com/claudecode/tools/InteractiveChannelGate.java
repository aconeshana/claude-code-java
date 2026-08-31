package com.claudecode.tools;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * Shared gate for tools whose approval flow requires a terminal.
 */
public final class InteractiveChannelGate {

    private InteractiveChannelGate() {}

    public static boolean terminalInteractionAvailable() {
        boolean channelsFeature = FeatureGate.isEnabled(FeatureGate.Flag.KAIROS)
            || FeatureGate.isEnabled(FeatureGate.Flag.KAIROS_CHANNELS);
        if (!channelsFeature) return true;
        String allowed = SubprocessEnvironment.get("CLAUDE_CODE_ALLOWED_CHANNELS");
        return StringUtils.isBlank(allowed);
    }
}
