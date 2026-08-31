package com.claudecode.core.process;

import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform-neutral process-tree inspection backed by Java {@link ProcessHandle}.
 *
 * <ul>
 *   <li>{@code isProcessRunning},
 *       {@code getAncestorPidsAsync}, {@code getProcessCommand},
 *       {@code getAncestorCommandsAsync}, and {@code getChildPids}.</li>
 * </ul>
 */
public final class ProcessUtils {
    private ProcessUtils() {}

    public static boolean isProcessRunning(long pid) {
        return pid > 1 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static List<Long> ancestorPids(long pid, int maxDepth) {
        List<Long> result = new ArrayList<>();
        ProcessHandle current = ProcessHandle.of(pid).orElse(null);
        for (int i = 0; current != null && i < Math.max(0, maxDepth); i++) {
            current = current.parent().orElse(null);
            if (current == null || current.pid() <= 1) break;
            result.add(current.pid());
        }
        return List.copyOf(result);
    }

    public static String processCommand(long pid) {
        return ProcessHandle.of(pid).map(ProcessUtils::commandLine).orElse(null);
    }

    public static List<String> ancestorCommands(long pid, int maxDepth) {
        List<String> result = new ArrayList<>();
        ProcessHandle current = ProcessHandle.of(pid).orElse(null);
        for (int i = 0; current != null && i < Math.max(0, maxDepth); i++) {
            String command = commandLine(current);
            if (StringUtils.isNotBlank(command)) result.add(command);
            current = current.parent().orElse(null);
        }
        return List.copyOf(result);
    }

    public static List<Long> childPids(long pid) {
        try {
            return ProcessHandle.of(pid)
                .map(handle -> handle.children().map(ProcessHandle::pid).toList())
                .orElseGet(List::of);
        } catch (RuntimeException _) {

            // is best-effort and permission/platform failures return no children.
            return List.of();
        }
    }

    private static String commandLine(ProcessHandle handle) {
        ProcessHandle.Info info = handle.info();
        return info.commandLine().orElseGet(() -> info.command().orElse(null));
    }
}
