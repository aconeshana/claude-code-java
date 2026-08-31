package com.claudecode.api;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code CLAUDE_CODE_EXTRA_METADATA} escape hatch: user-supplied JSON merged into the {@code
 * user_id} sub-object of every Messages API request's {@code metadata} block.
 */
public final class ExtraMetadata {

    private static final Logger log = LoggerFactory.getLogger(ExtraMetadata.class);

    static final String ENV_VAR = "CLAUDE_CODE_EXTRA_METADATA";

    private ExtraMetadata() {}

    /** The parsed extra metadata for this process, or {@code null} when unset/invalid. */
    public static ObjectNode resolve() {
        return parse(SubprocessEnvironment.get(ENV_VAR));
    }

    /** Pure parse half, exposed so tests need not mutate the process environment. */
    static ObjectNode parse(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        JsonNode parsed = JsonUtils.safeParseJson(raw);
        if (parsed == null || !parsed.isObject() || parsed.isArray()) {

            // surfaces in debug mode; a malformed value degrades to "no extra
            // metadata". Arrays are rejected alongside non-objects.
            log.debug("{} must be a JSON object, but was given {}", ENV_VAR, raw);
            return null;
        }
        // safeParseJson is LRU-cached and hands back a shared node for the same
        // string; callers merge into this object, so they must get a clone.
        return parsed.deepCopy();
    }
}
