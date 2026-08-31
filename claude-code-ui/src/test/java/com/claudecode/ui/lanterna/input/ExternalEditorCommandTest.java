package com.claudecode.ui.lanterna.input;

import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class ExternalEditorCommandTest {

    @Test
    void codeAndSublGetWaitArgumentsExactlyOnce() {
        assertEquals(List.of("code", "-w"),
            ExternalEditorCommand.resolve("code").executableAndArguments());
        assertEquals(List.of("subl", "--wait"),
            ExternalEditorCommand.resolve("subl").executableAndArguments());
        assertEquals(List.of("code", "--reuse-window", "-w"),
            ExternalEditorCommand.resolve("code --reuse-window -w").executableAndArguments());
        assertEquals(List.of("subl", "--wait", "--new-window"),
            ExternalEditorCommand.resolve("subl --wait --new-window").executableAndArguments());
    }

    @Test
    void basenameDetectionHandlesEditorPathsAndLeavesTerminalEditorsAlone() {
        assertEquals(List.of("/opt/bin/code", "--new-window", "-w"),
            ExternalEditorCommand.resolve("/opt/bin/code --new-window").executableAndArguments());
        assertEquals(List.of("vi", "-u", "NONE"),
            ExternalEditorCommand.resolve("vi -u NONE").executableAndArguments());
    }

    @Test
    void parserPreservesQuotedArgumentsWithoutShellEvaluation() {
        ExternalEditorCommand command = ExternalEditorCommand.resolve(
            "'/Applications/My Editor' --profile \"work profile\"");
        assertEquals(List.of("/Applications/My Editor", "--profile", "work profile"),
            command.executableAndArguments());
        assertEquals(List.of("vi", "-u", "NONE", "/tmp/input file.md"),
            ExternalEditorCommand.resolve("vi -u NONE").argvFor(Path.of("/tmp/input file.md")));
    }

    @Test
    void parserPreservesWindowsPathSeparators() {
        assertEquals(List.of("D:\\tools\\editor-ok.cmd", "--wait"),
            ExternalEditorCommand.resolve("D:\\tools\\editor-ok.cmd --wait")
                .executableAndArguments());
        assertEquals(List.of("C:\\Program Files\\Editor\\editor.exe", "--profile", "work"),
            ExternalEditorCommand.resolve(
                "\"C:\\Program Files\\Editor\\editor.exe\" --profile work")
                .executableAndArguments());
        assertEquals(List.of("\\\\server\\share\\editor.cmd"),
            ExternalEditorCommand.resolve("\\\\server\\share\\editor.cmd")
                .executableAndArguments());
    }

    @Test
    void malformedOrNulCommandsAreRejectedBeforeProcessBuilder() {
        assertThrows(IllegalArgumentException.class,
            () -> ExternalEditorCommand.resolve("\"unterminated"));
        assertThrows(IllegalArgumentException.class,
            () -> ExternalEditorCommand.resolve("code \\"));
        assertThrows(IllegalArgumentException.class,
            () -> ExternalEditorCommand.resolve("code\0--wait"));
        assertThrows(IllegalArgumentException.class,
            () -> ExternalEditorCommand.resolve("   "));
    }
}
