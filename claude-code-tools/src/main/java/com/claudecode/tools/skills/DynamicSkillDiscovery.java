package com.claudecode.tools.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Session-scoped nested skill discovery used by Read/Write/Edit.
 */
public final class DynamicSkillDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicSkillDiscovery.class);
    private static final long GIT_TIMEOUT_SECONDS = 2L;

    private final SkillLoader skillLoader;
    private final Set<String> attachmentTriggers;
    private final boolean enabled;
    private final Set<Path> checkedSkillDirs = ConcurrentHashMap.newKeySet();

    public DynamicSkillDiscovery(
            SkillLoader skillLoader, Set<String> attachmentTriggers, boolean enabled) {
        this.skillLoader = skillLoader;
        this.attachmentTriggers = attachmentTriggers;
        this.enabled = enabled;
    }

    /** Discovers every previously unseen nested {@code .claude/skills} root. */
    public void discover(Path filePath, String workingDirectory) {
        if (!enabled || filePath == null || workingDirectory == null) return;

        Path cwd = Path.of(workingDirectory).toAbsolutePath().normalize();
        Path absoluteFile = filePath.isAbsolute()
            ? filePath.toAbsolutePath().normalize()
            : cwd.resolve(filePath).normalize();
        Path current = absoluteFile.getParent();
        if (current == null || !current.startsWith(cwd)) return;

        List<Path> discoveredDeepestFirst = new ArrayList<>();
        while (current.startsWith(cwd) && !current.equals(cwd)) {
            Path skillDir = current.resolve(".claude").resolve("skills").normalize();
            if (checkedSkillDirs.add(skillDir) && Files.isDirectory(skillDir)
                    && !isGitIgnored(current, cwd)) {
                discoveredDeepestFirst.add(skillDir);
                attachmentTriggers.add(skillDir.toString());
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }

        // Apply shallower definitions first so deeper/closer definitions win.
        Collections.reverse(discoveredDeepestFirst);
        Runnable loadDiscoveredSkills = () -> {
            for (Path skillDir : discoveredDeepestFirst) {
                skillLoader.mergeDynamicProjectSkills(
                    skillLoader.loadFromDirectory(skillDir, Skill.SkillSource.PROJECT), cwd);
            }
        };
        if (attachmentTriggers instanceof DynamicSkillTriggerSet triggerSet) {

            // request therefore snapshots its listing before the discovered
            // skills become visible, while still persisting dynamic_skill.
            triggerSet.afterNextConsumption(loadDiscoveredSkills);
        } else {
            loadDiscoveredSkills.run();
        }
    }

    private static boolean isGitIgnored(Path path, Path cwd) {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "check-ignore", path.toString())
                .directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            LOG.debug("Dynamic skill gitignore probe failed for {}: {}", path, e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
