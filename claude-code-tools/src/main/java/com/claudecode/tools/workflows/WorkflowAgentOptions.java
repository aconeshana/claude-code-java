package com.claudecode.tools.workflows;

import com.fasterxml.jackson.databind.JsonNode;

/** Model-visible options accepted by the workflow DSL's {@code agent} hook. */
public record WorkflowAgentOptions(
    String label,
    String phase,
    JsonNode schema,
    String model,
    String effort,
    String isolation,
    String agentType,
    Long stallMs) {}
