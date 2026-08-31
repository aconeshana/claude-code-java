package com.claudecode.services.hooks;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;

final class HookTestJson {
    private HookTestJson() { }

    static ObjectNode specific(String event, Path watchPath) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("hookEventName", event);
        node.putArray("watchPaths").add(watchPath.toString());
        return node;
    }
}
