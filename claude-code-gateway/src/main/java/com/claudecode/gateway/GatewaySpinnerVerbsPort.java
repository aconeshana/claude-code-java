package com.claudecode.gateway;

import java.util.List;
import java.util.Map;

/**
 * Consumer-owned boundary for the TUI spinner's verb vocabulary.
 *
 * <p>The gateway must not depend on {@code claude-code-ui}, where
 * {@code SpinnerVerbs} owns the packaged verb lists and the
 * {@code spinnerVerbs} settings splice, so the CLI composition root projects
 * that one resolved vocabulary onto this port. The webui running status then
 * picks its verbs the same way the TUI spinner does: a random active verb per
 * turn, replaced by a tool's fixed verb once that tool starts.
 */
public interface GatewaySpinnerVerbsPort {

    /** The resolved present-participle list (built-ins plus the settings splice). */
    default List<String> activeVerbs() { return List.of(); }

    /** Tool name to its fixed verb; tools absent here take a random active verb. */
    default Map<String, String> toolVerbs() { return Map.of(); }
}
