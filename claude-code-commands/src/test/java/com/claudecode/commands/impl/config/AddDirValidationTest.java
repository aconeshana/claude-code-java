package com.claudecode.commands.impl.config;


import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class AddDirValidationTest {

    @TempDir Path tempDir;

    @Test
    void emptyPath_returnsEmptyPathResult() {
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace("", tempDir.toString(), List.of(tempDir));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.EmptyPath.class, result);
        assertEquals("Please provide a directory path.", AddDirValidation.addDirHelpMessage(result));
    }

    @Test
    void blankPath_returnsEmptyPathResult() {
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace("   ", tempDir.toString(), List.of(tempDir));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.EmptyPath.class, result);
    }

    @Test
    void nonExistentPath_returnsPathNotFound() {
        Path missing = tempDir.resolve("does-not-exist");
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace(missing.toString(), tempDir.toString(), List.of(tempDir));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.PathNotFound.class, result);
        String message = AddDirValidation.addDirHelpMessage(result);
        assertTrue(Strings.CS.contains(message, missing.toString()));
        assertTrue(Strings.CS.contains(message, "was not found"));
    }

    @Test
    void fileNotDirectory_returnsNotADirectoryWithParentHint() throws IOException {
        Path file = tempDir.resolve("some-file.txt");
        Files.writeString(file, "hi");

        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace(file.toString(), tempDir.toString(), List.of(tempDir));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.NotADirectory.class, result);
        String message = AddDirValidation.addDirHelpMessage(result);
        assertTrue(Strings.CS.contains(message, "is not a directory"));
        assertTrue(Strings.CS.contains(message, tempDir.toString()), "must suggest the parent directory: " + message);
    }

    @Test
    void alreadyInsideCwd_returnsAlreadyInWorkingDirectory() throws IOException {
        Path child = Files.createDirectory(tempDir.resolve("child"));
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace(child.toString(), tempDir.toString(), List.of(tempDir));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.AlreadyInWorkingDirectory.class, result);
        String message = AddDirValidation.addDirHelpMessage(result);
        assertTrue(Strings.CS.contains(message, "already accessible"));
        assertTrue(Strings.CS.contains(message, tempDir.toString()));
    }

    @Test
    void alreadyInsideAdditionalDir_returnsAlreadyInWorkingDirectory() throws IOException {
        Path otherRoot = Files.createDirectory(tempDir.resolve("other-root"));
        Path grandchild = Files.createDirectory(otherRoot.resolve("grandchild"));
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace(grandchild.toString(),
                tempDir.resolve("unrelated-cwd").toString(),
                List.of(tempDir.resolve("unrelated-cwd"), otherRoot));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.AlreadyInWorkingDirectory.class, result);
    }

    @Test
    void newSiblingDirectory_returnsSuccess() throws IOException {
        Path sibling = Files.createDirectory(tempDir.resolve("sibling-project"));
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace(sibling.toString(),
                tempDir.resolve("some-other-cwd").toString(),
                List.of(tempDir.resolve("some-other-cwd")));
        assertInstanceOf(AddDirValidation.AddDirectoryResult.Success.class, result);
        assertEquals(sibling.toString(),
            ((AddDirValidation.AddDirectoryResult.Success) result).absolutePath());
        assertTrue(Strings.CS.startsWith(AddDirValidation.addDirHelpMessage(result), "Added "));
    }

    @Test
    void relativePath_resolvedAgainstWorkingDirectory() throws IOException {
        Path sibling = Files.createDirectory(tempDir.resolve("relative-sibling"));
        AddDirValidation.AddDirectoryResult result =
            AddDirValidation.validateDirectoryForWorkspace("relative-sibling",
                tempDir.toString(), List.of(tempDir));
        // Resolves relative to the working directory, but the working
        // directory itself already covers it — "already accessible" wins.
        assertInstanceOf(AddDirValidation.AddDirectoryResult.AlreadyInWorkingDirectory.class, result);
    }
}
