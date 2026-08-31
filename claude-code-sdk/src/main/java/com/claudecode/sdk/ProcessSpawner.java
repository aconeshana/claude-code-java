package com.claudecode.sdk;

/**
 * Testable/customizable child-process creation boundary.
custom process spawner option.</li></ul>
 */
@FunctionalInterface
public interface ProcessSpawner {
    Process spawn(ProcessCommand command) throws Exception;
}
