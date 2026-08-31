package com.claudecode.runtime.sessionhost;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;
import java.util.List;

/**
 * Current explicit/effective effort and choices valid for one host session.
 */
@Explanation("Session-scoped effort state for semantic remote endpoints")
public record SessionHostEffortState(String current, String effective, List<String> efforts) {
    public SessionHostEffortState {
        current = StringUtils.isBlank(current) ? "auto" : current.trim();
        effective = effective == null ? "" : effective.trim();
        efforts = List.copyOf(efforts == null ? List.of() : efforts);
    }
}
