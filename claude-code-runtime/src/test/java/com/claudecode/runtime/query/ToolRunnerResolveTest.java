package com.claudecode.runtime.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;


class ToolRunnerResolveTest {

    @Test
    void resolveAlwaysReturnsConcurrentToolRunner() {
        assertInstanceOf(ConcurrentToolRunner.class, ToolRunner.resolve());
    }
}
