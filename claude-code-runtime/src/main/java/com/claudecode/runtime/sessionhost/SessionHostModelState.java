package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.List;

/**
 * Current model selection and the choices valid for one host session.
 */
@Explanation("Session-scoped model state for semantic remote endpoints")
public record SessionHostModelState(String current, List<SessionHostModelOption> models) {

    public SessionHostModelState {
        current = current == null ? "" : current.trim();
        models = List.copyOf(models == null ? List.of() : models);
    }
}
