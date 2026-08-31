package com.claudecode.api;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code CLAUDE_CODE_EXTRA_BODY} escape hatch: user-supplied JSON merged into the top level of
 * every Messages API request body.
 */
public final class ExtraBodyParams {

    private static final Logger log = LoggerFactory.getLogger(ExtraBodyParams.class);

    static final String ENV_VAR = "CLAUDE_CODE_EXTRA_BODY";

    private ExtraBodyParams() {}

    /** The parsed extra body for this process, or {@code null} when unset/invalid. */
    public static ObjectNode resolve() {
        return parse(SubprocessEnvironment.get(ENV_VAR));
    }

    /** Pure parse half, exposed so tests need not mutate the process environment. */
    static ObjectNode parse(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        JsonNode parsed = JsonUtils.safeParseJson(raw);
        if (parsed == null || !parsed.isObject()) {

            // debug mode; a malformed value degrades to "no extra body".
            log.debug("{} must be a JSON object, but was given {}", ENV_VAR, raw);
            return null;
        }
        // safeParseJson is LRU-cached and hands back a shared node for the same
        // string; callers merge into this object, so they must get a clone.
        return parsed.deepCopy();
    }
}
