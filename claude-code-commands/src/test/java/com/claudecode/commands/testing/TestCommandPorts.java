package com.claudecode.commands.testing;

import com.claudecode.commands.tooling.ToolingCommandPorts;
import java.nio.file.Files;
import java.util.List;
import org.apache.commons.lang3.Strings;

/** Pure fake command ports for commands-module tests. */
public final class TestCommandPorts {
    private TestCommandPorts() {}

    public static ToolingCommandPorts markdownResources() {
        ToolingCommandPorts none = ToolingCommandPorts.none();
        return new ToolingCommandPorts(directory -> {
            if (!Files.isDirectory(directory)) return List.of();
            try (var paths = Files.walk(directory)) {
                return paths.filter(Files::isRegularFile)
                    .filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".md"))
                    .sorted().toList();
            }
        }, none.plans(), none.tasks(), none.skillAttribution(),
            none.collaboration(), none.sandbox());
    }
}
