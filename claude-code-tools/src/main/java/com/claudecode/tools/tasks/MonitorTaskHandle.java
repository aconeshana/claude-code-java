package com.claudecode.tools.tasks;

import java.nio.file.Path;




public interface MonitorTaskHandle {
    String getTaskId();
    Path getOutputPath();
    String displaySource();
    boolean kill();
}
