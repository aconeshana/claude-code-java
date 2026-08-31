package com.claudecode.sdk;

import java.util.Objects;
import org.apache.commons.lang3.Strings;

/**
 * In-process MCP configuration carried across the SDK/CLI control bridge.
{@code McpSdkServerConfigWithInstance}.</li></ul>
 */
public record McpSdkServerConfigWithInstance(String type, String name, SdkMcpServer instance)
        implements McpServerConfig {
    public McpSdkServerConfigWithInstance {
        if (!Strings.CS.equals("sdk", type)) {
            throw new IllegalArgumentException("type must be sdk");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(instance, "instance");
    }

    public McpSdkServerConfigWithInstance(String name, SdkMcpServer instance) {
        this("sdk", name, instance);
    }
}
