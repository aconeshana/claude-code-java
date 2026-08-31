package com.claudecode.tools;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaInventedToolsAuditTest {


    @Test
    void workflowToolIsNoLongerMarkedAsJavaInvented() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"),
            "src/main/java/com/claudecode/tools");
        String src = Files.readString(root.resolve("workflows/WorkflowTool.java"), StandardCharsets.UTF_8);
        assertFalse(Strings.CS.contains(src, "JAVA-INVENTED TOOL"));
        assertTrue(Strings.CS.contains(src, "@BuiltInTool(name = \"Workflow\""));
    }
}
