package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** The command line {@code claude web} accepts. */
class WebCliCommandOptionsTest {

    @Test
    void runsInTheForegroundByDefault() {
        WebCliCommand command = new WebCliCommand();
        new CommandLine(command).parseArgs();

        assertThat(command.detach).isFalse();
    }

    @Test
    void dashDDetaches() {
        WebCliCommand command = new WebCliCommand();
        new CommandLine(command).parseArgs("-d");

        assertThat(command.detach).isTrue();
    }

    @Test
    void longFormDetaches() {
        WebCliCommand command = new WebCliCommand();
        new CommandLine(command).parseArgs("--detach");

        assertThat(command.detach).isTrue();
    }
}
