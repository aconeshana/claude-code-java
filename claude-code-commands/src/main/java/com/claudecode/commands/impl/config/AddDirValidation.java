package com.claudecode.commands.impl.config;

import java.nio.file.Files;
import org.apache.commons.lang3.StringUtils;
import java.nio.file.Path;
import java.util.List;

/**
 * Validates a candidate directory path for {@code /add-dir} and formats the corresponding
 * help/error message.
 */
final class AddDirValidation {

    private AddDirValidation() {}


    sealed interface AddDirectoryResult {
        record Success(String absolutePath) implements AddDirectoryResult {}
        record EmptyPath() implements AddDirectoryResult {}
        record PathNotFound(String directoryPath, String absolutePath) implements AddDirectoryResult {}
        record NotADirectory(String directoryPath, String absolutePath) implements AddDirectoryResult {}
        record AlreadyInWorkingDirectory(String directoryPath, String workingDir) implements AddDirectoryResult {}
    }

    /**
     * Resolves and validates {@code directoryPath}.
     */
    static AddDirectoryResult validateDirectoryForWorkspace(
            String directoryPath, String workingDirectory, List<Path> accessibleDirectories) {
        if (StringUtils.isBlank(directoryPath)) {
            return new AddDirectoryResult.EmptyPath();
        }
        Path absolute = AddDirCommand.resolveAndExpand(directoryPath, workingDirectory);
        if (!Files.exists(absolute)) {
            return new AddDirectoryResult.PathNotFound(directoryPath, absolute.toString());
        }
        if (!Files.isDirectory(absolute)) {
            return new AddDirectoryResult.NotADirectory(directoryPath, absolute.toString());
        }
        for (Path workingDir : accessibleDirectories) {
            if (absolute.startsWith(workingDir.toAbsolutePath().normalize())) {
                return new AddDirectoryResult.AlreadyInWorkingDirectory(directoryPath, workingDir.toString());
            }
        }
        return new AddDirectoryResult.Success(absolute.toString());
    }


    static String addDirHelpMessage(AddDirectoryResult result) {
        return switch (result) {
            case AddDirectoryResult.EmptyPath _ -> "Please provide a directory path.";
            case AddDirectoryResult.PathNotFound r -> "Path " + r.absolutePath() + " was not found.";
            case AddDirectoryResult.NotADirectory r -> {
                Path parentDir = Path.of(r.absolutePath()).getParent();
                yield r.directoryPath() + " is not a directory. Did you mean to add the parent directory "
                    + (parentDir != null ? parentDir : "/") + "?";
            }
            case AddDirectoryResult.AlreadyInWorkingDirectory r -> r.directoryPath()
                + " is already accessible within the existing working directory " + r.workingDir() + ".";
            case AddDirectoryResult.Success r -> "Added " + r.absolutePath() + " as a working directory.";
        };
    }
}
