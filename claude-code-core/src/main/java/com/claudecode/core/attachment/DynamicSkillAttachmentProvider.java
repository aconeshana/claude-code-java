package com.claudecode.core.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.DynamicSkillAttachment;

/**
 * Detects skills newly dropped into trigger directories.
 */
public final class DynamicSkillAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "dynamic_skill";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        Set<String> triggers = ctx.dynamicSkillDirTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        List<AttachmentPayload> out = new ArrayList<>();
        try {
            for (String skillDir : List.copyOf(triggers)) {
                Path dir = Path.of(skillDir);
                if (!Files.isDirectory(dir)) continue;
                List<String> skillNames = new ArrayList<>();
                try (var stream = Files.list(dir)) {
                    for (Path entry : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                        if (Files.exists(entry.resolve("SKILL.md"))) {
                            skillNames.add(entry.getFileName().toString());
                        }
                    }
                } catch (IOException _) {
                    // Ignore unreadable trigger dirs.
                }
                if (!skillNames.isEmpty()) {
                    String displayPath = skillDir;
                    try {
                        if (ctx.workingDirectory() != null) {
                            displayPath = Path.of(ctx.workingDirectory())
                                .relativize(dir).toString();
                        }
                    } catch (RuntimeException _) {
                        // Non-relativizable; keep absolute path.
                    }
                    out.add(new DynamicSkillAttachment(skillDir, skillNames, displayPath));
                }
            }
        } finally {
            triggers.clear();
        }
        return out;
    }
}
