package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.io.FileTextUtils;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Reads one immutable file snapshot for permission presentation preparation. */
@FunctionalInterface
public interface FileSnapshotReader {
    FileSnapshotReader STANDARD = path -> {
        try {
            return FileSnapshot.present(FileTextUtils.readWithMetadata(path).content());
        } catch (NoSuchFileException _) {
            return FileSnapshot.missing();
        } catch (IOException | RuntimeException error) {
            String warning = error.getMessage() == null
                ? "Unable to read file for preview"
                : "Unable to read file for preview: " + error.getMessage();
            return FileSnapshot.unreadable(true, warning);
        }
    };

    FileSnapshot read(Path path);

    /** Result of exactly one permission-preview read attempt. */
    record FileSnapshot(boolean exists, boolean readable, String content, String warning) {
        public FileSnapshot {
            content = content == null ? "" : content;
            warning = warning == null ? "" : warning;
        }

        public static FileSnapshot present(String content) {
            return new FileSnapshot(true, true, content, "");
        }

        public static FileSnapshot missing() {
            return new FileSnapshot(false, true, "", "");
        }

        public static FileSnapshot unreadable(boolean exists, String warning) {
            return new FileSnapshot(exists, false, "", warning);
        }
    }
}
