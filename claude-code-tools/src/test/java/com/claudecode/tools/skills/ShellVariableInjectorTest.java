package com.claudecode.tools.skills;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SessionIdentity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShellVariableInjectorTest {

    @Test
    void injectSkillDir() {
        ShellVariableInjector injector = new ShellVariableInjector();
        injector.setSkillDir(Path.of("/home/user/.claude/skills"));

        String result = injector.inject("Dir: ${CLAUDE_SKILL_DIR}/templates");
        assertTrue(Strings.CS.contains(result, "/home/user/.claude/skills"));
        assertFalse(Strings.CS.contains(result, "${CLAUDE_SKILL_DIR}"));
    }

    @Test
    void injectSessionId() {
        ShellVariableInjector injector = new ShellVariableInjector(SessionIdentity.of("session-123"));

        String result = injector.inject("Session: ${CLAUDE_SESSION_ID}");
        assertEquals("Session: session-123", result);
    }

    @Test
    void injectSessionId_reflectsLaterSwitch() {
        SessionIdentity identity = SessionIdentity.of("session-123");
        ShellVariableInjector injector = new ShellVariableInjector(identity);
        identity.set("session-456");

        String result = injector.inject("Session: ${CLAUDE_SESSION_ID}");
        assertEquals("Session: session-456", result);
    }

    @Test
    void injectCustomVariable() {
        ShellVariableInjector injector = new ShellVariableInjector();
        injector.setVariable("MY_VAR", "hello");

        String result = injector.inject("Value: ${MY_VAR}");
        assertEquals("Value: hello", result);
    }

    @Test
    void injectNullContent() {
        ShellVariableInjector injector = new ShellVariableInjector();
        assertNull(injector.inject(null));
    }

    @Test
    void injectEmptyContent() {
        ShellVariableInjector injector = new ShellVariableInjector();
        assertEquals("", injector.inject(""));
    }

    @Test
    void noVariablesUnchanged() {
        ShellVariableInjector injector = new ShellVariableInjector();
        String content = "No variables here.";
        assertEquals(content, injector.inject(content));
    }

    @Test
    void defaultConstructor_producesNonBlankSessionId() {
        // SessionIdentity guarantees a non-blank id, so unlike the old
        // String-based setSessionId(null), there is no "blank session id"
        // state to test — the default no-arg injector always resolves to a
        // real (random) id.
        ShellVariableInjector injector = new ShellVariableInjector();

        String result = injector.inject("Session: ${CLAUDE_SESSION_ID}");
        assertFalse(Strings.CS.endsWith(result, "Session: "));
        assertFalse(Strings.CS.contains(result, "${CLAUDE_SESSION_ID}"));
    }
}
