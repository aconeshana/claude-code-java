package com.claudecode.commands.dream;

/** Host-owned manual memory-consolidation capability for {@code /dream}. */
public interface DreamPort {
    boolean available();
    String buildPrompt(String workingDirectory);
}
