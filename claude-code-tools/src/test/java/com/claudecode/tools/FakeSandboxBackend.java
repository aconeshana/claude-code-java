package com.claudecode.tools;

import com.claudecode.core.engine.SandboxConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.claudecode.tools.sandbox.SandboxManager;

/**
 * Test double for {@link SandboxManager}: controllable availability, and
 * {@link #wrap} returns a fixed, recognizable argv so callers can assert the
 * sandbox decision was applied without spawning a real process or requiring
 * {@code sandbox-exec}/{@code bwrap} on the host.
 */
public class FakeSandboxBackend extends SandboxManager {

    private final boolean available;
    private final List<String> wrappedPrefix;

    public FakeSandboxBackend(boolean available) {
        this(available, List.of("fake-sandbox"));
    }

    public FakeSandboxBackend(boolean available, List<String> wrappedPrefix) {
        this.available = available;
        this.wrappedPrefix = wrappedPrefix;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableReason() {
        return "fake sandbox backend unavailable";
    }

    @Override
    public List<String> wrap(String command, Path cwd, SandboxConfig cfg) {
        List<String> out = new ArrayList<>(wrappedPrefix);
        out.add(command);
        return out;
    }
}
