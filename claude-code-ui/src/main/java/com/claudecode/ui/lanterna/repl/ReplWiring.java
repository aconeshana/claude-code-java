package com.claudecode.ui.lanterna.repl;

/**
 * Atomic interactive runtime bundle assembled once by the CLI composition root.
 */
public record ReplWiring(
    ReplApplicationPorts application,
    ReplFeatureRuntime features,
    ReplLaunchState launch,
    ReplStartupReadiness startupReadiness
) {
    public ReplWiring(ReplApplicationPorts application,
                      ReplFeatureRuntime features,
                      ReplLaunchState launch) {
        this(application, features, launch, ReplStartupReadiness.ready());
    }

    public ReplWiring {
        if (startupReadiness == null) startupReadiness = ReplStartupReadiness.ready();
    }
}
