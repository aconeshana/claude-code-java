package com.claudecode.tools;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.tools.output.SyntheticOutputTool;

class RemainingPromptPortsTest {

    @Test
    void syntheticOutputTool_descriptionPorted() {


// does not surface as description.
        String d = new SyntheticOutputTool(JsonUtils.getMapper().createObjectNode().put("type", "object"))
            .description();
        assertTrue(Strings.CS.contains(d, "Return structured output in the requested format"),
            "description must match TS description(); got: " + d);
    }
}
