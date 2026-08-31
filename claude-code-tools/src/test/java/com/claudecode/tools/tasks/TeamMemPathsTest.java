package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import com.claudecode.core.memdir.AutoMemoryPrompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamMemPathsTest {

    @Test
    void getTeamMemPathEndsWithSeparator() {
        String dir = TeamMemPaths.getTeamMemPath("/abs/working/dir");
        assertTrue(Strings.CS.endsWith(dir, File.separator), "must carry a trailing separator: " + dir);
    }

    /**
     * Regression guard: the team-mem dir the scanner checks MUST be exactly the
     * one the memory system writes to ({@link AutoMemoryPrompt#resolveAutoMemPath}
     * + "team"). A prior implementation re-sanitized the raw cwd (and truncated
     * it), so when the cwd was a sub-directory of the git repo — or the path
     * exceeded the length cap — the guard checked a different directory than
     * the writer and silently let secrets through.
     */
    @Test
    void getTeamMemPathDelegatesToResolveAutoMemPath() {
        String wd = "/abs/working/dir";
        String expected = AutoMemoryPrompt.resolveAutoMemPath(Path.of(wd)).resolve("team").toString()
            + File.separator;
        assertEquals(expected, TeamMemPaths.getTeamMemPath(wd));
    }

    @Test
    void getTeamMemPathIsConsistentWithIsTeamMemPath(@TempDir Path tmp) {
        String teamMemDir = TeamMemPaths.getTeamMemPath(tmp.toString());
        assertTrue(TeamMemPaths.isTeamMemPath(teamMemDir + "MEMORY.md", tmp.toString()));
        assertTrue(TeamMemPaths.isTeamMemPath(teamMemDir + "sub/nested.md", tmp.toString()));
    }

    @Test
    void isTeamMemPathMatchesInsideDirButNotSibling(@TempDir Path tmp) {
        String teamMemDir = TeamMemPaths.getTeamMemPath(tmp.toString());
        // Inside the team-memory directory → true.
        assertTrue(TeamMemPaths.isTeamMemPath(teamMemDir + "MEMORY.md", tmp.toString()));
        assertTrue(TeamMemPaths.isTeamMemPath(teamMemDir + "sub/nested.md", tmp.toString()));
        // Sibling "team-evil" must NOT prefix-match "team/".
        Path memoryParent = Path.of(teamMemDir).getParent();
        String evilSibling = memoryParent.resolve("team-evil").resolve("x.md").toString();
        assertFalse(TeamMemPaths.isTeamMemPath(evilSibling, tmp.toString()));
        // Completely unrelated path → false.
        assertFalse(TeamMemPaths.isTeamMemPath("/etc/passwd", tmp.toString()));
    }
}
