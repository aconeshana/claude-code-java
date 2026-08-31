package com.claudecode.tools.agent;

public class NoOpSubAgentFactory implements SubAgentFactory {

    @Override
    public SubAgentResult runSubAgent(SubAgentRequest request) {
        return SubAgentResult.of("Sub-agent not configured: " + request.prompt());
    }
}